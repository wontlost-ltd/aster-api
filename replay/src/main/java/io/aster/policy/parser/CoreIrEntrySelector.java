package io.aster.policy.parser;

import aster.truffle.runtime.AsterConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 让 Truffle 执行**指定**的函数：把目标函数的 Func 声明挪到 Core IR 中所有 Func 之前。
 *
 * <p>为什么需要：Truffle 的 {@code AsterLanguage.parse} 不接收入口函数名，其 Loader 固定按
 * {@code AsterConfig.DEFAULT_FUNCTION}（默认 "main"）→ "main" → 第一个函数 的顺序选入口。
 * 调用方（evaluate-source 与 /evaluate 两条路径）却按目标函数映射参数、并把目标名记入执行结果，
 * 于是多 Rule 策略请求第二个函数时会静默执行第一个函数，执行记录还声称执行的是目标。
 *
 * <p>约束：
 * <ul>
 *   <li>目标为空、目标已是第一个 Func、或 IR 中没有该函数时，原样返回输入字符串
 *       （单 Rule 策略字节不变，编译/Source 缓存键不受影响；「函数不存在」由调用方既有校验报告）。</li>
 *   <li>模块里存在会被 Loader 按名优先选中的函数（DEFAULT_FUNCTION 或 "main"）且它不是目标时，
 *       挪动顺序无济于事——直接抛 {@link IllegalArgumentException}，宁可响亮失败也不执行错函数。</li>
 *   <li>重排时只移动目标的 Func 声明（同名重载一起移动），其余声明相对顺序不变；
 *       数字按原精度保留（BigDecimal 精确模式），避免 Decimal 字面量在重序列化时变形。</li>
 * </ul>
 */
public final class CoreIrEntrySelector {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private CoreIrEntrySelector() {
    }

    /**
     * 返回一份「入口即目标函数」的 Core IR JSON。
     *
     * @param coreJson     Core IR JSON（模块级，含 decls）
     * @param functionName 目标函数名；null 或空表示沿用 Loader 的默认选择
     * @return 目标函数位于所有 Func 之前的 JSON；无需调整时返回原字符串本身
     * @throws IllegalArgumentException 模块中按名优先的函数会抢占入口时
     */
    public static String withEntry(String coreJson, String functionName) {
        if (coreJson == null || functionName == null || functionName.isEmpty()) {
            return coreJson;
        }
        JsonNode root = parse(coreJson);
        JsonNode decls = root.get("decls");
        if (!(decls instanceof ArrayNode declArray)) {
            return coreJson;
        }
        List<String> funcNames = funcNamesInOrder(declArray);
        if (!funcNames.contains(functionName)) {
            return coreJson;
        }
        rejectShadowingEntry(funcNames, functionName);
        if (funcNames.get(0).equals(functionName)) {
            return coreJson;
        }
        ((ObjectNode) root).set("decls", reorder(declArray, functionName));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Core IR 重新序列化失败", e);
        }
    }

    private static JsonNode parse(String coreJson) {
        try {
            return MAPPER.readTree(coreJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Core IR JSON 无法解析", e);
        }
    }

    private static List<String> funcNamesInOrder(ArrayNode decls) {
        List<String> names = new ArrayList<>();
        for (JsonNode decl : decls) {
            if (isFunc(decl)) {
                names.add(decl.path("name").asText());
            }
        }
        return names;
    }

    /** Loader 会先按这些名字选入口，任何一个存在且不是目标，重排都改变不了结果。 */
    private static void rejectShadowingEntry(List<String> funcNames, String target) {
        Set<String> preferred = new LinkedHashSet<>(List.of(AsterConfig.DEFAULT_FUNCTION, "main"));
        for (String name : preferred) {
            if (!name.equals(target) && funcNames.contains(name)) {
                throw new IllegalArgumentException(
                    "无法执行函数 '" + target + "'：模块中定义了函数 '" + name
                        + "'，执行引擎会优先选它作为入口。请重命名 '" + name + "'，或直接执行 '" + name + "'。");
            }
        }
    }

    /**
     * 目标 Func 们插到第一个 Func 的位置，其余声明相对顺序不变。
     * 调用前已保证目标不是第一个 Func，故第一个 Func 一定在 others 中，插入点必然存在。
     */
    private static ArrayNode reorder(ArrayNode decls, String target) {
        List<JsonNode> targets = new ArrayList<>();
        List<JsonNode> others = new ArrayList<>();
        int firstFuncIndex = -1;
        for (JsonNode decl : decls) {
            boolean func = isFunc(decl);
            if (func && firstFuncIndex < 0) {
                firstFuncIndex = others.size();
            }
            if (func && target.equals(decl.path("name").asText())) {
                targets.add(decl);
            } else {
                others.add(decl);
            }
        }
        ArrayNode result = MAPPER.createArrayNode();
        for (int i = 0; i < others.size(); i++) {
            if (i == firstFuncIndex) {
                targets.forEach(result::add);
            }
            result.add(others.get(i));
        }
        return result;
    }

    private static boolean isFunc(JsonNode decl) {
        return "Func".equals(decl.path("kind").asText());
    }
}
