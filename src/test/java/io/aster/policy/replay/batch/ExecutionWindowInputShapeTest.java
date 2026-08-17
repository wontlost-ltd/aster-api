package io.aster.policy.replay.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * 从 cloud 拉到的 execution input 必须转成**普通 Java 结构**再交给执行器。
 *
 * <h2>被修复的生产缺陷</h2>
 *
 * <p>原实现直接把 Vert.x {@code JsonObject} 塞进 {@code WindowedExecution.input}。
 * Truffle 把它当宿主对象包成 {@code HostObject}，而该 Context 没有配 HostAccess，
 * 于是策略里访问 {@code loan.creditScore} 直接报：
 *
 * <pre>
 * 无法访问成员：对象类型 com.oracle.truffle.host.HostObject 不支持成员访问，
 * 成员：creditScore
 * </pre>
 *
 * <p>生产实测：126 条重放 **100% 失败**，且被 {@code classify()} 的默认分支
 * 归成 {@code INPUT_INCOMPATIBLE}——UI 上显示成「你的历史输入与新版本不兼容」，
 * 把系统缺陷说成用户的数据问题。而同一份输入走 {@code /evaluate-source}
 * **执行成功**（那条路传的是普通 Map），这正是定位的关键对照。
 *
 * <h2>为什么用反射</h2>
 *
 * {@code toExecution} / {@code toPlainJava} 是私有静态方法。为测试放宽可见性
 * 会让「什么是对外契约」变模糊；本仓 {@code ReplayBatchWindowResolutionTest}
 * 已有同样的反射先例，沿用之。
 */
class ExecutionWindowInputShapeTest {

    private static Object toExecutionInput(JsonObject row) throws Exception {
        Method m = ExecutionWindowClient.class
            .getDeclaredMethod("toExecution", JsonObject.class);
        m.setAccessible(true);
        Object exec = m.invoke(null, row);
        return ((ExecutionWindowClient.WindowedExecution) exec).input();
    }

    private static JsonObject row(Object input) {
        return new JsonObject()
            .put("id", "e1")
            .put("input", input)
            .put("decision", "APPROVED")
            .put("success", true)
            .put("functionName", "evaluateLoan")
            .put("locale", "en-US");
    }

    @Test
    void input不得是JsonObject否则Truffle无法访问成员() throws Exception {
        Object input = toExecutionInput(row(new JsonObject().put("amount", 100)));

        assertThat(input)
            .as("★JsonObject 会被 Truffle 包成 HostObject，访问成员直接抛错")
            .isNotInstanceOf(JsonObject.class)
            .isInstanceOf(Map.class);
    }

    @Test
    void 嵌套结构必须递归转换() throws Exception {
        // 生产中的真实形态：{"loan": {"amount": 80000, "creditScore": 750}}
        // ★只转外层是不够的——策略访问的正是 loan.creditScore 这个内层成员。
        JsonObject nested = new JsonObject()
            .put("loan", new JsonObject().put("amount", 80000).put("creditScore", 750));

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) toExecutionInput(row(nested));

        assertThat(input.get("loan"))
            .as("★内层仍是 JsonObject 的话，loan.creditScore 照样访问不到")
            .isNotInstanceOf(JsonObject.class)
            .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> loan = (Map<String, Object>) input.get("loan");
        assertThat(loan.get("creditScore")).isEqualTo(750);
        assertThat(loan.get("amount")).isEqualTo(80000);
    }

    @Test
    void 数组同样要递归转换() throws Exception {
        JsonObject withArray = new JsonObject()
            .put("items", new JsonArray()
                .add(new JsonObject().put("sku", "A"))
                .add(new JsonObject().put("sku", "B")));

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) toExecutionInput(row(withArray));

        assertThat(input.get("items")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) input.get("items");
        assertThat(items.get(0))
            .as("★数组元素里的对象同样会被包成 HostObject")
            .isNotInstanceOf(JsonObject.class)
            .isInstanceOf(Map.class);
    }

    @Test
    void 标量与null原样保留() throws Exception {
        JsonObject scalars = new JsonObject()
            .put("n", 1).put("s", "x").put("b", true).putNull("nil");

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) toExecutionInput(row(scalars));

        assertThat(input.get("n")).isEqualTo(1);
        assertThat(input.get("s")).isEqualTo("x");
        assertThat(input.get("b")).isEqualTo(true);
        assertThat(input.get("nil")).isNull();
    }
}
