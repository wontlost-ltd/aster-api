package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 策略目录实体
 *
 * 该实体与 policy_catalog 表对应，用于记录租户在各模块与函数下可供调用的策略入口以及默认版本。
 * Panache Active Record 模式允许直接在实体上执行查询与持久化操作，便于后续扩展缓存或查询接口。
 */
@RegisterForReflection
@Entity
@Table(
    name = "policy_catalog",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "policy_catalog_unique_per_tenant",
            columnNames = {"tenant_id", "module_name", "function_name"}
        )
    },
    indexes = {
        @Index(name = "idx_catalog_tenant", columnList = "tenant_id")
    }
)
public class PolicyCatalog extends PanacheEntityBase {

    /**
     * 策略目录主键，使用 UUID 确保跨节点生成不冲突
     */
    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * 租户标识，用于多租户隔离策略
     */
    @Column(name = "tenant_id", nullable = false, length = 100)
    public String tenantId;

    /**
     * 策略所属模块（如 aster.finance.loan）
     */
    @Column(name = "module_name", nullable = false, length = 200)
    public String moduleName;

    /**
     * 策略入口函数名称（如 evaluateLoanEligibility）
     */
    @Column(name = "function_name", nullable = false, length = 200)
    public String functionName;

    /**
     * 策略所属领域标签，结合模块区分业务域
     */
    @Column(name = "domain", length = 50)
    public String domain;

    /**
     * 策略附加标签信息，使用 JSONB 存储灵活的维度
     */
    @Column(name = "tags", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    public String tags;

    /**
     * 默认策略版本ID，指向 policy_versions 表
     */
    @Column(name = "default_version_id")
    public Long defaultVersionId;

    /**
     * 创建时间用于审计策略目录变更
     */
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /**
     * 更新时间反映最新元数据修改
     */
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /** 外部归属（cloud 策略 id，ADR 0054 §11 D1）；null = aster-api 原生策略。 */
    @Column(name = "external_ref", length = 100)
    public String externalRef;

    /** 最近一次被接受的 cloud 部署代际；更小的代际按 {@code stale_generation} 拒绝。 */
    @Column(name = "external_generation")
    public Long externalGeneration;
}
