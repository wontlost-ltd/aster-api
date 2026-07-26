package io.aster.policy.i18n;

import io.aster.policy.i18n.UiMessagesService.MessagesEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UiMessagesService 单元测试（ADR 0018 Phase 2）。
 *
 * <p>不启动 Quarkus、不接 Redis —— 直接 new + 反射调用 classpath 加载与热刷新
 * 处理逻辑（同 LexiconAdminAllowlistTest 的轻量 idiom）。Redis pub/sub 集成由
 * @PostConstruct 的 Instance<RedisDataSource> isUnsatisfied 守卫，本测试不触达。
 *
 * <p>测试资源 src/test/resources/ui-messages/en-US.json 提供 classpath 加载目标
 * （生产中由各语言包 jar 的 ui-messages/ 提供）。
 */
@DisplayName("UiMessagesService 界面文案内存仓")
class UiMessagesServiceTest {

    private MessagesEntry loadViaReflection(UiMessagesService svc, String locale) throws Exception {
        Method m = UiMessagesService.class.getDeclaredMethod("loadFromClasspath", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<MessagesEntry> result = (Optional<MessagesEntry>) m.invoke(svc, locale);
        return result.orElse(null);
    }

    @Test
    @DisplayName("从 classpath 加载 ui-messages 资源并算 sha256")
    void loadFromClasspath() throws Exception {
        UiMessagesService svc = new UiMessagesService();
        MessagesEntry entry = loadViaReflection(svc, "en-US");

        assertThat(entry).isNotNull();
        assertThat(entry.json()).contains("\"save\"").contains("Save");
        // sha256 是 64 位 hex
        assertThat(entry.sha256()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("classpath 无该 locale 资源 → empty（优雅降级，不抛）")
    void loadMissingLocaleReturnsEmpty() throws Exception {
        UiMessagesService svc = new UiMessagesService();
        MessagesEntry entry = loadViaReflection(svc, "xx-XX");
        assertThat(entry).isNull();
    }

    @Test
    @DisplayName("路径穿越 locale → empty（拒绝越权读任意文件，java/path-injection）")
    void loadRejectsPathTraversalLocale() throws Exception {
        UiMessagesService svc = new UiMessagesService();
        // 各种穿越 payload 都必须被白名单拦下 → empty（不触达 classpath 资源）。
        assertThat(loadViaReflection(svc, "../../../etc/passwd")).isNull();
        assertThat(loadViaReflection(svc, "../en-US")).isNull();
        assertThat(loadViaReflection(svc, "en-US/../../secret")).isNull();
        assertThat(loadViaReflection(svc, "en.US")).isNull();      // 点号非法
        assertThat(loadViaReflection(svc, "en/US")).isNull();      // 斜杠非法
        assertThat(loadViaReflection(svc, "en\\US")).isNull();     // 反斜杠非法
        assertThat(loadViaReflection(svc, "")).isNull();
    }

    @Test
    @DisplayName("合法 locale（en-US）不受安全校验影响，仍正常加载")
    void loadValidLocaleStillWorks() throws Exception {
        UiMessagesService svc = new UiMessagesService();
        assertThat(loadViaReflection(svc, "en-US")).isNotNull();
    }

    @Test
    @DisplayName("get(null/空) → empty")
    void getNullOrBlank() {
        UiMessagesService svc = new UiMessagesService();
        assertThat(svc.get(null)).isEmpty();
        assertThat(svc.get("")).isEmpty();
        assertThat(svc.get("   ")).isEmpty();
    }

    @Test
    @DisplayName("handleReload 从 classpath 重新加载并填入内存仓")
    void handleReloadPopulatesStore() {
        UiMessagesService svc = new UiMessagesService();
        assertThat(svc.get("en-US")).isEmpty(); // 初始空

        svc.handleReload("{\"locale\":\"en-US\"}");

        assertThat(svc.get("en-US")).isPresent();
        assertThat(svc.get("en-US").get().json()).contains("Save");
        assertThat(svc.loadedLocales()).contains("en-US");
    }

    @Test
    @DisplayName("handleReload 同 locale 内容不变 → sha 稳定（幂等）")
    void handleReloadIdempotentSha() {
        UiMessagesService svc = new UiMessagesService();
        svc.handleReload("{\"locale\":\"en-US\"}");
        String sha1 = svc.get("en-US").get().sha256();
        svc.handleReload("{\"locale\":\"en-US\"}");
        String sha2 = svc.get("en-US").get().sha256();
        assertThat(sha1).isEqualTo(sha2);
    }

    @Test
    @DisplayName("handleReload 缺 locale 字段 → 忽略，不污染内存仓")
    void handleReloadMissingLocale() {
        UiMessagesService svc = new UiMessagesService();
        svc.handleReload("{\"version\":\"abc\"}");
        assertThat(svc.loadedLocales()).isEmpty();
    }

    @Test
    @DisplayName("handleReload classpath 无资源 → 不填入（不创建空条目）")
    void handleReloadUnknownLocaleNoOp() {
        UiMessagesService svc = new UiMessagesService();
        svc.handleReload("{\"locale\":\"xx-XX\"}");
        assertThat(svc.get("xx-XX")).isEmpty();
    }

    @Test
    @DisplayName("handleReload null/空 payload → 安全忽略")
    void handleReloadNullPayload() {
        UiMessagesService svc = new UiMessagesService();
        svc.handleReload(null);
        svc.handleReload("");
        svc.handleReload("not-json{{{");
        assertThat(svc.loadedLocales()).isEmpty();
    }

    // ──────────────────────────────────────── ADR 0021 运行时覆盖层

    @Test
    @DisplayName("无 Redis：resolveEntry 回退 classpath（handleReload 仍从 classpath 加载）")
    void noRedisFallsBackToClasspath() {
        // 无 Redis → loadFromRedis MISS → resolveEntry 回退 classpath。
        UiMessagesService svc = new UiMessagesService();
        svc.handleReload("{\"locale\":\"en-US\"}");
        assertThat(svc.get("en-US")).isPresent();
    }

    @Test
    @DisplayName("baselineJson 读 classpath 基线（en-US 存在，xx-XX 不存在）")
    void baselineJsonReadsClasspath() {
        UiMessagesService svc = new UiMessagesService();
        assertThat(svc.baselineJson("en-US")).isPresent();
        assertThat(svc.baselineJson("xx-XX")).isEmpty();
        assertThat(svc.baselineJson(null)).isEmpty();
        assertThat(svc.baselineJson("  ")).isEmpty();
    }

    @Test
    @DisplayName("无 Redis：writeOverride → empty（写入端点据此返回 503，不假装成功）")
    void writeOverrideNoRedisReturnsEmpty() {
        UiMessagesService svc = new UiMessagesService();
        assertThat(svc.writeOverride("en-US", "{\"common\":{}}")).isEmpty();
        assertThat(svc.writeOverride(null, "{}")).isEmpty();
        assertThat(svc.writeOverride("en-US", null)).isEmpty();
    }

    @Test
    @DisplayName("无 Redis：deleteOverride → removed=false（不可用时不假装删除成功）")
    void deleteOverrideNoRedisReturnsFalse() {
        UiMessagesService svc = new UiMessagesService();
        UiMessagesService.DeleteResult r = svc.deleteOverride("en-US");
        assertThat(r.removed()).isFalse();
        assertThat(r.propagated()).isFalse();
    }
}
