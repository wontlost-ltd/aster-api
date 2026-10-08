package io.aster.security.apikey;

import io.aster.billing.snapshot.ApiKeySnapshot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyVerifyResultTest {

    @Test
    void invalid_factory_setsValidFalse() {
        ApiKeyVerifyResult r = ApiKeyVerifyResult.invalid("revoked");
        assertThat(r.valid()).isFalse();
        assertThat(r.reason()).isEqualTo("revoked");
        assertThat(r.userId()).isNull();
    }

    @Test
    void valid_factory_setsAllFields() {
        ApiKeyVerifyResult r = ApiKeyVerifyResult.valid("key-1", "user-1", "tenant-1", "pro", "active");
        assertThat(r.valid()).isTrue();
        assertThat(r.reason()).isNull();
        assertThat(r.apiKeyId()).isEqualTo("key-1");
        assertThat(r.userId()).isEqualTo("user-1");
        assertThat(r.tenantId()).isEqualTo("tenant-1");
        assertThat(r.plan()).isEqualTo("pro");
        assertThat(r.subscriptionStatus()).isEqualTo("active");
    }

    @Test
    void valid_withQuotaOwner_keepsIt() {
        ApiKeyVerifyResult r = ApiKeyVerifyResult.valid("key-1", "user-1", "team-1", "team", "active", "member", "owner-1");
        assertThat(r.quotaOwnerId()).isEqualTo("owner-1");
        assertThat(r.tenantId()).isEqualTo("team-1");
    }

    @Test
    void valid_withoutQuotaOwner_fallsBackToUserId() {
        assertThat(ApiKeyVerifyResult.valid("key-1", "user-1", "user-1", "pro", "active").quotaOwnerId()).isEqualTo("user-1");
        assertThat(ApiKeyVerifyResult.valid("key-1", "user-1", "user-1", "pro", "active", "owner", " ").quotaOwnerId()).isEqualTo("user-1");
    }

    @Test
    void 七参valid的businessRoles为空列表_八参保留顺序去null() {
        assertThat(ApiKeyVerifyResult.valid("k", "u", "t", "pro", "active", "MEMBER", "u").businessRoles()).isEmpty();
        var r = ApiKeyVerifyResult.valid("k", "u", "t", "pro", "active", "MEMBER", "u", Arrays.asList("DPO", null, "CISO"));
        assertThat(r.businessRoles()).containsExactly("DPO", "CISO");
        assertThatThrownBy(() -> r.businessRoles().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalid与八参null的businessRoles均为空列表() {
        assertThat(ApiKeyVerifyResult.invalid("revoked").businessRoles()).isEmpty();
        assertThat(ApiKeyVerifyResult.valid("k", "u", "t", "pro", "active", "MEMBER", "u", null).businessRoles()).isEmpty();
    }

    @Test
    void verify结果与快照的业务角色归一同源() {
        List<String> raw = Arrays.asList(" DPO ", null, " ", "CISO", "DPO");
        ApiKeyVerifyResult r = ApiKeyVerifyResult.valid("k", "u", "t", "pro", "active", "MEMBER", "u", raw);
        ApiKeySnapshot s = new ApiKeySnapshot(true, null, "k", "u", "t", "pro", "MEMBER", null, null, raw);
        assertThat(ApiKeySnapshot.normalizeRoles(raw)).containsExactly("DPO", "CISO", "DPO");
        assertThat(r.businessRoles()).isEqualTo(s.businessRoles()).isEqualTo(ApiKeySnapshot.normalizeRoles(raw));
    }
}
