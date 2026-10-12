package io.aster.policy.parser;

import aster.truffle.runtime.AsterConfig;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 让 Truffle 执行**指定**的函数：把目标函数的 Func 声明挪到 Core IR 中所有 Func 之前。
 *
 * <p>为什么需要：Truffle 的 {@code AsterLanguage.parse} 不接收入口函数名，其 Loader 固定按
 * {@code AsterConfig.DEFAULT_FUNCTION}（默认 "main"）→ "main" → 第一个函数 的顺序选入口。
 * 调用方却按目标函数映射参数、并把目标名记入执行结果，于是多 Rule 策略请求第二个函数时
 * 会静默执行第一个函数，执行记录还声称执行的是目标。
 *
 * <p>约束：
 * <ul>
 *   <li>目标为空、或目标已是第一个 Func 时，原样返回输入字符串（单 Rule 策略字节不变，
 *       编译/Source 缓存键不受影响）。</li>
 *   <li>目标不在 IR 中时抛 {@link EntryFunctionException}。不能交给调用方：/evaluate 路径
 *       （PolicyCompiler → executeCompiled）没有函数存在性校验，原样返回会静默执行第一个函数。</li>
 *   <li>模块里存在会被 Loader 按名优先选中的函数（DEFAULT_FUNCTION 或 "main"）且它不是目标时，
 *       重排无效——抛 {@link EntryFunctionException}，宁可响亮失败也不执行错函数。
 *       跨模块 link 后被导入模块的函数带 mangle 前缀，不会与这些名字相等。</li>
 *   <li>重排按原文切片移动声明，**不重新序列化**：每个字面量逐字节保留（-0.0、指数写法、
 *         转义等重序列化会改变的形式都不受影响），只有 decls 中元素的先后顺序变化。</li>
 * </ul>
 */
public final class CoreIrEntrySelector {

    private static final JsonFactory FACTORY = new JsonFactory();

    private CoreIrEntrySelector() {
    }

    /** decls 数组中一个元素在原文中的区间 [start, end)，以及它的 kind/name。 */
    private record DeclSpan(int start, int end, String kind, String name) {
        boolean isFunc() {
            return "Func".equals(kind);
        }
    }

    /**
     * 返回一份「入口即目标函数」的 Core IR JSON。
     *
     * @param coreJson     Core IR JSON（模块级，含 decls）
     * @param functionName 目标函数名；null 或空表示沿用 Loader 的默认选择
     * @return 目标函数位于所有 Func 之前的 JSON；无需调整时返回原字符串本身
     * @throws EntryFunctionException 目标不存在，或模块中按名优先的函数会抢占入口
     * @throws IllegalArgumentException Core IR JSON 无法解析
     */
    public static String withEntry(String coreJson, String functionName) {
        if (coreJson == null || functionName == null || functionName.isEmpty()) {
            return coreJson;
        }
        List<DeclSpan> decls = scanDecls(coreJson);
        List<String> funcNames = decls.stream().filter(DeclSpan::isFunc).map(DeclSpan::name).toList();
        if (!funcNames.contains(functionName)) {
            throw EntryFunctionException.missing(functionName, funcNames);
        }
        rejectShadowingEntry(funcNames, functionName);
        if (funcNames.get(0).equals(functionName)) {
            return coreJson;
        }
        return splice(coreJson, decls, reorder(decls, functionName));
    }

    /** Loader 会先按这些名字选入口，任何一个存在且不是目标，重排都改变不了结果。 */
    private static void rejectShadowingEntry(List<String> funcNames, String target) {
        Set<String> preferred = new LinkedHashSet<>(List.of(AsterConfig.DEFAULT_FUNCTION, "main"));
        for (String name : preferred) {
            if (!name.equals(target) && funcNames.contains(name)) {
                throw EntryFunctionException.shadowed(target, name);
            }
        }
    }

    /** 目标 Func 们插到第一个 Func 的位置（同名重载一起移动），其余声明相对顺序不变。 */
    private static List<DeclSpan> reorder(List<DeclSpan> decls, String target) {
        List<DeclSpan> targets = decls.stream().filter(d -> d.isFunc() && target.equals(d.name())).toList();
        List<DeclSpan> result = new ArrayList<>(decls.size());
        boolean inserted = false;
        for (DeclSpan decl : decls) {
            if (decl.isFunc() && !inserted) {
                result.addAll(targets);
                inserted = true;
            }
            if (!targets.contains(decl)) {
                result.add(decl);
            }
        }
        return result;
    }

    /**
     * 按新顺序拼回原文：第 i 个元素槽位换成新顺序的第 i 个元素原文，元素之间的分隔符
     * （逗号与空白）与数组外的全部内容保持原样。
     */
    private static String splice(String json, List<DeclSpan> original, List<DeclSpan> ordered) {
        StringBuilder out = new StringBuilder(json.length());
        out.append(json, 0, original.get(0).start());
        for (int i = 0; i < original.size(); i++) {
            DeclSpan decl = ordered.get(i);
            out.append(json, decl.start(), decl.end());
            int separatorEnd = i + 1 < original.size() ? original.get(i + 1).start() : json.length();
            out.append(json, original.get(i).end(), separatorEnd);
        }
        return out.toString();
    }

    /**
     * 扫描顶层 "decls" 数组，记录每个元素的原文区间与 kind/name；不构建整棵树。
     *
     * <p>必须走完整个顶层对象：若有重复的顶层 decls 键，本类只会重排第一个，而 Truffle 的
     * Jackson 反序列化保留最后一个——结果是静默执行错函数。故重复时直接拒绝。
     */
    private static List<DeclSpan> scanDecls(String json) {
        try (JsonParser parser = FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Core IR JSON 顶层不是对象");
            }
            List<DeclSpan> decls = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (!"decls".equals(field)) {
                    parser.skipChildren();
                } else if (decls != null) {
                    throw new IllegalArgumentException("Core IR JSON 含重复的顶层 decls 键");
                } else if (value == JsonToken.START_ARRAY) {
                    decls = readDeclArray(parser);
                } else {
                    parser.skipChildren();
                    decls = List.of();
                }
            }
            return decls == null ? List.of() : decls;
        } catch (IOException e) {
            throw new IllegalArgumentException("Core IR JSON 无法解析", e);
        }
    }

    private static List<DeclSpan> readDeclArray(JsonParser parser) throws IOException {
        List<DeclSpan> decls = new ArrayList<>();
        for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
            int start = (int) parser.currentTokenLocation().getCharOffset();
            String kind = null;
            String name = null;
            if (token == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    if (value == JsonToken.VALUE_STRING && "kind".equals(field)) {
                        kind = parser.getText();
                    } else if (value == JsonToken.VALUE_STRING && "name".equals(field)) {
                        name = parser.getText();
                    } else {
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();
            }
            int end = (int) parser.currentLocation().getCharOffset();
            decls.add(new DeclSpan(start, end, kind, name));
        }
        return decls;
    }
}
