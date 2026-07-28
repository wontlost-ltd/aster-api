package io.aster.policy.rest;

import io.aster.policy.security.rbac.RequireRole;
import io.aster.policy.security.rbac.Role;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PolicyVersionResource} 从 {@code PolicyEvaluationResource} 抽出时的**契约不变量**
 * （issue #174）。
 *
 * <p>拆类最大的风险不是逻辑写错，而是**注解漏抄**——路由、鉴权、线程模型都由注解承载，
 * 漏一个不会有编译错误，却会静默改变生产行为：
 * <ul>
 *   <li>类级 {@code @Path} 变了 → 路由 404（集成方 breaking）</li>
 *   <li>类级 {@code @RequireRole(MEMBER)} 漏了 → 版本历史对任意认证用户开放</li>
 *   <li>方法级 ADMIN 漏了 → 回滚（生产突变操作）降级到 MEMBER 就能执行</li>
 *   <li>{@code @Blocking} 漏了 → 阻塞调用跑在 event-loop 上</li>
 * </ul>
 *
 * <p>这些用例把上述四点钉死。端点路径本身另有
 * {@code PublicApiContractTest} 的 golden 守着（本次搬运后 golden 零变化，
 * 证明路由未受影响）。
 */
@DisplayName("PolicyVersionResource 抽取契约")
class PolicyVersionResourceContractTest {

    @Test
    @DisplayName("类级 @Path 与原类一致（/api/v1/policies）")
    void classPathMatchesOriginal() {
        Path p = PolicyVersionResource.class.getAnnotation(Path.class);
        assertNotNull(p, "缺类级 @Path 会让两个端点整体 404");
        assertEquals("/api/v1/policies", p.value());
        assertEquals(
            PolicyEvaluationResource.class.getAnnotation(Path.class).value(),
            p.value(),
            "必须与原类同路径，否则搬运即 breaking change");
    }

    @Test
    @DisplayName("★类级 @RequireRole(MEMBER) 不得丢失——它是 getVersionHistory 的门槛")
    void classLevelRoleIsMember() {
        RequireRole r = PolicyVersionResource.class.getAnnotation(RequireRole.class);
        assertNotNull(r, "漏掉类级 @RequireRole 会让版本历史对任意认证用户开放");
        assertEquals(Role.MEMBER, r.value());
    }

    @Test
    @DisplayName("★rollback 保留方法级 ADMIN 提权（生产突变操作）")
    void rollbackRequiresAdmin() throws Exception {
        Method m = PolicyVersionResource.class.getDeclaredMethod(
            "rollback", String.class, io.aster.policy.rest.model.RollbackRequest.class);
        RequireRole r = m.getAnnotation(RequireRole.class);
        assertNotNull(r, "rollback 必须有方法级 @RequireRole；红队 P1-E 要求提权到 ADMIN");
        assertEquals(Role.ADMIN, r.value(),
            "回滚会激活旧版本、改变线上决策行为，MEMBER 权限不足");
    }

    @Test
    @DisplayName("两个端点都保留 @Blocking（阻塞 DB 调用不得跑在 event-loop 上）")
    void bothEndpointsAreBlocking() throws Exception {
        for (Method m : new Method[]{
            PolicyVersionResource.class.getDeclaredMethod(
                "rollback", String.class, io.aster.policy.rest.model.RollbackRequest.class),
            PolicyVersionResource.class.getDeclaredMethod("getVersionHistory", String.class),
        }) {
            assertTrue(
                m.isAnnotationPresent(io.smallrye.common.annotation.Blocking.class),
                m.getName() + " 必须保留 @Blocking——versionService 是阻塞 DB 调用");
        }
    }

    @Test
    @DisplayName("原类不再持有这两个端点（避免路由重复注册）")
    void originalNoLongerDeclaresThem() {
        for (Method m : PolicyEvaluationResource.class.getDeclaredMethods()) {
            assertTrue(
                !m.getName().equals("rollback") && !m.getName().equals("getVersionHistory"),
                "PolicyEvaluationResource 仍声明 " + m.getName() + "，会与新 resource 冲突");
        }
    }
}
