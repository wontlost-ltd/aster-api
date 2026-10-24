package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "policy_revalidation_run")
public class PolicyRevalidationRun extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "toolchain_id", nullable = false) public String toolchainId;
    @Column(nullable = false) public String scope;
    @Column(name = "scope_tenant") public String scopeTenant;
    @Column(nullable = false) public String status;
    @Column(nullable = false) public long cursor;
    @Column(name = "scope_max_version_id", nullable = false) public long scopeMaxVersionId;
    @Column(name = "heartbeat_at") public Instant heartbeatAt;
    @Column(nullable = false) public int total;
    @Column(nullable = false) public int checked;
    @Column(name = "invalid_count", nullable = false) public int invalidCount;
    @Column(name = "started_at") public Instant startedAt;
    @Column(name = "finished_at") public Instant finishedAt;
    @Column(columnDefinition = "text") public String error;
    /** 发起者：admin:<cloud 用户 id> 或 startup:toolchain-change；列上线前的历史任务为 null。 */
    @Column(name = "triggered_by") public String triggeredBy;
    /** enforce：判定立即对门禁生效；report：只记录为 advisory，不拦截。 */
    @Column(nullable = false) public String mode;
}
