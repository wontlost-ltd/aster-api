package io.aster.policy.api.convert;

import aster.core.ir.CoreModel;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命名参数上下文映射器
 *
 * 将命名参数格式的上下文转换为位置参数数组，供 CNL 运行时使用。
 *
 * 支持两种上下文格式：
 * 1. 命名格式（推荐）：Map<参数名, 参数值>
 *    示例：{ "申请": { "编号": "A001", "金额": 50000 }, "年龄": 25 }
 *
 * 2. 位置格式（兼容）：Object[]
 *    示例：[{ "编号": "A001", "金额": 50000 }, 25]
 *
 * 命名格式的优势：
 * - 用户无需记住参数顺序
 * - 支持动态生成表单（基于参数名和类型）
 * - 更易读、易维护
 */
public class NamedContextMapper {

    private static final Logger LOG = Logger.getLogger(NamedContextMapper.class);

    /**
     * 映射结果
     */
    public record MappingResult(
        /** 位置参数数组（供运行时使用） */
        Object[] positionalArgs,
        /** 是否使用了命名格式 */
        boolean wasNamedFormat,
        /** 警告信息（如未知参数名） */
        List<String> warnings,
        /** 错误信息（如缺少必需参数） */
        String error
    ) {
        public static MappingResult success(Object[] args, boolean named, List<String> warnings) {
            return new MappingResult(args, named, warnings, null);
        }

        public static MappingResult error(String errorMessage) {
            return new MappingResult(new Object[0], false, List.of(), errorMessage);
        }

        public boolean hasError() {
            return error != null && !error.isEmpty();
        }

        public boolean success() {
            return !hasError();
        }
    }

    /**
     * 将上下文映射为位置参数数组
     *
     * @param context 上下文（Map 表示命名格式，List/Array 表示位置格式）
     * @param params  函数参数定义列表
     * @return 映射结果
     */
    public static MappingResult mapContext(Object context, List<CoreModel.Param> params) {
        if (context == null) {
            // 空上下文 -> 空数组
            return MappingResult.success(new Object[0], false, List.of());
        }

        if (params == null || params.isEmpty()) {
            // 无参数定义，返回原样包装
            if (context instanceof List<?> list) {
                return MappingResult.success(list.toArray(), false, List.of());
            }
            if (context.getClass().isArray()) {
                return MappingResult.success((Object[]) context, false, List.of());
            }
            // 单一值包装为数组
            return MappingResult.success(new Object[] { context }, false, List.of());
        }

        // 检测上下文格式
        if (context instanceof Map<?, ?> map) {
            return tryNamedMapping(map, params);
        }

        if (context instanceof List<?> list) {
            // 位置格式
            return MappingResult.success(list.toArray(), false, List.of());
        }

        if (context.getClass().isArray()) {
            // 位置格式
            return MappingResult.success((Object[]) context, false, List.of());
        }

        // 单一值，按单参数处理
        return MappingResult.success(new Object[] { context }, false, List.of());
    }

    /**
     * 尝试命名映射
     *
     * 检测 Map 的键是否与参数名匹配，如果匹配则按名称提取参数值。
     */
    private static MappingResult tryNamedMapping(Map<?, ?> map, List<CoreModel.Param> params) {
        // 收集参数名
        List<String> paramNames = params.stream()
            .map(p -> p.name)
            .toList();

        // 构建上下文键到参数名的模糊匹配映射
        // 处理规范化差异（如 request → reqüst），支持原始键直接匹配和规范化后匹配
        java.util.Map<String, String> keyToParamMapping = new java.util.HashMap<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String keyStr)) continue;
            // 精确匹配
            if (paramNames.contains(keyStr)) {
                keyToParamMapping.put(keyStr, keyStr);
                continue;
            }
            // 模糊匹配：上下文键可能是规范化前的形式（如 "request"），
            // 而参数名是规范化后的形式（如 "reqüst"）
            for (String paramName : paramNames) {
                if (fuzzyMatch(keyStr, paramName)) {
                    keyToParamMapping.put(keyStr, paramName);
                    LOG.debugf("模糊匹配: 上下文键 '%s' → 参数名 '%s'", keyStr, paramName);
                    break;
                }
            }
        }

        boolean isNamedFormat = !keyToParamMapping.isEmpty();

        if (!isNamedFormat) {
            // ★单参数规则：把整个 Map 当作那一个参数（合法且常用——
            //   `given applicant` 配 {id:…, age:…} 就是这条路径）。
            if (params.size() == 1) {
                LOG.debugf("上下文键与参数名不匹配，按单参数 Map 处理。参数: %s", paramNames.get(0));
                return MappingResult.success(new Object[] { map }, false, List.of());
            }

            // ★多参数规则：键一个都没对上，说明调用方给错了输入形状。
            //   此前这里也返回 `new Object[]{map}`——把 1 个实参喂给 N 参函数，
            //   于是错误延迟到引擎里炸成
            //   `Lambda func_X expects at least 2 arguments` 或
            //   `HostObject 不支持成员访问，成员：age`。
            //   两者都指向引擎/HostAccess 配置，而真实原因是**键名对不上**，
            //   排查方向完全被带偏（实测：非英文模板全军覆没即由此而来——
            //   defaultInput 的键是英文，而非英文规则的参数名是本地化的）。
            //   改为在映射层就报出可直接行动的信息。
            String msg = "上下文键与参数名不匹配。上下文键: " + map.keySet()
                + "，期望参数名: " + paramNames;
            LOG.errorf(msg);
            return MappingResult.error(msg);
        }

        // 命名格式：按参数顺序提取值
        LOG.debugf("检测到命名格式上下文，参数名: %s", paramNames);

        // 构建反向映射：参数名 → 上下文键
        java.util.Map<String, String> paramToKeyMapping = new java.util.HashMap<>();
        for (var entry : keyToParamMapping.entrySet()) {
            paramToKeyMapping.put(entry.getValue(), entry.getKey());
        }

        Object[] positionalArgs = new Object[params.size()];
        List<String> warnings = new ArrayList<>();
        List<String> missingParams = new ArrayList<>();

        for (int i = 0; i < params.size(); i++) {
            CoreModel.Param param = params.get(i);
            String paramName = param.name;
            String contextKey = paramToKeyMapping.get(paramName);

            if (contextKey != null && map.containsKey(contextKey)) {
                positionalArgs[i] = map.get(contextKey);
                // ★不打印参数**值**：context 是客户的明文业务输入（金额、评分、
                //   身份字段…）。DEBUG 打开时重跑一批执行就会把成百上千条金融
                //   数据写进日志，绕过一切 PII 保留策略。只记键名与是否有值——
                //   排查「参数没映射上」需要的是这两个信息，不是值本身。
                LOG.debugf("参数 '%s' (位置 %d) 已映射, 空值=%b",
                    paramName, i, positionalArgs[i] == null);
            } else {
                // 缺少参数
                missingParams.add(paramName);
                positionalArgs[i] = null;
                LOG.warnf("缺少参数: '%s' (位置 %d)", paramName, i);
            }
        }

        // 检查未知参数
        for (Object key : map.keySet()) {
            if (key instanceof String keyStr && !keyToParamMapping.containsKey(keyStr)) {
                warnings.add("未知参数: '" + keyStr + "'");
                LOG.warnf("未知参数: '%s'", keyStr);
            }
        }

        // 如果缺少必需参数，返回错误
        if (!missingParams.isEmpty()) {
            String errorMsg = "缺少必需参数: " + String.join(", ", missingParams);
            LOG.errorf(errorMsg);
            return MappingResult.error(errorMsg);
        }

        return MappingResult.success(positionalArgs, true, warnings);
    }

    /**
     * 模糊匹配上下文键和参数名
     *
     * 处理规范化差异（如德语 umlaut 替换: request → reqüst）。
     * 通过比较 ASCII 基础形式来判断是否为同一标识符。
     */
    private static boolean fuzzyMatch(String contextKey, String paramName) {
        // 将 umlaut 字符还原为 ASCII 等价形式后比较
        String normalizedParam = normalizeUmlauts(paramName);
        String normalizedKey = normalizeUmlauts(contextKey);
        return normalizedKey.equalsIgnoreCase(normalizedParam);
    }

    /**
     * 将 umlaut 字符还原为 ASCII 等价形式
     */
    private static String normalizeUmlauts(String s) {
        return s.replace("ü", "ue")
                .replace("ö", "oe")
                .replace("ä", "ae")
                .replace("ß", "ss")
                .replace("Ü", "Ue")
                .replace("Ö", "Oe")
                .replace("Ä", "Ae");
    }

    /**
     * 提取函数参数信息
     *
     * @param module       Core IR 模块
     * @param functionName 函数名
     * @return 参数列表，如果函数不存在返回空列表
     */
    public static List<CoreModel.Param> extractFunctionParams(CoreModel.Module module, String functionName) {
        if (module == null || module.decls == null) {
            return List.of();
        }

        for (CoreModel.Decl decl : module.decls) {
            if (decl instanceof CoreModel.Func func && func.name.equals(functionName)) {
                return func.params != null ? func.params : List.of();
            }
        }

        return List.of();
    }

    /**
     * 查找模块中的第一个函数
     *
     * @param module Core IR 模块
     * @return 第一个函数声明，如果没有返回 null
     */
    public static CoreModel.Func findFirstFunction(CoreModel.Module module) {
        if (module == null || module.decls == null) {
            return null;
        }

        for (CoreModel.Decl decl : module.decls) {
            if (decl instanceof CoreModel.Func func) {
                return func;
            }
        }

        return null;
    }
}
