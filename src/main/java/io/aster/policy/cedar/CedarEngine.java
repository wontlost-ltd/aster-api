package io.aster.policy.cedar;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.AuthorizationSuccessResponse;
import com.cedarpolicy.model.DetailedError;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.ValidationResponse;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.exception.AuthException;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.CedarList;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.PrimLong;
import com.cedarpolicy.value.PrimString;
import com.cedarpolicy.value.Value;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基于官方 cedar-java 的 Cedar 校验与授权封装（ADR 0047 §2）。
 *
 * <p>依赖结论（2026-10-12 于 Maven Central 核实）：
 * <ul>
 *   <li>坐标 {@code com.cedarpolicy:cedar-java:4.10.0}（当前最新稳定版），按官方 README 使用 {@code uber}
 *       classifier；主 jar 不含原生库，uber jar 内含 CedarJavaFFI：
 *       {@code jne/linux/{x86_64,aarch64}/libcedar_java_ffi.so}、{@code jne/macos/{x86_64,aarch64}}、
 *       {@code jne/windows/x86_64}。</li>
 *   <li>原生库经 fizzed JNE 按 OS/arch 解包加载（可用环境变量 {@code CEDAR_JAVA_FFI_LIB} 指定绝对路径覆盖）。</li>
 *   <li>linux 原生库链接 glibc（NEEDED libc.so.6 / libdl.so.2 / libpthread.so.0，符号版本 GLIBC_2.17/2.18），
 *       官方不提供 musl 构建。实测 Alpine（musl）上无法加载，故生产镜像 Dockerfile.jvm 使用 glibc 的
 *       {@code azul/zulu-openjdk-debian:25-jre}；以 {@code scripts/ci/cedar-native-smoke.sh} 复验。</li>
 * </ul>
 *
 * <p>语义约定：策略文本整体解析，Cedar 按出现顺序命名为 {@code policy0..N}，再按 {@code policyIds} 同序替换为
 * 调用方的稳定 id；校验使用 cedar-java 默认的严格（strict）模式；授权不传 schema，不做请求校验。
 */
@ApplicationScoped
public class CedarEngine {

    private static final String PARSED_ID_PREFIX = "policy";
    private static final EntityTypeName PRINCIPAL = typeName("Aster::Principal");
    private static final EntityTypeName RESOURCE = typeName("Aster::Resource");
    private static final EntityTypeName ACTION = typeName("Aster::Action");

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();

    /** 严格模式校验结果；errors 为面向人的诊断文本。 */
    public record ValidationResult(boolean ok, List<String> errors) {
    }

    /** 授权结果；determiningPolicyIds 按 policyIds 原始顺序排列。 */
    public record Decision(boolean allow, List<String> determiningPolicyIds, List<String> errors) {
    }

    /** 一次授权请求的 Aster 侧描述，context 值仅支持 Long/Integer、String、Boolean。 */
    public record CedarRequest(
        String principalId,
        String principalType,
        List<String> roles,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> context) {
    }

    public ValidationResult validate(String policiesText, List<String> policyIds, String schemaJson) {
        try {
            PolicySet policies = parse(policiesText, policyIds);
            Schema schema = Schema.parse(Schema.JsonOrCedar.Json, schemaJson);
            ValidationResponse response = engine.validate(new ValidationRequest(schema, policies));
            return new ValidationResult(response.validationPassed(), validationErrors(response));
        } catch (AuthException e) {
            return new ValidationResult(false, List.of(String.valueOf(e.getMessage())));
        }
    }

    /**
     * 执行授权。context 中出现不支持的值类型时抛 {@link IllegalArgumentException}，由调用方处理。
     */
    public Decision authorize(String policiesText, List<String> policyIds, CedarRequest request) {
        try {
            PolicySet policies = parse(policiesText, policyIds);
            Map<String, Value> context = toCedarContext(request.context());
            Entity principal = principalEntity(request);
            Entity resource = resourceEntity(request);
            Entity action = new Entity(ACTION.of(request.action()));
            AuthorizationRequest query = new AuthorizationRequest(
                principal.getEUID(), action.getEUID(), resource.getEUID(),
                Optional.of(context), Optional.empty(), false);
            AuthorizationResponse response = engine.isAuthorized(query, policies, Set.of(principal, resource, action));
            return toDecision(response, policyIds);
        } catch (AuthException e) {
            return new Decision(false, List.of(), List.of(String.valueOf(e.getMessage())));
        }
    }

    /** 整体解析策略文本，并把 Cedar 自动分配的 policyN 映射为调用方 id。 */
    private static PolicySet parse(String policiesText, List<String> policyIds) throws AuthException {
        PolicySet parsed = PolicySet.parsePolicies(policiesText);
        if (parsed.getNumPolicies() != policyIds.size() || parsed.getNumTemplates() != 0) {
            throw new IllegalArgumentException("策略条数与 policyIds 不一致：解析得 "
                + parsed.getNumPolicies() + " 条（模板 " + parsed.getNumTemplates() + "），id " + policyIds.size() + " 个");
        }
        Set<Policy> renamed = new HashSet<>();
        for (Policy p : parsed.policies) {
            renamed.add(new Policy(p.getSource(), policyIds.get(parsedIndex(p.getID()))));
        }
        return new PolicySet(renamed);
    }

    private static int parsedIndex(String parsedId) {
        if (!parsedId.startsWith(PARSED_ID_PREFIX)) {
            throw new IllegalStateException("cedar-java 返回了非预期的策略 id：" + parsedId);
        }
        return Integer.parseInt(parsedId.substring(PARSED_ID_PREFIX.length()));
    }

    private static Entity principalEntity(CedarRequest request) {
        List<Value> roles = new ArrayList<>();
        request.roles().forEach(r -> roles.add(new PrimString(r)));
        Map<String, Value> attrs = Map.of(
            "type", new PrimString(request.principalType()),
            "roles", new CedarList(roles));
        return new Entity(PRINCIPAL.of(request.principalId()), attrs, Set.of());
    }

    private static Entity resourceEntity(CedarRequest request) {
        Map<String, Value> attrs = Map.of("type", new PrimString(request.resourceType()));
        return new Entity(RESOURCE.of(request.resourceId()), attrs, Set.of());
    }

    private static Map<String, Value> toCedarContext(Map<String, Object> context) {
        Map<String, Value> out = new HashMap<>();
        context.forEach((k, v) -> out.put(k, toCedarValue(k, v)));
        return out;
    }

    private static Value toCedarValue(String key, Object v) {
        return switch (v) {
            case Long l -> new PrimLong(l);
            case Integer i -> new PrimLong(i.longValue());
            case String s -> new PrimString(s);
            case Boolean b -> new PrimBool(b);
            case null, default -> throw new IllegalArgumentException(
                "context." + key + " 的值类型不受支持：" + (v == null ? "null" : v.getClass().getName()));
        };
    }

    private static Decision toDecision(AuthorizationResponse response, List<String> policyIds) {
        if (response.success.isEmpty()) {
            return new Decision(false, List.of(), detailed(response.errors.orElseThrow()));
        }
        AuthorizationSuccessResponse ok = response.success.get();
        Set<String> reasons = ok.getReason();
        List<String> determining = policyIds.stream().filter(reasons::contains).toList();
        List<String> errors = ok.getErrors().stream()
            .map(e -> e.getPolicyId() + ": " + e.getError().message)
            .toList();
        return new Decision(ok.isAllowed(), determining, errors);
    }

    private static List<String> validationErrors(ValidationResponse response) {
        if (response.success.isEmpty()) {
            return detailed(response.errors.orElseThrow());
        }
        return response.success.get().validationErrors.stream()
            .map(e -> e.getPolicyId() + ": " + e.getError().message)
            .toList();
    }

    private static List<String> detailed(List<DetailedError> errors) {
        return errors.stream().map(e -> e.message).toList();
    }

    private static EntityTypeName typeName(String name) {
        return EntityTypeName.parse(name).orElseThrow(() -> new IllegalStateException("非法 Cedar 实体类型：" + name));
    }
}
