package io.aster.policy.graphql.converter;

import io.aster.policy.graphql.types.HealthcareTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import static io.aster.policy.graphql.converter.MapConversionUtils.getFieldValue;

/**
 * 医疗领域转换器，覆盖资格检查与索赔处理两类场景。
 *
 * <p>推荐策略端返回包含 {@code _type} 字段的 Map，用于明确标识返回类型：</p>
 * <ul>
 *   <li>{@code claim}：索赔结果</li>
 *   <li>{@code eligibility}：资格检查结果</li>
 * </ul>
 *
 * <p>仍然保留字段探测与反射回退机制，以兼容历史实现。</p>
 */
@ApplicationScoped
public class HealthcareConverter implements PolicyGraphQLConverter<HealthcareConverter.HealthcareInput, Object> {

    /**
     * 医疗策略输入标记接口。
     */
    public interface HealthcareInput {
    }

    /**
     * 医疗服务资格检查输入。
     */
    public static class EligibilityInput implements HealthcareInput {
        public final HealthcareTypes.Patient patient;
        public final HealthcareTypes.Service service;

        public EligibilityInput(HealthcareTypes.Patient patient, HealthcareTypes.Service service) {
            this.patient = patient;
            this.service = service;
        }
    }

    /**
     * 医疗索赔处理输入。
     */
    public static class ClaimInput implements HealthcareInput {
        public final HealthcareTypes.Claim claim;
        public final HealthcareTypes.Provider provider;
        public final Integer patientCoverage;

        public ClaimInput(HealthcareTypes.Claim claim, HealthcareTypes.Provider provider, Integer patientCoverage) {
            this.claim = claim;
            this.provider = provider;
            this.patientCoverage = patientCoverage;
        }
    }

    @Override
    public Map<String, Object> toAsterContext(HealthcareInput gqlInput, String tenantId) {
        if (gqlInput instanceof EligibilityInput eligibility) {
            return Map.of(
                "patient", convertPatient(eligibility.patient),
                "service", convertService(eligibility.service)
            );
        }
        if (gqlInput instanceof ClaimInput claim) {
            return Map.of(
                "claim", convertClaim(claim.claim),
                "provider", convertProvider(claim.provider),
                "patientCoverage", claim.patientCoverage
            );
        }
        throw new IllegalArgumentException("Unsupported healthcare input type: " + gqlInput);
    }

    @Override
    public Object toGraphQLResponse(Object asterResult) {
        if (asterResult == null) {
            return null;
        }

        if (asterResult instanceof Map<?, ?> resultMap) {
            return convertFromMap(resultMap);
        }

        return convertFromPojo(asterResult);
    }

    @Override
    public String getDomain() {
        return "healthcare";
    }

    private Map<String, Object> convertPatient(HealthcareTypes.Patient patient) {
        return Map.of(
            "patientId", patient.patientId,
            "age", patient.age,
            "insuranceType", patient.insuranceType,
            "hasInsurance", patient.hasInsurance,
            "chronicConditions", patient.chronicConditions
        );
    }

    private Map<String, Object> convertService(HealthcareTypes.Service service) {
        return Map.of(
            "serviceCode", service.serviceCode,
            "serviceName", service.serviceName,
            "basePrice", service.basePrice,
            "requiresPreAuth", service.requiresPreAuth
        );
    }

    private Map<String, Object> convertClaim(HealthcareTypes.Claim claim) {
        return Map.of(
            "claimId", claim.claimId,
            "amount", claim.amount,
            "serviceDate", claim.serviceDate,
            "diagnosisCode", claim.diagnosisCode,
            "patientId", claim.patientId,
            "providerId", claim.providerId
        );
    }

    private Map<String, Object> convertProvider(HealthcareTypes.Provider provider) {
        return Map.of(
            "providerId", provider.providerId,
            "inNetwork", provider.inNetwork,
            "qualityScore", provider.qualityScore,
            "specialtyType", provider.specialtyType
        );
    }

    private Object convertFromMap(Map<?, ?> resultMap) {
        // 变体/结构体类型标签**同时容忍两种拼写**（aster-lang-ts#137）：
        // TS 引擎产出 `__type`、Truffle 此前产出 `_type`，现已统一为 `__type`。
        // 这里读两种是为跨版本兼容——若只认新标签，旧引擎（或缓存的旧结果）
        // 会静默落到「没有类型标记」分支、退化成通用转换，而不是响亮失败。
        Object typeIndicator = resultMap.get("__type");
        if (typeIndicator == null) {
            typeIndicator = resultMap.get("_type");
        }
        if (typeIndicator != null) {
            String type = String.valueOf(typeIndicator);
            if ("claim".equalsIgnoreCase(type)) {
                return convertClaimDecision(resultMap);
            }
            if ("eligibility".equalsIgnoreCase(type)) {
                return convertEligibilityResult(resultMap);
            }
        }

        if (resultMap.containsKey("approved") || resultMap.containsKey("approvedAmount")) {
            return convertClaimDecision(resultMap);
        }
        if (resultMap.containsKey("eligible") || resultMap.containsKey("coveragePercent")) {
            return convertEligibilityResult(resultMap);
        }

        throw new RuntimeException("Unsupported healthcare result map: missing _type and known fields");
    }

    private Object convertFromPojo(Object asterResult) {
        Class<?> resultClass = asterResult.getClass();
        // 尝试检测eligible字段（使用getDeclaredField支持私有字段和记录类）
        if (hasField(resultClass, "eligible")) {
            try {
                return convertEligibilityResult(asterResult, resultClass);
            } catch (Exception e) {
                throw new RuntimeException("Failed to convert eligibility result", e);
            }
        }

        // 尝试检测approved字段
        if (hasField(resultClass, "approved")) {
            try {
                return convertClaimDecision(asterResult, resultClass);
            } catch (Exception e) {
                throw new RuntimeException("Failed to convert claim decision", e);
            }
        }

        throw new RuntimeException("Unsupported healthcare result type: missing both 'eligible' and 'approved' fields");
    }

    /**
     * 检查类是否包含指定字段（支持私有字段和记录类）。
     */
    private boolean hasField(Class<?> clazz, String fieldName) {
        try {
            clazz.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException e) {
            // 对于记录类，还需要检查父类
            Class<?> superclass = clazz.getSuperclass();
            if (superclass != null && superclass != Object.class) {
                return hasField(superclass, fieldName);
            }
            return false;
        }
    }

    private HealthcareTypes.EligibilityCheck convertEligibilityResult(Map<?, ?> resultMap) {
        Boolean eligible = asBoolean(resultMap.get("eligible"));
        String reason = asString(resultMap.get("reason"));
        Integer coveragePercent = asInteger(resultMap.get("coveragePercent"));
        Integer estimatedCost = asInteger(resultMap.get("estimatedCost"));
        Boolean requiresPreAuth = asBoolean(resultMap.get("requiresPreAuth"));

        return new HealthcareTypes.EligibilityCheck(eligible, reason, coveragePercent, estimatedCost, requiresPreAuth);
    }

    private HealthcareTypes.ClaimDecision convertClaimDecision(Map<?, ?> resultMap) {
        Boolean approved = asBoolean(resultMap.get("approved"));
        String reason = asString(resultMap.get("reason"));
        Integer approvedAmount = asInteger(resultMap.get("approvedAmount"));
        Boolean requiresReview = asBoolean(resultMap.get("requiresReview"));
        String denialCode = asString(resultMap.get("denialCode"));

        return new HealthcareTypes.ClaimDecision(approved, reason, approvedAmount, requiresReview, denialCode);
    }

    private HealthcareTypes.EligibilityCheck convertEligibilityResult(Object asterResult, Class<?> resultClass) throws Exception {
        Boolean eligible = getFieldValue(asterResult, "eligible", Boolean.class);
        String reason = getFieldValue(asterResult, "reason", String.class);
        Integer coveragePercent = getFieldValue(asterResult, "coveragePercent", Integer.class);
        Integer estimatedCost = getFieldValue(asterResult, "estimatedCost", Integer.class);
        Boolean requiresPreAuth = getFieldValue(asterResult, "requiresPreAuth", Boolean.class);

        return new HealthcareTypes.EligibilityCheck(eligible, reason, coveragePercent, estimatedCost, requiresPreAuth);
    }

    private HealthcareTypes.ClaimDecision convertClaimDecision(Object asterResult, Class<?> resultClass) throws Exception {
        Boolean approved = getFieldValue(asterResult, "approved", Boolean.class);
        String reason = getFieldValue(asterResult, "reason", String.class);
        Integer approvedAmount = getFieldValue(asterResult, "approvedAmount", Integer.class);
        Boolean requiresReview = getFieldValue(asterResult, "requiresReview", Boolean.class);
        String denialCode = getFieldValue(asterResult, "denialCode", String.class);

        return new HealthcareTypes.ClaimDecision(approved, reason, approvedAmount, requiresReview, denialCode);
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integer) {
            return integer;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
