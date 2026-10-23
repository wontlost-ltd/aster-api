package io.aster.policy.repository;

import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyCatalog;
import io.aster.policy.entity.PolicyVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PolicySourceRepository 定义策略编目与版本查询的统一入口。
 * 通过抽象仓库与数据库交互，为上层服务屏蔽数据源细节。
 */
public interface PolicySourceRepository {

    /**
     * 根据租户、模块与函数定位当前可用的策略目录。
     *
     * @param tenantId  租户ID
     * @param module    模块名称
     * @param function  函数名称
     * @return 查询到的策略目录
     */
    Optional<PolicyCatalog> findActiveCatalog(String tenantId, String module, String function);

    /**
     * 依据策略目录主键获取其默认启用的策略版本。
     *
     * @param catalogId 策略目录ID（UUID）
     * @return 默认启用的策略版本
     */
    Optional<PolicyVersion> findActiveVersion(UUID catalogId);

    /**
     * 该租户下此模块/函数是否存在任何版本（不论状态）。
     * 用于区分「策略不存在」与「存在但没有可执行版本」两种评估失败。
     */
    boolean hasAnyVersion(String tenantId, String module, String function);

    /**
     * 查询指定策略版本下的核心 JSON 产物。
     *
     * @param versionId 策略版本ID
     * @return 匹配的核心 JSON 产物
     */
    Optional<PolicyArtifact> findCoreJsonArtifact(Long versionId);

    /**
     * 根据版本 ID 查询策略版本。
     *
     * @param versionId 策略版本ID
     * @return 策略版本实体
     */
    Optional<PolicyVersion> findVersionById(Long versionId);

    /**
     * 根据租户枚举其所有策略目录，便于缓存与预热。
     *
     * @param tenantId 租户ID
     * @return 租户下全部策略目录
     */
    List<PolicyCatalog> findByTenant(String tenantId);
}
