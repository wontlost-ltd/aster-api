package io.aster.policy.compiler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.entity.PolicyVersionValidation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ValidationRepository {
    @Inject EntityManager entityManager;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Optional<ValidationStatus> findStatus(long versionId, String toolchainId) {
        List<?> rows = entityManager.createNativeQuery("""
            SELECT status, diagnostics FROM policy_version_validation
            WHERE version_id = :versionId AND toolchain_id = :toolchainId
            """).setParameter("versionId", versionId).setParameter("toolchainId", toolchainId).getResultList();
        if (rows.isEmpty()) return Optional.empty();
        Object[] row = (Object[]) rows.get(0);
        return Optional.of(new ValidationStatus((String) row[0], decodeDiagnostics(row[1])));
    }

    /** 独立事务写入：门禁可能在无事务的热路径上同步验证，不能依赖调用方事务。 */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void insertIfAbsent(long versionId, String toolchainId, String status,
                               List<CompilationResult.Diagnostic> diagnostics, Long runId) {
        String json = encodeDiagnostics(diagnostics);
        entityManager.createNativeQuery("""
            INSERT INTO policy_version_validation(version_id, toolchain_id, status, diagnostics, run_id, checked_at)
            VALUES (:versionId, :toolchainId, :status, CAST(:diagnostics AS jsonb), :runId, :checkedAt)
            ON CONFLICT (version_id, toolchain_id) DO NOTHING
            """).setParameter("versionId", versionId).setParameter("toolchainId", toolchainId)
            .setParameter("status", status).setParameter("diagnostics", json).setParameter("runId", runId)
            .setParameter("checkedAt", Instant.now()).executeUpdate();
    }


    /** 失败但没有结构化诊断（如解析失败、源码为空）时，把错误文本落成 E000 诊断，保证 invalid 行可解释。 */
    public static List<CompilationResult.Diagnostic> diagnosticsOf(CompilationResult result) {
        if (!result.getDiagnostics().isEmpty() || result.getErrors().isEmpty()) return result.getDiagnostics();
        return result.getErrors().stream()
            .map(message -> new CompilationResult.Diagnostic(1, 1, 1, 1, message, "E000", "error", null, null, true))
            .toList();
    }

    public static boolean isValid(CompilationResult result) {
        return result.isSuccess() && result.getDiagnostics().stream()
            .noneMatch(diagnostic -> diagnostic.code() != null && diagnostic.code().startsWith("E"));
    }

    private static String encodeDiagnostics(List<CompilationResult.Diagnostic> diagnostics) {
        try { return MAPPER.writeValueAsString(diagnostics == null ? List.of() : diagnostics); }
        catch (Exception e) { throw new IllegalStateException("诊断序列化失败", e); }
    }

    private static List<CompilationResult.Diagnostic> decodeDiagnostics(Object value) {
        if (value == null) return List.of();
        try { return MAPPER.readValue(value.toString(), new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("验证诊断 JSON 损坏", e); }
    }

    public record ValidationStatus(String status, List<CompilationResult.Diagnostic> diagnostics) {}
}
