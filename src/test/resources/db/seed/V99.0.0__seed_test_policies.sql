-- 策略种子数据（仅用于测试）
-- 创建测试用的策略目录和版本，使用预编译的 Core JSON
-- Core JSON 格式遵循 aster-lang-truffle 的 CoreModel.java 定义

-- 1. 贷款评估策略 (aster.finance.loan.evaluateLoanEligibility)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'default',
    'aster.finance.loan',
    'evaluateLoanEligibility',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.evaluateLoanEligibility',
    1,
    'aster.finance.loan',
    'evaluateLoanEligibility',
    '// Test loan policy',
    true,
    'APPROVED',
    'default',
    'test-hash-loan-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

-- 获取刚插入的 version id 并更新 catalog 的 default_version_id
UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'evaluateLoanEligibility'
AND tenant_id = 'default';

-- 插入预编译的 Core JSON 作为 artifact
-- 使用 CoreModel.Module 格式: { name, decls: [Func] }
-- Func 需要 kind="Func", 返回类型使用 Data + Construct
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000001'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Data",
      "name": "LoanEligibilityResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateLoanEligibility",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LoanEligibilityResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test approval"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 450}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 360}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-loan-001',
    '{"functionSignature": "evaluateLoanEligibility(application: Object, applicant: Object): LoanEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 2. 医疗服务资格检查策略 (aster.healthcare.eligibility.checkServiceEligibility)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000002',
    'default',
    'aster.healthcare.eligibility',
    'checkServiceEligibility',
    'healthcare',
    '{"category": "healthcare", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.healthcare.eligibility.checkServiceEligibility',
    1,
    'aster.healthcare.eligibility',
    'checkServiceEligibility',
    '// Test healthcare policy',
    true,
    'APPROVED',
    'default',
    'test-hash-healthcare-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.healthcare.eligibility.checkServiceEligibility'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.healthcare.eligibility'
AND function_name = 'checkServiceEligibility'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000002'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.healthcare.eligibility",
  "decls": [
    {
      "kind": "Data",
      "name": "ServiceEligibilityResult",
      "fields": [
        {"name": "eligible", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "coveragePercent", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "estimatedCost", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "requiresPreAuth", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}
      ]
    },
    {
      "kind": "Func",
      "name": "checkServiceEligibility",
      "params": [
        {"name": "patient", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "service", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "ServiceEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "ServiceEligibilityResult",
              "fields": [
                {"name": "eligible", "expr": {"kind": "Bool", "value": true}},
                {"name": "coveragePercent", "expr": {"kind": "Int", "value": 80}},
                {"name": "estimatedCost", "expr": {"kind": "Int", "value": 300}},
                {"name": "requiresPreAuth", "expr": {"kind": "Bool", "value": false}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test eligibility check"}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-healthcare-001',
    '{"functionSignature": "checkServiceEligibility(patient: Object, service: Object): ServiceEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.healthcare.eligibility.checkServiceEligibility'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 3. 利率计算策略 (aster.finance.loan.determineInterestRateBps)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000003',
    'default',
    'aster.finance.loan',
    'determineInterestRateBps',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.determineInterestRateBps',
    1,
    'aster.finance.loan',
    'determineInterestRateBps',
    '// Test interest rate policy',
    true,
    'APPROVED',
    'default',
    'test-hash-interest-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.determineInterestRateBps'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'determineInterestRateBps'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000003'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Func",
      "name": "determineInterestRateBps",
      "params": [],
      "ret": {"kind": "TypeName", "name": "Int"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {"kind": "Int", "value": 450}
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-interest-001',
    '{"functionSignature": "determineInterestRateBps(): Int"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.determineInterestRateBps'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 4. 测试用简单策略 (aster.test.sample.testFunction) - 用于 DynamicPolicyExecutionTest
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000004',
    'test-tenant',
    'aster.test.sample',
    'testFunction',
    'test',
    '{"category": "test", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.test.sample.testFunction',
    1,
    'aster.test.sample',
    'testFunction',
    '// Test sample policy',
    true,
    'APPROVED',
    'test-tenant',
    'test-hash-sample-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.test.sample.testFunction'
    AND tenant_id = 'test-tenant'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.test.sample'
AND function_name = 'testFunction'
AND tenant_id = 'test-tenant';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000004'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.test.sample",
  "decls": [
    {
      "kind": "Func",
      "name": "testFunction",
      "params": [
        {"name": "amount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "term", "type": {"kind": "TypeName", "name": "Int"}}
      ],
      "ret": {"kind": "TypeName", "name": "Bool"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {"kind": "Bool", "value": true}
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-sample-001',
    '{"functionSignature": "testFunction(amount: Int, term: Int): Bool"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.test.sample.testFunction'
AND pv.tenant_id = 'test-tenant'
ON CONFLICT DO NOTHING;

-- 5. 人寿保险报价策略 (aster.insurance.life.generateLifeQuote)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000005',
    'default',
    'aster.insurance.life',
    'generateLifeQuote',
    'insurance',
    '{"category": "life-insurance", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.insurance.life.generateLifeQuote',
    1,
    'aster.insurance.life',
    'generateLifeQuote',
    '// Test life insurance policy',
    true,
    'APPROVED',
    'default',
    'test-hash-life-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.insurance.life.generateLifeQuote'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.insurance.life'
AND function_name = 'generateLifeQuote'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000005'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.insurance.life",
  "decls": [
    {
      "kind": "Data",
      "name": "LifeQuoteResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "monthlyPremium", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "coverageAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termYears", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "generateLifeQuote",
      "params": [
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "request", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LifeQuoteResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LifeQuoteResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test life quote"}},
                {"name": "monthlyPremium", "expr": {"kind": "Int", "value": 150}},
                {"name": "coverageAmount", "expr": {"kind": "Int", "value": 500000}},
                {"name": "termYears", "expr": {"kind": "Int", "value": 20}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-life-001',
    '{"functionSignature": "generateLifeQuote(applicant: Object, request: Object): LifeQuoteResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.insurance.life.generateLifeQuote'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 6. 人寿保险风险评分策略 (aster.insurance.life.calculateRiskScore)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000006',
    'default',
    'aster.insurance.life',
    'calculateRiskScore',
    'insurance',
    '{"category": "life-insurance", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.insurance.life.calculateRiskScore',
    1,
    'aster.insurance.life',
    'calculateRiskScore',
    '// Test risk score policy',
    true,
    'APPROVED',
    'default',
    'test-hash-risk-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.insurance.life.calculateRiskScore'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.insurance.life'
AND function_name = 'calculateRiskScore'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000006'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.insurance.life",
  "decls": [
    {
      "kind": "Func",
      "name": "calculateRiskScore",
      "params": [
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "Int"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {"kind": "Int", "value": 75}
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-risk-001',
    '{"functionSignature": "calculateRiskScore(applicant: Object): Int"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.insurance.life.calculateRiskScore'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 7. 汽车保险报价策略 (aster.insurance.auto.generateAutoQuote)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000007',
    'default',
    'aster.insurance.auto',
    'generateAutoQuote',
    'insurance',
    '{"category": "auto-insurance", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.insurance.auto.generateAutoQuote',
    1,
    'aster.insurance.auto',
    'generateAutoQuote',
    '// Test auto insurance policy',
    true,
    'APPROVED',
    'default',
    'test-hash-auto-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.insurance.auto.generateAutoQuote'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.insurance.auto'
AND function_name = 'generateAutoQuote'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000007'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.insurance.auto",
  "decls": [
    {
      "kind": "Data",
      "name": "AutoQuoteResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "monthlyPremium", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "deductible", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "coverageLimit", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "generateAutoQuote",
      "params": [
        {"name": "driver", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "vehicle", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "coverageType", "type": {"kind": "TypeName", "name": "Text"}}
      ],
      "ret": {"kind": "TypeName", "name": "AutoQuoteResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "AutoQuoteResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test auto quote"}},
                {"name": "monthlyPremium", "expr": {"kind": "Int", "value": 120}},
                {"name": "deductible", "expr": {"kind": "Int", "value": 500}},
                {"name": "coverageLimit", "expr": {"kind": "Int", "value": 100000}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-auto-001',
    '{"functionSignature": "generateAutoQuote(driver: Object, vehicle: Object, coverageType: Text): AutoQuoteResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.insurance.auto.generateAutoQuote'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 8. 医疗索赔处理策略 (aster.healthcare.claims.processClaim)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000008',
    'default',
    'aster.healthcare.claims',
    'processClaim',
    'healthcare',
    '{"category": "healthcare", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.healthcare.claims.processClaim',
    1,
    'aster.healthcare.claims',
    'processClaim',
    '// Test claim processing policy',
    true,
    'APPROVED',
    'default',
    'test-hash-claim-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.healthcare.claims.processClaim'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.healthcare.claims'
AND function_name = 'processClaim'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000008'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.healthcare.claims",
  "decls": [
    {
      "kind": "Data",
      "name": "ClaimDecisionResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "requiresReview", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "denialCode", "type": {"kind": "TypeName", "name": "Text"}}
      ]
    },
    {
      "kind": "Func",
      "name": "processClaim",
      "params": [
        {"name": "claim", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "provider", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "patientCoverage", "type": {"kind": "TypeName", "name": "Int"}}
      ],
      "ret": {"kind": "TypeName", "name": "ClaimDecisionResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "ClaimDecisionResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test claim approved"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 800}},
                {"name": "requiresReview", "expr": {"kind": "Bool", "value": false}},
                {"name": "denialCode", "expr": {"kind": "String", "value": "NONE"}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-claim-001',
    '{"functionSignature": "processClaim(claim: Object, provider: Object, patientCoverage: Int): ClaimDecisionResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.healthcare.claims.processClaim'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 9. 信用卡申请评估策略 (aster.finance.creditcard.evaluateCreditCardApplication)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000009',
    'default',
    'aster.finance.creditcard',
    'evaluateCreditCardApplication',
    'finance',
    '{"category": "creditcard", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.creditcard.evaluateCreditCardApplication',
    1,
    'aster.finance.creditcard',
    'evaluateCreditCardApplication',
    '// Test credit card policy',
    true,
    'APPROVED',
    'default',
    'test-hash-cc-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.creditcard.evaluateCreditCardApplication'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.creditcard'
AND function_name = 'evaluateCreditCardApplication'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000009'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.creditcard",
  "decls": [
    {
      "kind": "Data",
      "name": "CreditCardResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedLimit", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateAPR", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "monthlyFee", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "creditLine", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "requiresDeposit", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "depositAmount", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateCreditCardApplication",
      "params": [
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "creditHistory", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "product", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "CreditCardResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "CreditCardResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test credit card approval"}},
                {"name": "approvedLimit", "expr": {"kind": "Int", "value": 50000}},
                {"name": "interestRateAPR", "expr": {"kind": "Int", "value": 1899}},
                {"name": "monthlyFee", "expr": {"kind": "Int", "value": 0}},
                {"name": "creditLine", "expr": {"kind": "Int", "value": 50000}},
                {"name": "requiresDeposit", "expr": {"kind": "Bool", "value": false}},
                {"name": "depositAmount", "expr": {"kind": "Int", "value": 0}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-cc-001',
    '{"functionSignature": "evaluateCreditCardApplication(applicant: Object, creditHistory: Object, product: Object): CreditCardResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.creditcard.evaluateCreditCardApplication'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 10. 信用卡利用率惩罚计算策略 (aster.finance.creditcard.calculateUtilizationPenalty)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000010',
    'default',
    'aster.finance.creditcard',
    'calculateUtilizationPenalty',
    'finance',
    '{"category": "creditcard", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.creditcard.calculateUtilizationPenalty',
    1,
    'aster.finance.creditcard',
    'calculateUtilizationPenalty',
    '// Test utilization penalty policy',
    true,
    'APPROVED',
    'default',
    'test-hash-cc-util-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.creditcard.calculateUtilizationPenalty'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.creditcard'
AND function_name = 'calculateUtilizationPenalty'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000010'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.creditcard",
  "decls": [
    {
      "kind": "Func",
      "name": "calculateUtilizationPenalty",
      "params": [],
      "ret": {"kind": "TypeName", "name": "Int"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {"kind": "Int", "value": 50}
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-cc-util-001',
    '{"functionSignature": "calculateUtilizationPenalty(): Int"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.creditcard.calculateUtilizationPenalty'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 11. 医疗保险标准覆盖率计算策略 (aster.healthcare.eligibility.determineStandardCoverage)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000011',
    'default',
    'aster.healthcare.eligibility',
    'determineStandardCoverage',
    'healthcare',
    '{"category": "healthcare", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.healthcare.eligibility.determineStandardCoverage',
    1,
    'aster.healthcare.eligibility',
    'determineStandardCoverage',
    '// Test standard coverage policy',
    true,
    'APPROVED',
    'default',
    'test-hash-coverage-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.healthcare.eligibility.determineStandardCoverage'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.healthcare.eligibility'
AND function_name = 'determineStandardCoverage'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000011'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.healthcare.eligibility",
  "decls": [
    {
      "kind": "Func",
      "name": "determineStandardCoverage",
      "params": [],
      "ret": {"kind": "TypeName", "name": "Int"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {"kind": "Int", "value": 80}
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-coverage-001',
    '{"functionSignature": "determineStandardCoverage(): Int"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.healthcare.eligibility.determineStandardCoverage'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 12. 医疗保险患者费用计算策略 (aster.healthcare.eligibility.calculatePatientCost)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000012',
    'default',
    'aster.healthcare.eligibility',
    'calculatePatientCost',
    'healthcare',
    '{"category": "healthcare", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.healthcare.eligibility.calculatePatientCost',
    1,
    'aster.healthcare.eligibility',
    'calculatePatientCost',
    '// Test patient cost policy',
    true,
    'APPROVED',
    'default',
    'test-hash-cost-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.healthcare.eligibility.calculatePatientCost'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.healthcare.eligibility'
AND function_name = 'calculatePatientCost'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000012'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.healthcare.eligibility",
  "decls": [
    {
      "kind": "Func",
      "name": "calculatePatientCost",
      "params": [],
      "ret": {"kind": "TypeName", "name": "Int"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {"kind": "Int", "value": 200}
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-cost-001',
    '{"functionSignature": "calculatePatientCost(): Int"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.healthcare.eligibility.calculatePatientCost'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 13. 个人贷款评估策略 (aster.finance.personal_lending.evaluatePersonalLoan)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000013',
    'default',
    'aster.finance.personal_lending',
    'evaluatePersonalLoan',
    'finance',
    '{"category": "personal_lending", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.personal_lending.evaluatePersonalLoan',
    1,
    'aster.finance.personal_lending',
    'evaluatePersonalLoan',
    '// Test personal loan policy',
    true,
    'APPROVED',
    'default',
    'test-hash-personal-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.personal_lending.evaluatePersonalLoan'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.personal_lending'
AND function_name = 'evaluatePersonalLoan'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000013'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.personal_lending",
  "decls": [
    {
      "kind": "Data",
      "name": "PersonalLoanResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "monthlyPayment", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "downPaymentRequired", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "conditions", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "riskLevel", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "decisionScore", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "reasonCode", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "recommendations", "type": {"kind": "TypeName", "name": "Text"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluatePersonalLoan",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "PersonalLoanResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "PersonalLoanResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 30000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 850}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 36}},
                {"name": "monthlyPayment", "expr": {"kind": "Int", "value": 925}},
                {"name": "downPaymentRequired", "expr": {"kind": "Int", "value": 3000}},
                {"name": "conditions", "expr": {"kind": "String", "value": "Standard terms apply"}},
                {"name": "riskLevel", "expr": {"kind": "String", "value": "LOW"}},
                {"name": "decisionScore", "expr": {"kind": "Int", "value": 85}},
                {"name": "reasonCode", "expr": {"kind": "String", "value": "TEST_APPROVAL"}},
                {"name": "recommendations", "expr": {"kind": "String", "value": "Consider credit protection"}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-personal-001',
    '{"functionSignature": "evaluatePersonalLoan(application: Object, applicant: Object): PersonalLoanResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.personal_lending.evaluatePersonalLoan'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 14. 企业贷款评估策略 (aster.finance.enterprise_lending.evaluateEnterpriseLoan)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000014',
    'default',
    'aster.finance.enterprise_lending',
    'evaluateEnterpriseLoan',
    'finance',
    '{"category": "enterprise_lending", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.enterprise_lending.evaluateEnterpriseLoan',
    1,
    'aster.finance.enterprise_lending',
    'evaluateEnterpriseLoan',
    '// Test enterprise loan policy',
    true,
    'APPROVED',
    'default',
    'test-hash-enterprise-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.enterprise_lending.evaluateEnterpriseLoan'
    AND tenant_id = 'default'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.enterprise_lending'
AND function_name = 'evaluateEnterpriseLoan'
AND tenant_id = 'default';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000014'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.enterprise_lending",
  "decls": [
    {
      "kind": "Data",
      "name": "EnterpriseLoanResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "collateralRequired", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateEnterpriseLoan",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "company", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "EnterpriseLoanResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "EnterpriseLoanResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test enterprise loan approval"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 1000000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 500}},
                {"name": "collateralRequired", "expr": {"kind": "Bool", "value": false}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 60}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-enterprise-001',
    '{"functionSignature": "evaluateEnterpriseLoan(application: Object, company: Object): EnterpriseLoanResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.enterprise_lending.evaluateEnterpriseLoan'
AND pv.tenant_id = 'default'
ON CONFLICT DO NOTHING;

-- 15. 贷款评估策略 - 租户1 (tenant1)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000015',
    'tenant1',
    'aster.finance.loan',
    'evaluateLoanEligibility',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.evaluateLoanEligibility.tenant1',
    1,
    'aster.finance.loan',
    'evaluateLoanEligibility',
    '// Test loan policy for tenant1',
    true,
    'APPROVED',
    'tenant1',
    'test-hash-loan-t1-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant1'
    AND tenant_id = 'tenant1'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'evaluateLoanEligibility'
AND tenant_id = 'tenant1';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000015'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Data",
      "name": "LoanEligibilityResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateLoanEligibility",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LoanEligibilityResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test approval tenant1"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 300000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 400}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 120}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-loan-t1-001',
    '{"functionSignature": "evaluateLoanEligibility(application: Object, applicant: Object): LoanEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant1'
AND pv.tenant_id = 'tenant1'
ON CONFLICT DO NOTHING;

-- 16. 贷款评估策略 - 租户2 (tenant2)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000016',
    'tenant2',
    'aster.finance.loan',
    'evaluateLoanEligibility',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.evaluateLoanEligibility.tenant2',
    1,
    'aster.finance.loan',
    'evaluateLoanEligibility',
    '// Test loan policy for tenant2',
    true,
    'APPROVED',
    'tenant2',
    'test-hash-loan-t2-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant2'
    AND tenant_id = 'tenant2'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'evaluateLoanEligibility'
AND tenant_id = 'tenant2';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000016'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Data",
      "name": "LoanEligibilityResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateLoanEligibility",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LoanEligibilityResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test approval tenant2"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 300000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 420}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 120}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-loan-t2-001',
    '{"functionSignature": "evaluateLoanEligibility(application: Object, applicant: Object): LoanEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant2'
AND pv.tenant_id = 'tenant2'
ON CONFLICT DO NOTHING;

-- =============================================================================
-- 17-21. 多租户测试策略 (tenant-a, tenant-b, tenant-batch-*)
-- =============================================================================

-- 17. tenant-a 贷款评估策略
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000017',
    'tenant-a',
    'aster.finance.loan',
    'evaluateLoanEligibility',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.evaluateLoanEligibility.tenant-a',
    1,
    'aster.finance.loan',
    'evaluateLoanEligibility',
    '// Test loan policy for tenant-a',
    true,
    'APPROVED',
    'tenant-a',
    'test-hash-loan-ta-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-a'
    AND tenant_id = 'tenant-a'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'evaluateLoanEligibility'
AND tenant_id = 'tenant-a';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000017'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Data",
      "name": "LoanEligibilityResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateLoanEligibility",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LoanEligibilityResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test approval tenant-a"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 75000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 450}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 240}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-loan-ta-001',
    '{"functionSignature": "evaluateLoanEligibility(application: Object, applicant: Object): LoanEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-a'
AND pv.tenant_id = 'tenant-a'
ON CONFLICT DO NOTHING;

-- 18. tenant-b 贷款评估策略
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000018',
    'tenant-b',
    'aster.finance.loan',
    'evaluateLoanEligibility',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.evaluateLoanEligibility.tenant-b',
    1,
    'aster.finance.loan',
    'evaluateLoanEligibility',
    '// Test loan policy for tenant-b',
    true,
    'APPROVED',
    'tenant-b',
    'test-hash-loan-tb-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-b'
    AND tenant_id = 'tenant-b'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'evaluateLoanEligibility'
AND tenant_id = 'tenant-b';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000018'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Data",
      "name": "LoanEligibilityResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateLoanEligibility",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LoanEligibilityResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test approval tenant-b"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 70000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 480}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 180}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-loan-tb-001',
    '{"functionSignature": "evaluateLoanEligibility(application: Object, applicant: Object): LoanEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-b'
AND pv.tenant_id = 'tenant-b'
ON CONFLICT DO NOTHING;

-- 19. tenant-batch-parallel 贷款评估策略
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000019',
    'tenant-batch-parallel',
    'aster.finance.loan',
    'evaluateLoanEligibility',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.evaluateLoanEligibility.tenant-batch-parallel',
    1,
    'aster.finance.loan',
    'evaluateLoanEligibility',
    '// Test loan policy for tenant-batch-parallel',
    true,
    'APPROVED',
    'tenant-batch-parallel',
    'test-hash-loan-tbp-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-batch-parallel'
    AND tenant_id = 'tenant-batch-parallel'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'evaluateLoanEligibility'
AND tenant_id = 'tenant-batch-parallel';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000019'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Data",
      "name": "LoanEligibilityResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateLoanEligibility",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LoanEligibilityResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test batch parallel"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 60000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 500}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 60}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-loan-tbp-001',
    '{"functionSignature": "evaluateLoanEligibility(application: Object, applicant: Object): LoanEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-batch-parallel'
AND pv.tenant_id = 'tenant-batch-parallel'
ON CONFLICT DO NOTHING;

-- 20. tenant-batch-partial 贷款评估策略
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000020',
    'tenant-batch-partial',
    'aster.finance.loan',
    'evaluateLoanEligibility',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.evaluateLoanEligibility.tenant-batch-partial',
    1,
    'aster.finance.loan',
    'evaluateLoanEligibility',
    '// Test loan policy for tenant-batch-partial',
    true,
    'APPROVED',
    'tenant-batch-partial',
    'test-hash-loan-tbpa-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-batch-partial'
    AND tenant_id = 'tenant-batch-partial'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'evaluateLoanEligibility'
AND tenant_id = 'tenant-batch-partial';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000020'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Data",
      "name": "LoanEligibilityResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateLoanEligibility",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LoanEligibilityResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test batch partial"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 55000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 520}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 48}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-loan-tbpa-001',
    '{"functionSignature": "evaluateLoanEligibility(application: Object, applicant: Object): LoanEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-batch-partial'
AND pv.tenant_id = 'tenant-batch-partial'
ON CONFLICT DO NOTHING;

-- 21. tenant-batch-volume 贷款评估策略
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000021',
    'tenant-batch-volume',
    'aster.finance.loan',
    'evaluateLoanEligibility',
    'finance',
    '{"category": "loan", "test": true}',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES (
    'aster.finance.loan.evaluateLoanEligibility.tenant-batch-volume',
    1,
    'aster.finance.loan',
    'evaluateLoanEligibility',
    '// Test loan policy for tenant-batch-volume',
    true,
    'APPROVED',
    'tenant-batch-volume',
    'test-hash-loan-tbv-001',
    'zh-CN',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'system'
) ON CONFLICT DO NOTHING;

UPDATE policy_catalog
SET default_version_id = (
    SELECT id FROM policy_versions
    WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-batch-volume'
    AND tenant_id = 'tenant-batch-volume'
    ORDER BY version DESC LIMIT 1
)
WHERE module_name = 'aster.finance.loan'
AND function_name = 'evaluateLoanEligibility'
AND tenant_id = 'tenant-batch-volume';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT
    'b0000000-0000-0000-0000-000000000021'::uuid,
    pv.id,
    'CORE_JSON',
    E'{
  "name": "aster.finance.loan",
  "decls": [
    {
      "kind": "Data",
      "name": "LoanEligibilityResult",
      "fields": [
        {"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}},
        {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}},
        {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}},
        {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}
      ]
    },
    {
      "kind": "Func",
      "name": "evaluateLoanEligibility",
      "params": [
        {"name": "application", "type": {"kind": "TypeName", "name": "Object"}},
        {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}
      ],
      "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"},
      "body": {"kind": "Block", 
        "statements": [
          {
            "kind": "Return",
            "expr": {
              "kind": "Construct",
              "typeName": "LoanEligibilityResult",
              "fields": [
                {"name": "approved", "expr": {"kind": "Bool", "value": true}},
                {"name": "reason", "expr": {"kind": "String", "value": "Test batch volume"}},
                {"name": "approvedAmount", "expr": {"kind": "Int", "value": 80000}},
                {"name": "interestRateBps", "expr": {"kind": "Int", "value": 490}},
                {"name": "termMonths", "expr": {"kind": "Int", "value": 72}}
              ]
            }
          }
        ]
      }
    }
  ]
}'::bytea,
    'test-sha256-loan-tbv-001',
    '{"functionSignature": "evaluateLoanEligibility(application: Object, applicant: Object): LoanEligibilityResult"}',
    CURRENT_TIMESTAMP
FROM policy_versions pv
WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-batch-volume'
AND pv.tenant_id = 'tenant-batch-volume'
ON CONFLICT DO NOTHING;

-- =============================================================================
-- 22-40. 缓存和组合测试所需的租户策略
-- 批量创建 policy_catalog, policy_versions, policy_artifacts 记录
-- =============================================================================

-- 定义需要添加的租户列表（使用临时函数或多次插入）
-- 租户列表：tenant-cache, tenant-load-failure, tenant-execution-failure, tenant-eviction-clean,
--          tenant-composition-null, tenant-composition-empty, tenant-composition-order, tenant-composition-deep,
--          tenant-cache-create, tenant-cache-update, tenant-cache-delete, tenant-cache-multi,
--          tenant-invalidate-a, tenant-invalidate-b, tenant-flag-accuracy, tenant-flag-miss, tenant-flag-hit,
--          tenant-ttl-expire, tenant-concurrent

-- 22. tenant-cache
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000022', 'tenant-cache', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"category": "loan", "test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-cache', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-cache', 'test-hash-tc', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
ON CONFLICT DO NOTHING;

UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache' AND tenant_id = 'tenant-cache' ORDER BY version DESC LIMIT 1)
WHERE module_name = 'aster.finance.loan' AND function_name = 'evaluateLoanEligibility' AND tenant_id = 'tenant-cache';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000022'::uuid, pv.id, 'CORE_JSON',
E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test tenant-cache"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 65000}}, {"name": "interestRateBps", "expr": {"kind": "Int", "value": 450}}, {"name": "termMonths", "expr": {"kind": "Int", "value": 180}}]}}]}}]}'::bytea,
'test-sha256-tc', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache' AND pv.tenant_id = 'tenant-cache' ON CONFLICT DO NOTHING;

-- 23. tenant-composition-null
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000023', 'tenant-composition-null', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"category": "loan", "test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-composition-null', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-composition-null', 'test-hash-tcn', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
ON CONFLICT DO NOTHING;

UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-composition-null' AND tenant_id = 'tenant-composition-null' ORDER BY version DESC LIMIT 1)
WHERE module_name = 'aster.finance.loan' AND function_name = 'evaluateLoanEligibility' AND tenant_id = 'tenant-composition-null';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000023'::uuid, pv.id, 'CORE_JSON',
E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test comp null"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}, {"name": "interestRateBps", "expr": {"kind": "Int", "value": 500}}, {"name": "termMonths", "expr": {"kind": "Int", "value": 60}}]}}]}}]}'::bytea,
'test-sha256-tcn', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-composition-null' AND pv.tenant_id = 'tenant-composition-null' ON CONFLICT DO NOTHING;

-- 24. tenant-composition-empty
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000024', 'tenant-composition-empty', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"category": "loan", "test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-composition-empty', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-composition-empty', 'test-hash-tce', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
ON CONFLICT DO NOTHING;

UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-composition-empty' AND tenant_id = 'tenant-composition-empty' ORDER BY version DESC LIMIT 1)
WHERE module_name = 'aster.finance.loan' AND function_name = 'evaluateLoanEligibility' AND tenant_id = 'tenant-composition-empty';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000024'::uuid, pv.id, 'CORE_JSON',
E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test comp empty"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 45000}}, {"name": "interestRateBps", "expr": {"kind": "Int", "value": 520}}, {"name": "termMonths", "expr": {"kind": "Int", "value": 48}}]}}]}}]}'::bytea,
'test-sha256-tce', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-composition-empty' AND pv.tenant_id = 'tenant-composition-empty' ON CONFLICT DO NOTHING;

-- 25. tenant-composition-order
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000025', 'tenant-composition-order', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"category": "loan", "test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-composition-order', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-composition-order', 'test-hash-tco', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
ON CONFLICT DO NOTHING;

UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-composition-order' AND tenant_id = 'tenant-composition-order' ORDER BY version DESC LIMIT 1)
WHERE module_name = 'aster.finance.loan' AND function_name = 'evaluateLoanEligibility' AND tenant_id = 'tenant-composition-order';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000025'::uuid, pv.id, 'CORE_JSON',
E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test comp order"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 55000}}, {"name": "interestRateBps", "expr": {"kind": "Int", "value": 480}}, {"name": "termMonths", "expr": {"kind": "Int", "value": 72}}]}}]}}]}'::bytea,
'test-sha256-tco', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-composition-order' AND pv.tenant_id = 'tenant-composition-order' ON CONFLICT DO NOTHING;

-- 26. tenant-composition-deep
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000026', 'tenant-composition-deep', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"category": "loan", "test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-composition-deep', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-composition-deep', 'test-hash-tcd', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
ON CONFLICT DO NOTHING;

UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-composition-deep' AND tenant_id = 'tenant-composition-deep' ORDER BY version DESC LIMIT 1)
WHERE module_name = 'aster.finance.loan' AND function_name = 'evaluateLoanEligibility' AND tenant_id = 'tenant-composition-deep';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000026'::uuid, pv.id, 'CORE_JSON',
E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "interestRateBps", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "termMonths", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test comp deep"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 60000}}, {"name": "interestRateBps", "expr": {"kind": "Int", "value": 460}}, {"name": "termMonths", "expr": {"kind": "Int", "value": 84}}]}}]}}]}'::bytea,
'test-sha256-tcd', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-composition-deep' AND pv.tenant_id = 'tenant-composition-deep' ON CONFLICT DO NOTHING;

-- 27-40. 其他缓存测试租户 (使用相同模式)
-- tenant-cache-create, tenant-cache-update, tenant-cache-delete, tenant-cache-multi,
-- tenant-invalidate-a, tenant-invalidate-b, tenant-flag-accuracy, tenant-flag-miss, tenant-flag-hit,
-- tenant-ttl-expire, tenant-concurrent, tenant-eviction-clean, tenant-load-failure, tenant-execution-failure

-- 27. tenant-cache-create
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000027', 'tenant-cache-create', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-cache-create', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-cache-create', 'test-hash-27', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache-create' AND tenant_id = 'tenant-cache-create' LIMIT 1) WHERE tenant_id = 'tenant-cache-create' AND module_name = 'aster.finance.loan';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000027'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-27', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache-create' ON CONFLICT DO NOTHING;

-- 28. tenant-cache-update
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000028', 'tenant-cache-update', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-cache-update', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-cache-update', 'test-hash-28', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache-update' AND tenant_id = 'tenant-cache-update' LIMIT 1) WHERE tenant_id = 'tenant-cache-update' AND module_name = 'aster.finance.loan';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000028'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-28', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache-update' ON CONFLICT DO NOTHING;

-- 29. tenant-cache-delete
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000029', 'tenant-cache-delete', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-cache-delete', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-cache-delete', 'test-hash-29', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache-delete' AND tenant_id = 'tenant-cache-delete' LIMIT 1) WHERE tenant_id = 'tenant-cache-delete' AND module_name = 'aster.finance.loan';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000029'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-29', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache-delete' ON CONFLICT DO NOTHING;

-- 30. tenant-cache-multi
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000030', 'tenant-cache-multi', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-cache-multi', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-cache-multi', 'test-hash-30', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache-multi' AND tenant_id = 'tenant-cache-multi' LIMIT 1) WHERE tenant_id = 'tenant-cache-multi' AND module_name = 'aster.finance.loan';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000030'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-30', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-cache-multi' ON CONFLICT DO NOTHING;

-- 31. tenant-invalidate-a
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000031', 'tenant-invalidate-a', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-invalidate-a', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-invalidate-a', 'test-hash-31', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-invalidate-a' AND tenant_id = 'tenant-invalidate-a' LIMIT 1) WHERE tenant_id = 'tenant-invalidate-a' AND module_name = 'aster.finance.loan';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000031'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-31', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-invalidate-a' ON CONFLICT DO NOTHING;

-- 32. tenant-invalidate-b
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000032', 'tenant-invalidate-b', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.evaluateLoanEligibility.tenant-invalidate-b', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-invalidate-b', 'test-hash-32', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-invalidate-b' AND tenant_id = 'tenant-invalidate-b' LIMIT 1) WHERE tenant_id = 'tenant-invalidate-b' AND module_name = 'aster.finance.loan';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000032'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-32', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-invalidate-b' ON CONFLICT DO NOTHING;

-- 33-40. 更多租户 (tenant-flag-accuracy, tenant-flag-miss, tenant-flag-hit, tenant-ttl-expire, tenant-concurrent, tenant-eviction-clean)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at) VALUES
('a0000000-0000-0000-0000-000000000033', 'tenant-flag-accuracy', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('a0000000-0000-0000-0000-000000000034', 'tenant-flag-miss', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('a0000000-0000-0000-0000-000000000035', 'tenant-flag-hit', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('a0000000-0000-0000-0000-000000000036', 'tenant-ttl-expire', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('a0000000-0000-0000-0000-000000000037', 'tenant-concurrent', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('a0000000-0000-0000-0000-000000000038', 'tenant-eviction-clean', 'aster.finance.loan', 'evaluateLoanEligibility', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by) VALUES
('aster.finance.loan.evaluateLoanEligibility.tenant-flag-accuracy', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-flag-accuracy', 'test-hash-33', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
('aster.finance.loan.evaluateLoanEligibility.tenant-flag-miss', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-flag-miss', 'test-hash-34', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
('aster.finance.loan.evaluateLoanEligibility.tenant-flag-hit', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-flag-hit', 'test-hash-35', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
('aster.finance.loan.evaluateLoanEligibility.tenant-ttl-expire', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-ttl-expire', 'test-hash-36', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
('aster.finance.loan.evaluateLoanEligibility.tenant-concurrent', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-concurrent', 'test-hash-37', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
('aster.finance.loan.evaluateLoanEligibility.tenant-eviction-clean', 1, 'aster.finance.loan', 'evaluateLoanEligibility', '// Test', true, 'APPROVED', 'tenant-eviction-clean', 'test-hash-38', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
ON CONFLICT DO NOTHING;

UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-flag-accuracy' LIMIT 1) WHERE tenant_id = 'tenant-flag-accuracy' AND module_name = 'aster.finance.loan';
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-flag-miss' LIMIT 1) WHERE tenant_id = 'tenant-flag-miss' AND module_name = 'aster.finance.loan';
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-flag-hit' LIMIT 1) WHERE tenant_id = 'tenant-flag-hit' AND module_name = 'aster.finance.loan';
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-ttl-expire' LIMIT 1) WHERE tenant_id = 'tenant-ttl-expire' AND module_name = 'aster.finance.loan';
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-concurrent' LIMIT 1) WHERE tenant_id = 'tenant-concurrent' AND module_name = 'aster.finance.loan';
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.evaluateLoanEligibility.tenant-eviction-clean' LIMIT 1) WHERE tenant_id = 'tenant-eviction-clean' AND module_name = 'aster.finance.loan';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000033'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-33', '{}', CURRENT_TIMESTAMP FROM policy_versions pv WHERE pv.tenant_id = 'tenant-flag-accuracy' ON CONFLICT DO NOTHING;
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000034'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-34', '{}', CURRENT_TIMESTAMP FROM policy_versions pv WHERE pv.tenant_id = 'tenant-flag-miss' ON CONFLICT DO NOTHING;
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000035'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-35', '{}', CURRENT_TIMESTAMP FROM policy_versions pv WHERE pv.tenant_id = 'tenant-flag-hit' ON CONFLICT DO NOTHING;
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000036'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-36', '{}', CURRENT_TIMESTAMP FROM policy_versions pv WHERE pv.tenant_id = 'tenant-ttl-expire' ON CONFLICT DO NOTHING;
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000037'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-37', '{}', CURRENT_TIMESTAMP FROM policy_versions pv WHERE pv.tenant_id = 'tenant-concurrent' ON CONFLICT DO NOTHING;
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000038'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Data", "name": "LoanEligibilityResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateLoanEligibility", "params": [{"name": "application", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "LoanEligibilityResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "LoanEligibilityResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test"}}, {"name": "approvedAmount", "expr": {"kind": "Int", "value": 50000}}]}}]}}]}'::bytea, 'test-sha256-38', '{}', CURRENT_TIMESTAMP FROM policy_versions pv WHERE pv.tenant_id = 'tenant-eviction-clean' ON CONFLICT DO NOTHING;

-- ================================================================================
-- 组合策略函数 - Composition Test Functions
-- ================================================================================

-- 39. tenant-composition-null: aster.healthcare.eligibility.determineStandardCoverage (返回50)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000039', 'tenant-composition-null', 'aster.healthcare.eligibility', 'determineStandardCoverage', 'healthcare', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.healthcare.eligibility.determineStandardCoverage.tenant-composition-null', 1, 'aster.healthcare.eligibility', 'determineStandardCoverage', '// Test', true, 'APPROVED', 'tenant-composition-null', 'test-hash-39', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.healthcare.eligibility.determineStandardCoverage.tenant-composition-null' LIMIT 1) WHERE tenant_id = 'tenant-composition-null' AND module_name = 'aster.healthcare.eligibility';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000039'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.healthcare.eligibility", "decls": [{"kind": "Func", "name": "determineStandardCoverage", "params": [], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Int", "value": 50}}]}}]}'::bytea, 'test-sha256-39', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.healthcare.eligibility.determineStandardCoverage.tenant-composition-null' ON CONFLICT DO NOTHING;

-- 40. tenant-composition-empty: aster.healthcare.eligibility.calculatePatientCost (返回0)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000040', 'tenant-composition-empty', 'aster.healthcare.eligibility', 'calculatePatientCost', 'healthcare', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.healthcare.eligibility.calculatePatientCost.tenant-composition-empty', 1, 'aster.healthcare.eligibility', 'calculatePatientCost', '// Test', true, 'APPROVED', 'tenant-composition-empty', 'test-hash-40', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.healthcare.eligibility.calculatePatientCost.tenant-composition-empty' LIMIT 1) WHERE tenant_id = 'tenant-composition-empty' AND module_name = 'aster.healthcare.eligibility';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000040'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.healthcare.eligibility", "decls": [{"kind": "Func", "name": "calculatePatientCost", "params": [], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Int", "value": 0}}]}}]}'::bytea, 'test-sha256-40', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.healthcare.eligibility.calculatePatientCost.tenant-composition-empty' ON CONFLICT DO NOTHING;

-- 41. tenant-composition-order: aster.finance.loan.determineInterestRateBps (固定返回425)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000041', 'tenant-composition-order', 'aster.finance.loan', 'determineInterestRateBps', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.determineInterestRateBps.tenant-composition-order', 1, 'aster.finance.loan', 'determineInterestRateBps', '// Test', true, 'APPROVED', 'tenant-composition-order', 'test-hash-41', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.determineInterestRateBps.tenant-composition-order' LIMIT 1) WHERE tenant_id = 'tenant-composition-order' AND module_name = 'aster.finance.loan' AND function_name = 'determineInterestRateBps';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000041'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Func", "name": "determineInterestRateBps", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Int", "value": 425}}]}}]}'::bytea, 'test-sha256-41', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.determineInterestRateBps.tenant-composition-order' ON CONFLICT DO NOTHING;

-- 42. tenant-composition-order: aster.finance.creditcard.calculateUtilizationPenalty (始终返回100)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000042', 'tenant-composition-order', 'aster.finance.creditcard', 'calculateUtilizationPenalty', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-order', 1, 'aster.finance.creditcard', 'calculateUtilizationPenalty', '// Test', true, 'APPROVED', 'tenant-composition-order', 'test-hash-42', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-order' LIMIT 1) WHERE tenant_id = 'tenant-composition-order' AND module_name = 'aster.finance.creditcard';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000042'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.creditcard", "decls": [{"kind": "Func", "name": "calculateUtilizationPenalty", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Int", "value": 100}}]}}]}'::bytea, 'test-sha256-42', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-order' ON CONFLICT DO NOTHING;

-- 43. tenant-composition-deep: aster.finance.loan.determineInterestRateBps (固定返回425)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000043', 'tenant-composition-deep', 'aster.finance.loan', 'determineInterestRateBps', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.loan.determineInterestRateBps.tenant-composition-deep', 1, 'aster.finance.loan', 'determineInterestRateBps', '// Test', true, 'APPROVED', 'tenant-composition-deep', 'test-hash-43', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.determineInterestRateBps.tenant-composition-deep' LIMIT 1) WHERE tenant_id = 'tenant-composition-deep' AND module_name = 'aster.finance.loan' AND function_name = 'determineInterestRateBps';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000043'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Func", "name": "determineInterestRateBps", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Int", "value": 425}}]}}]}'::bytea, 'test-sha256-43', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.determineInterestRateBps.tenant-composition-deep' ON CONFLICT DO NOTHING;

-- 44. tenant-composition-deep: aster.finance.creditcard.calculateUtilizationPenalty (同上)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000044', 'tenant-composition-deep', 'aster.finance.creditcard', 'calculateUtilizationPenalty', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-deep', 1, 'aster.finance.creditcard', 'calculateUtilizationPenalty', '// Test', true, 'APPROVED', 'tenant-composition-deep', 'test-hash-44', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-deep' LIMIT 1) WHERE tenant_id = 'tenant-composition-deep' AND module_name = 'aster.finance.creditcard';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000044'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.creditcard", "decls": [{"kind": "Func", "name": "calculateUtilizationPenalty", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Int", "value": 100}}]}}]}'::bytea, 'test-sha256-44', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-deep' ON CONFLICT DO NOTHING;

-- 45. tenant-composition-deep-fail: aster.test.failure.failingPolicy (抛出异常)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000045', 'tenant-composition-deep-fail', 'aster.test.failure', 'failingPolicy', 'test', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.test.failure.failingPolicy.tenant-composition-deep-fail', 1, 'aster.test.failure', 'failingPolicy', '// Test', true, 'APPROVED', 'tenant-composition-deep-fail', 'test-hash-45', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.test.failure.failingPolicy.tenant-composition-deep-fail' LIMIT 1) WHERE tenant_id = 'tenant-composition-deep-fail' AND module_name = 'aster.test.failure';
-- failingPolicy 故意除以零（div(input, 0)）→ 运行时 BuiltinException，这是预期行为。
-- 用真运行时错误而非畸形 IR：早先此处误写 Err 用 "value" 键（规范字段是 "expr"）→
-- 反序列化成 Err null → 仅靠 interop 契约 crash（-ea 下）伪装成失败，掩盖真实意图。
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000045'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.test.failure", "decls": [{"kind": "Func", "name": "failingPolicy", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Call", "target": {"kind": "Name", "name": "div"}, "args": [{"kind": "Name", "name": "input"}, {"kind": "Int", "value": 0}]}}]}}]}'::bytea, 'test-sha256-45', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.test.failure.failingPolicy.tenant-composition-deep-fail' ON CONFLICT DO NOTHING;

-- 46-47. tenant-composition-deep-fail 还需要 determineInterestRateBps 和 calculateUtilizationPenalty
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at) VALUES
('a0000000-0000-0000-0000-000000000046', 'tenant-composition-deep-fail', 'aster.finance.loan', 'determineInterestRateBps', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('a0000000-0000-0000-0000-000000000047', 'tenant-composition-deep-fail', 'aster.finance.creditcard', 'calculateUtilizationPenalty', 'finance', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;

INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by) VALUES
('aster.finance.loan.determineInterestRateBps.tenant-composition-deep-fail', 1, 'aster.finance.loan', 'determineInterestRateBps', '// Test', true, 'APPROVED', 'tenant-composition-deep-fail', 'test-hash-46', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system'),
('aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-deep-fail', 1, 'aster.finance.creditcard', 'calculateUtilizationPenalty', '// Test', true, 'APPROVED', 'tenant-composition-deep-fail', 'test-hash-47', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system')
ON CONFLICT DO NOTHING;

UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.loan.determineInterestRateBps.tenant-composition-deep-fail' LIMIT 1) WHERE tenant_id = 'tenant-composition-deep-fail' AND module_name = 'aster.finance.loan' AND function_name = 'determineInterestRateBps';
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-deep-fail' LIMIT 1) WHERE tenant_id = 'tenant-composition-deep-fail' AND module_name = 'aster.finance.creditcard';

INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000046'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.loan", "decls": [{"kind": "Func", "name": "determineInterestRateBps", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Int", "value": 425}}]}}]}'::bytea, 'test-sha256-46', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.loan.determineInterestRateBps.tenant-composition-deep-fail' ON CONFLICT DO NOTHING;
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000047'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.creditcard", "decls": [{"kind": "Func", "name": "calculateUtilizationPenalty", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Int", "value": 100}}]}}]}'::bytea, 'test-sha256-47', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.creditcard.calculateUtilizationPenalty.tenant-composition-deep-fail' ON CONFLICT DO NOTHING;

-- 48-51. aster.test.batch.slowPolicy 用于批量测试 (返回输入值)
-- tenant-batch-parallel
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000048', 'tenant-batch-parallel', 'aster.test.batch', 'slowPolicy', 'test', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.test.batch.slowPolicy.tenant-batch-parallel', 1, 'aster.test.batch', 'slowPolicy', '// Test', true, 'APPROVED', 'tenant-batch-parallel', 'test-hash-48', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.test.batch.slowPolicy.tenant-batch-parallel' LIMIT 1) WHERE tenant_id = 'tenant-batch-parallel' AND module_name = 'aster.test.batch';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000048'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.test.batch", "decls": [{"kind": "Func", "name": "slowPolicy", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Name", "name": "input"}}]}}]}'::bytea, 'test-sha256-48', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.test.batch.slowPolicy.tenant-batch-parallel' ON CONFLICT DO NOTHING;

-- tenant-batch-partial
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000049', 'tenant-batch-partial', 'aster.test.batch', 'slowPolicy', 'test', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.test.batch.slowPolicy.tenant-batch-partial', 1, 'aster.test.batch', 'slowPolicy', '// Test', true, 'APPROVED', 'tenant-batch-partial', 'test-hash-49', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.test.batch.slowPolicy.tenant-batch-partial' LIMIT 1) WHERE tenant_id = 'tenant-batch-partial' AND module_name = 'aster.test.batch';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000049'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.test.batch", "decls": [{"kind": "Func", "name": "slowPolicy", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Name", "name": "input"}}]}}]}'::bytea, 'test-sha256-49', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.test.batch.slowPolicy.tenant-batch-partial' ON CONFLICT DO NOTHING;

-- tenant-batch-volume
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000050', 'tenant-batch-volume', 'aster.test.batch', 'slowPolicy', 'test', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.test.batch.slowPolicy.tenant-batch-volume', 1, 'aster.test.batch', 'slowPolicy', '// Test', true, 'APPROVED', 'tenant-batch-volume', 'test-hash-50', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.test.batch.slowPolicy.tenant-batch-volume' LIMIT 1) WHERE tenant_id = 'tenant-batch-volume' AND module_name = 'aster.test.batch';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000050'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.test.batch", "decls": [{"kind": "Func", "name": "slowPolicy", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Name", "name": "input"}}]}}]}'::bytea, 'test-sha256-50', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.test.batch.slowPolicy.tenant-batch-volume' ON CONFLICT DO NOTHING;

-- 51-52. aster.test.failure.failingPolicy 用于失败测试 (使用 Err 表达式抛出异常)
-- tenant-execution-failure
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000051', 'tenant-execution-failure', 'aster.test.failure', 'failingPolicy', 'test', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.test.failure.failingPolicy.tenant-execution-failure', 1, 'aster.test.failure', 'failingPolicy', '// Test', true, 'APPROVED', 'tenant-execution-failure', 'test-hash-51', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.test.failure.failingPolicy.tenant-execution-failure' LIMIT 1) WHERE tenant_id = 'tenant-execution-failure' AND module_name = 'aster.test.failure';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000051'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.test.failure", "decls": [{"kind": "Func", "name": "failingPolicy", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Call", "target": {"kind": "Name", "name": "div"}, "args": [{"kind": "Name", "name": "input"}, {"kind": "Int", "value": 0}]}}]}}]}'::bytea, 'test-sha256-51', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.test.failure.failingPolicy.tenant-execution-failure' ON CONFLICT DO NOTHING;

-- tenant-batch-partial
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000052', 'tenant-batch-partial', 'aster.test.failure', 'failingPolicy', 'test', '{"test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.test.failure.failingPolicy.tenant-batch-partial', 1, 'aster.test.failure', 'failingPolicy', '// Test', true, 'APPROVED', 'tenant-batch-partial', 'test-hash-52', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.test.failure.failingPolicy.tenant-batch-partial' LIMIT 1) WHERE tenant_id = 'tenant-batch-partial' AND module_name = 'aster.test.failure';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000052'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.test.failure", "decls": [{"kind": "Func", "name": "failingPolicy", "params": [{"name": "input", "type": {"kind": "TypeName", "name": "Int"}}], "ret": {"kind": "TypeName", "name": "Int"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Call", "target": {"kind": "Name", "name": "div"}, "args": [{"kind": "Name", "name": "input"}, {"kind": "Int", "value": 0}]}}]}}]}'::bytea, 'test-sha256-52', '{}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.test.failure.failingPolicy.tenant-batch-partial' ON CONFLICT DO NOTHING;

-- 53. 信用卡策略 for tenant-cache-multi (用于 testCacheInvalidationAcrossMultiplePolicies)
INSERT INTO policy_catalog (id, tenant_id, module_name, function_name, domain, tags, created_at, updated_at)
VALUES ('a0000000-0000-0000-0000-000000000053', 'tenant-cache-multi', 'aster.finance.creditcard', 'evaluateCreditCardApplication', 'finance', '{"category": "creditcard", "test": true}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, module_name, function_name) DO NOTHING;
INSERT INTO policy_versions (policy_id, version, module_name, function_name, content, active, status, tenant_id, source_hash, locale, created_at, activated_at, activated_by)
VALUES ('aster.finance.creditcard.evaluateCreditCardApplication.tenant-cache-multi', 1, 'aster.finance.creditcard', 'evaluateCreditCardApplication', '// Test credit card policy for tenant-cache-multi', true, 'APPROVED', 'tenant-cache-multi', 'test-hash-cc-multi', 'zh-CN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system') ON CONFLICT DO NOTHING;
UPDATE policy_catalog SET default_version_id = (SELECT id FROM policy_versions WHERE policy_id = 'aster.finance.creditcard.evaluateCreditCardApplication.tenant-cache-multi' AND tenant_id = 'tenant-cache-multi' LIMIT 1) WHERE tenant_id = 'tenant-cache-multi' AND module_name = 'aster.finance.creditcard';
INSERT INTO policy_artifacts (id, policy_version_id, artifact_type, content, content_sha256, compiler_opts, created_at)
SELECT 'b0000000-0000-0000-0000-000000000053'::uuid, pv.id, 'CORE_JSON', E'{"name": "aster.finance.creditcard", "decls": [{"kind": "Data", "name": "CreditCardResult", "fields": [{"name": "approved", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "reason", "type": {"kind": "TypeName", "name": "Text"}}, {"name": "approvedLimit", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "interestRateAPR", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "monthlyFee", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "creditLine", "type": {"kind": "TypeName", "name": "Int"}}, {"name": "requiresDeposit", "type": {"kind": "TypeName", "name": "Bool"}}, {"name": "depositAmount", "type": {"kind": "TypeName", "name": "Int"}}]}, {"kind": "Func", "name": "evaluateCreditCardApplication", "params": [{"name": "applicant", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "creditHistory", "type": {"kind": "TypeName", "name": "Object"}}, {"name": "product", "type": {"kind": "TypeName", "name": "Object"}}], "ret": {"kind": "TypeName", "name": "CreditCardResult"}, "body": {"kind": "Block", "statements": [{"kind": "Return", "expr": {"kind": "Construct", "typeName": "CreditCardResult", "fields": [{"name": "approved", "expr": {"kind": "Bool", "value": true}}, {"name": "reason", "expr": {"kind": "String", "value": "Test credit card approval for cache-multi"}}, {"name": "approvedLimit", "expr": {"kind": "Int", "value": 50000}}, {"name": "interestRateAPR", "expr": {"kind": "Int", "value": 1899}}, {"name": "monthlyFee", "expr": {"kind": "Int", "value": 0}}, {"name": "creditLine", "expr": {"kind": "Int", "value": 50000}}, {"name": "requiresDeposit", "expr": {"kind": "Bool", "value": false}}, {"name": "depositAmount", "expr": {"kind": "Int", "value": 0}}]}}]}}]}'::bytea, 'test-sha256-cc-multi', '{"functionSignature": "evaluateCreditCardApplication(applicant: Object, creditHistory: Object, product: Object): CreditCardResult"}', CURRENT_TIMESTAMP
FROM policy_versions pv WHERE pv.policy_id = 'aster.finance.creditcard.evaluateCreditCardApplication.tenant-cache-multi' AND pv.tenant_id = 'tenant-cache-multi' ON CONFLICT DO NOTHING;
