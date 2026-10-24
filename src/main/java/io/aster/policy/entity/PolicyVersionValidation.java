package io.aster.policy.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "policy_version_validation")
@IdClass(PolicyVersionValidation.Key.class)
public class PolicyVersionValidation extends PanacheEntityBase {
    @Id @Column(name = "version_id", nullable = false) public Long versionId;
    @Id @Column(name = "toolchain_id", nullable = false) public String toolchainId;
    @Column(nullable = false) public String status;
    @Column(columnDefinition = "jsonb") public String diagnostics;
    @Column(name = "diagnostics_schema", nullable = false) public int diagnosticsSchema = 1;
    @Column(name = "run_id") public Long runId;
    @Column(name = "checked_at", nullable = false) public Instant checkedAt;

    public static class Key implements Serializable {
        public Long versionId;
        public String toolchainId;
        public Key() {}
        public Key(Long versionId, String toolchainId) { this.versionId = versionId; this.toolchainId = toolchainId; }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && java.util.Objects.equals(versionId, key.versionId)
                && java.util.Objects.equals(toolchainId, key.toolchainId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(versionId, toolchainId); }
    }
}
