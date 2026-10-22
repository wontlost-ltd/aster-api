package io.aster.policy.repository;

import io.aster.policy.entity.ArtifactType;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PolicySourceRepository 的默认实现，基于 Panache Active Record 进行查询。
 * 目标是简化上层服务的依赖，避免直接操作实体，集中处理空值与查询约束。
 */
@ApplicationScoped
public class PolicySourceRepositoryImpl implements PolicySourceRepository {

    @Override
    public Optional<PolicyCatalog> findActiveCatalog(String tenantId, String module, String function) {
        if (tenantId == null || module == null || function == null) {
            return Optional.empty();
        }

        return PolicyCatalog.<PolicyCatalog>find(
            "tenantId = ?1 and moduleName = ?2 and functionName = ?3",
            tenantId,
            module,
            function
        ).firstResultOptional();
    }

    @Override
    public Optional<PolicyVersion> findActiveVersion(UUID catalogId) {
        if (catalogId == null) {
            return Optional.empty();
        }

        return PolicyCatalog.<PolicyCatalog>findByIdOptional(catalogId)
            .flatMap(catalog -> {
                Long defaultVersionId = catalog.defaultVersionId;
                if (defaultVersionId == null) {
                    return Optional.empty();
                }

                return PolicyVersion.<PolicyVersion>find(
                    "id = ?1 and active = ?2",
                    defaultVersionId,
                    true
                ).firstResultOptional();
            });
    }

    @Override
    public boolean hasAnyVersion(String tenantId, String module, String function) {
        if (tenantId == null || module == null || function == null) {
            return false;
        }
        return PolicyVersion.count(
            "tenantId = ?1 and moduleName = ?2 and functionName = ?3",
            tenantId,
            module,
            function
        ) > 0;
    }

    @Override
    public Optional<PolicyArtifact> findCoreJsonArtifact(Long versionId) {
        if (versionId == null) {
            return Optional.empty();
        }

        return PolicyArtifact.<PolicyArtifact>find(
            "policyVersionId = ?1 and artifactType = ?2",
            versionId,
            ArtifactType.CORE_JSON.name()
        ).firstResultOptional();
    }

    @Override
    public Optional<PolicyVersion> findVersionById(Long versionId) {
        if (versionId == null) {
            return Optional.empty();
        }
        return PolicyVersion.findByIdOptional(versionId);
    }

    @Override
    public List<PolicyCatalog> findByTenant(String tenantId) {
        if (tenantId == null) {
            return Collections.emptyList();
        }

        return PolicyCatalog.<PolicyCatalog>list("tenantId", tenantId);
    }
}
