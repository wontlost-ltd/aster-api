package io.aster.policy.runtime;

import aster.core.ir.CoreModel;
import io.aster.policy.parser.DynamicCnlExecutor.DynamicExecutionException;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「规则读取的输入字段不存在」的运行时失败归因为 {@link MissingInputFieldException}，并给出字段路径。
 *
 * <p>为什么在这里做：Truffle 运行时（aster-lang-truffle {@code MemberAccessNode}）读不到键时只抛
 * {@code RuntimeException("Map 中不存在键：<成员名>")}，既没有错误码，也只有最后一段成员名。
 * 执行器手里有调用方输入、目标函数的参数表与模块内的类型声明，足以把成员名还原为完整路径。
 *
 * <p>判定刻意保守：只有在调用方输入里<b>确实找到缺这个键的对象</b>时才改判，找不到就保留原失败
 * （可能是规则内部构造的值缺键，那不是输入问题）。查找分两轮：先只看参数类型声明了该字段的结构体
 * （有类型的策略，结果精确）；没有命中再看任意缺这个键的映射（参数未声明类型的旧策略）。
 * 同一轮里有多个候选时取遍历顺序（参数顺序、字段声明顺序）中的第一个。
 */
public final class MissingInputFields {

    private static final Logger LOG = Logger.getLogger(MissingInputFields.class);

    /**
     * Truffle 读不到键时的两种文案（aster-lang-truffle MemberAccessNode）：guest 原生映射与宿主 hash 对象。
     * 成员名之后可能紧跟全角句号或说明文字，故成员名只取到空白 / 中文标点为止。
     */
    private static final List<Pattern> MISSING_MEMBER = List.of(
        Pattern.compile("Map 中不存在键：([^\\s，。：]+)"),
        Pattern.compile("无法访问成员：([^\\s，。：]+)。该对象的可用成员为"));

    /** cause 链遍历上限，防御自引用或异常深嵌套。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    private MissingInputFields() {
    }

    /**
     * 执行失败若是「规则读取的输入字段不存在」，换成 {@link MissingInputFieldException}；否则原样返回。
     *
     * @param failure       执行失败
     * @param module        目标函数所在的 Core IR 模块（取类型声明）
     * @param params        目标函数的参数表
     * @param args          实际传给目标函数的位置参数（与 params 一一对应）
     * @param paramPrefixed 字段路径是否以参数名开头：调用方按参数名传入时为 true；
     *                      单参数规则直接传入整个对象（不带参数名这一层）时为 false
     */
    public static DynamicExecutionException translate(DynamicExecutionException failure, CoreModel.Module module,
                                                      List<CoreModel.Param> params, Object[] args,
                                                      boolean paramPrefixed) {
        if (failure instanceof MissingInputFieldException
                || failure.kind() != DynamicExecutionException.Kind.EXECUTION
                || params == null || args == null) {
            return failure;
        }
        Optional<String> path = missingMember(failure)
            .flatMap(member -> locate(member, module, params, args, paramPrefixed));
        if (path.isEmpty()) {
            return failure;
        }
        LOG.debugf("执行失败归因为输入缺字段: path=%s", path.get());
        return MissingInputFieldException.of(path.get());
    }

    /** 从失败的 cause 链里取出运行时报告的缺失成员名。 */
    static Optional<String> missingMember(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String message = current.getMessage();
            if (message != null) {
                for (Pattern pattern : MISSING_MEMBER) {
                    Matcher m = pattern.matcher(message);
                    if (m.find()) {
                        return Optional.of(m.group(1));
                    }
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return Optional.empty();
    }

    /** 在调用方输入中找缺 member 键的对象，返回其字段路径。 */
    static Optional<String> locate(String member, CoreModel.Module module, List<CoreModel.Param> params,
                                   Object[] args, boolean paramPrefixed) {
        Map<String, CoreModel.Data> types = dataDecls(module);
        for (boolean typedOnly : new boolean[] {true, false}) {
            Search search = new Search(member, types, typedOnly);
            for (int i = 0; i < params.size() && i < args.length; i++) {
                CoreModel.Param param = params.get(i);
                String root = paramPrefixed ? param.name : "";
                Optional<String> found = search.find(args[i], param.type, root, 0);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, CoreModel.Data> dataDecls(CoreModel.Module module) {
        Map<String, CoreModel.Data> out = new HashMap<>();
        if (module == null || module.decls == null) {
            return out;
        }
        for (CoreModel.Decl decl : module.decls) {
            if (decl instanceof CoreModel.Data data && data.name != null) {
                out.put(data.name, data);
            }
        }
        return out;
    }

    /** 一轮查找：typedOnly=true 时只认类型声明了 member 字段的结构体。 */
    private record Search(String member, Map<String, CoreModel.Data> types, boolean typedOnly) {

        Optional<String> find(Object value, CoreModel.Type type, String path, int depth) {
            if (depth > PolyglotValues.MAX_DEPTH) {
                return Optional.empty();
            }
            if (value instanceof Map<?, ?> map) {
                return findInMap(map, dataOf(type), path, depth);
            }
            if (value instanceof List<?> list) {
                return findInList(list, elementType(type), path, depth);
            }
            if (value instanceof Object[] array) {
                return findInList(List.of(array), elementType(type), path, depth);
            }
            return Optional.empty();
        }

        private Optional<String> findInMap(Map<?, ?> map, CoreModel.Data data, String path, int depth) {
            boolean declared = data != null && fieldOf(data, member) != null;
            if ((declared || !typedOnly) && !hasKey(map, member)) {
                return Optional.of(join(path, member));
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                CoreModel.Field field = data == null ? null : fieldOf(data, key);
                Optional<String> found = find(entry.getValue(), field == null ? null : field.type,
                    join(path, key), depth + 1);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }

        private Optional<String> findInList(List<?> list, CoreModel.Type elementType, String path, int depth) {
            for (int i = 0; i < list.size(); i++) {
                Optional<String> found = find(list.get(i), elementType, path + "[" + i + "]", depth + 1);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }

        private CoreModel.Data dataOf(CoreModel.Type type) {
            CoreModel.Type t = unwrap(type);
            if (!(t instanceof CoreModel.TypeName named) || named.name == null) {
                return null;
            }
            CoreModel.Data data = types.get(named.name);
            if (data == null && named.name.contains(".")) {
                data = types.get(named.name.substring(named.name.lastIndexOf('.') + 1));
            }
            return data;
        }
    }

    private static CoreModel.Type unwrap(CoreModel.Type type) {
        CoreModel.Type t = type;
        for (int i = 0; i < 8 && t != null; i++) {
            if (t instanceof CoreModel.Maybe m) {
                t = m.type;
            } else if (t instanceof CoreModel.Option o) {
                t = o.type;
            } else if (t instanceof CoreModel.PiiType p) {
                t = p.baseType;
            } else {
                return t;
            }
        }
        return t;
    }

    private static CoreModel.Type elementType(CoreModel.Type type) {
        CoreModel.Type t = unwrap(type);
        if (t instanceof CoreModel.ListT list) {
            return list.type;
        }
        if (t instanceof CoreModel.TypeApp app && "List".equals(app.base) && app.args != null && app.args.size() == 1) {
            return app.args.get(0);
        }
        return null;
    }

    private static CoreModel.Field fieldOf(CoreModel.Data data, String name) {
        if (data.fields == null) {
            return null;
        }
        for (CoreModel.Field field : data.fields) {
            if (field != null && field.name != null && sameName(field.name, name)) {
                return field;
            }
        }
        return null;
    }

    /** 与运行时同一口径的键存在判断：原样或德语 umlaut 还原后相同即视为同一键。 */
    private static boolean hasKey(Map<?, ?> map, String name) {
        if (map.containsKey(name)) {
            return true;
        }
        for (Object key : map.keySet()) {
            if (key instanceof String s && sameName(s, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameName(String a, String b) {
        return a.equals(b) || denormalizeUmlauts(a).equals(denormalizeUmlauts(b));
    }

    private static String denormalizeUmlauts(String s) {
        return s.replace("ü", "ue").replace("ö", "oe").replace("ä", "ae").replace("ß", "ss")
            .replace("Ü", "Ue").replace("Ö", "Oe").replace("Ä", "Ae");
    }

    private static String join(String path, String segment) {
        return path.isEmpty() ? segment : path + "." + segment;
    }
}
