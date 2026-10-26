package io.aster.policy.runtime;

import aster.truffle.runtime.interop.AsterDecimalValue;
import aster.truffle.runtime.interop.AsterListValue;
import aster.truffle.runtime.interop.AsterMapValue;
import org.graalvm.polyglot.Value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宿主 ↔ guest 的值边界：两条执行路径（{@code DynamicCnlExecutor} 与 {@code TrufflePolicyRuntime}）
 * 共用的唯一转换口径。
 *
 * <p><b>为什么入参要先转成 guest 原生集合</b>（aster-cloud#639）：评估上下文是 Jackson 解出的
 * {@code Map}/{@code List}。若原样经 {@code Value.execute} 传入，GraalVM 会把它包成 host object；
 * 规则读字段没问题（{@code allowMapAccess}），但规则<b>原样返回</b>这个参数时：
 * <ul>
 *   <li>返回值仍是 host object，按成员读出的键为空 → 结果被序列化成 {@code {}}；</li>
 *   <li>trace 归一化不认识 host object，判定降级 → {@code trace_not_replayable}。</li>
 * </ul>
 * 转成引擎自己的 {@link AsterMapValue}/{@link AsterListValue} 后，入参与 guest 内部构造的值同形，
 * 返回、trace、回放三处都走引擎既有路径，不再有 host object 这一特例。
 *
 * <p><b>为什么出参仍要识别 host object 与 hash entries</b>：入参之外仍可能有宿主值流回
 * （例如 Builtins 返回的 Java 集合、未来新增的宿主注入点）。按成员读取 host Map 会静默得到空对象，
 * 这里直接解包宿主对象并按 hash entries 读取映射，保证任何形态都不会被悄悄丢成 {@code {}}。
 *
 * <p><b>边界约束</b>：两个方向都按容器嵌套层数设上限 {@link #MAX_DEPTH}，并按对象身份检测环；
 * 映射键必须是字符串（JSON 来源天然满足；非字符串键经字符串化会互相覆盖，如 {@code 1} 与 {@code "1"}）。
 * 违例抛 {@link ValueShapeException}，入参回 400、出参回 422，而不是栈溢出或静默丢数据。
 */
public final class PolyglotValues {

    /** 容器（映射 / 列表 / 数组）最大嵌套层数；顶层容器记为第 1 层。业务上下文远达不到该深度。 */
    public static final int MAX_DEPTH = 64;

    private PolyglotValues() {
    }

    /** 把位置参数逐个转成 guest 原生值；null 视同无参。 */
    public static Object[] toGuestArgs(Object[] args) {
        if (args == null) {
            return new Object[0];
        }
        Object[] guestArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            guestArgs[i] = toGuest(args[i]);
        }
        return guestArgs;
    }

    /**
     * 宿主值 → guest 值：Map 转 {@link AsterMapValue}，List / 数组转 {@link AsterListValue}，递归处理嵌套；
     * 总是复制，guest 不会改到调用方的对象。标量须是合法 interop 值，见 {@link Walk#guestScalar}。
     */
    public static Object toGuest(Object value) {
        return new Walk(Direction.INPUT).toGuest(value, 1);
    }

    /**
     * guest 值 → 可被 Jackson 序列化的宿主值。
     *
     * <p>形态约定沿用既有口径：数组 → {@code Object[]}，对象/映射 → {@code Map}（保留键序），
     * 整数优先收窄为 int/long，Decimal 为字符串。无法识别的值退化为字符串表示，避免序列化失败。
     */
    public static Object toHost(Value value) {
        return new Walk(Direction.OUTPUT).toHost(value, 1);
    }

    /** 转换方向：决定违例时报哪一组错误码。 */
    private enum Direction {
        INPUT("入参", true, ValueShapeException.INPUT_TOO_DEEP, ValueShapeException.INPUT_CYCLIC,
            ValueShapeException.INPUT_INVALID_KEY),
        OUTPUT("结果", false, ValueShapeException.OUTPUT_TOO_DEEP, ValueShapeException.OUTPUT_CYCLIC,
            ValueShapeException.OUTPUT_INVALID_KEY);

        final String label;
        final boolean input;
        final String tooDeep;
        final String cyclic;
        final String invalidKey;

        Direction(String label, boolean input, String tooDeep, String cyclic, String invalidKey) {
            this.label = label;
            this.input = input;
            this.tooDeep = tooDeep;
            this.cyclic = cyclic;
            this.invalidKey = invalidKey;
        }

        ValueShapeException fail(String code, String message) {
            return new ValueShapeException(code, input, label + message);
        }
    }

    /**
     * 单次转换的遍历状态：{@code path} 记录当前递归路径上的宿主容器（按身份），
     * 进入时登记、退出时移除——同一子对象被多处共享（DAG）是合法的，只有回到祖先才算环。
     */
    private static final class Walk {
        private final Direction direction;
        private final Set<Object> path = Collections.newSetFromMap(new IdentityHashMap<>());

        Walk(Direction direction) {
            this.direction = direction;
        }

        // ── 宿主 → guest ──────────────────────────────────────────

        Object toGuest(Object value, int depth) {
            if (value instanceof AsterMapValue || value instanceof AsterListValue) {
                return value;
            }
            if (value instanceof Map<?, ?> map) {
                return guestMap(map, depth);
            }
            if (value instanceof Object[] array) {
                return guestList(array, Arrays.asList(array), depth);
            }
            if (value instanceof Collection<?> collection) {
                return guestList(collection, collection, depth);
            }
            return guestScalar(value);
        }

        private AsterMapValue guestMap(Map<?, ?> map, int depth) {
            enter(map, depth);
            Map<String, Object> entries = new LinkedHashMap<>();
            map.forEach((k, v) -> entries.put(key(k), toGuest(v, depth + 1)));
            path.remove(map);
            return new AsterMapValue(entries);
        }

        private AsterListValue guestList(Object identity, Collection<?> items, int depth) {
            enter(identity, depth);
            List<Object> elements = new ArrayList<>(items.size());
            items.forEach(e -> elements.add(toGuest(e, depth + 1)));
            path.remove(identity);
            return new AsterListValue(elements);
        }

        /**
         * guest 集合内的标量必须是 Truffle 合法 interop 值，否则引擎读字段时触发契约违例。
         * JSON 只会产出下列类型；BigInteger（超 long 的整数）沿用 polyglot 对顶层大整数的口径转 double，
         * BigDecimal 交给引擎一等公民 Decimal；其余宿主对象拒绝，而不是让 guest 拿到不透明对象。
         */
        private Object guestScalar(Object value) {
            if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                    || value instanceof Integer || value instanceof Long || value instanceof Double
                    || value instanceof Float || value instanceof Short || value instanceof Byte) {
                return value;
            }
            if (value instanceof BigInteger big) {
                return big.bitLength() < Long.SIZE ? (Object) big.longValue() : (Object) big.doubleValue();
            }
            if (value instanceof BigDecimal decimal) {
                return AsterDecimalValue.of(decimal);
            }
            throw direction.fail(ValueShapeException.INPUT_UNSUPPORTED_VALUE,
                "包含不支持的值类型 " + value.getClass().getSimpleName() + "（仅支持 JSON 标量、对象与数组）");
        }

        // ── guest → 宿主 ──────────────────────────────────────────

        Object toHost(Value value, int depth) {
            if (value == null || value.isNull()) {
                return null;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isNumber()) {
                return toNumber(value);
            }
            if (value.isString()) {
                return value.asString();
            }
            // host object 必须先于成员判断：host Map 的成员键为空，按成员读会被静默丢成 {}
            if (value.isHostObject()) {
                return hostToPlain(value.asHostObject(), depth);
            }
            if (value.hasArrayElements()) {
                return arrayToHost(value, depth);
            }
            if (value.hasHashEntries()) {
                return hashToHost(value, depth);
            }
            if (value.hasMembers()) {
                return membersToHost(value, depth);
            }
            return value.toString();
        }

        private static Object toNumber(Value value) {
            if (value.fitsInInt()) {
                return value.asInt();
            }
            if (value.fitsInLong()) {
                return value.asLong();
            }
            if (value.fitsInDouble()) {
                return value.asDouble();
            }
            // 超出 double 精度的数值（如 BigInteger）保留其十进制表示，不静默丢精度
            return value.toString();
        }

        private Object[] arrayToHost(Value value, int depth) {
            checkDepth(depth);
            int size = (int) value.getArraySize();
            Object[] array = new Object[size];
            for (int i = 0; i < size; i++) {
                array[i] = toHost(value.getArrayElement(i), depth + 1);
            }
            return array;
        }

        private Map<String, Object> hashToHost(Value value, int depth) {
            checkDepth(depth);
            Map<String, Object> map = new LinkedHashMap<>();
            Value iterator = value.getHashEntriesIterator();
            while (iterator.hasIteratorNextElement()) {
                Value entry = iterator.getIteratorNextElement();
                map.put(key(toHost(entry.getArrayElement(0), depth + 1)), toHost(entry.getArrayElement(1), depth + 1));
            }
            return map;
        }

        private Map<String, Object> membersToHost(Value value, int depth) {
            checkDepth(depth);
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, toHost(value.getMember(key), depth + 1));
            }
            return map;
        }

        /**
         * 解包后的宿主 Java 对象：集合递归复制成与 guest 出参同一形态（数组 → {@code Object[]}，
         * 映射 → {@code Map}），元素若仍是 polyglot {@link Value} 则回到 {@link #toHost}。
         */
        private Object hostToPlain(Object host, int depth) {
            if (host instanceof Value nested) {
                return toHost(nested, depth);
            }
            if (host instanceof Map<?, ?> map) {
                enter(map, depth);
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(key(k), hostToPlain(v, depth + 1)));
                path.remove(map);
                return copy;
            }
            if (host instanceof Object[] array) {
                return plainArray(array, Arrays.asList(array), depth);
            }
            if (host instanceof Collection<?> collection) {
                return plainArray(collection, collection, depth);
            }
            return host;
        }

        private Object[] plainArray(Object identity, Collection<?> items, int depth) {
            enter(identity, depth);
            Object[] copy = items.stream().map(e -> hostToPlain(e, depth + 1)).toArray();
            path.remove(identity);
            return copy;
        }

        // ── 共用约束 ─────────────────────────────────────────────

        private void enter(Object container, int depth) {
            checkDepth(depth);
            if (!path.add(container)) {
                throw direction.fail(direction.cyclic, "存在循环引用（容器直接或间接包含自身）");
            }
        }

        private void checkDepth(int depth) {
            if (depth > MAX_DEPTH) {
                throw direction.fail(direction.tooDeep, "嵌套层数超过上限（最多 " + MAX_DEPTH + " 层）");
            }
        }

        private String key(Object key) {
            if (key instanceof String text) {
                return text;
            }
            throw direction.fail(direction.invalidKey,
                "的映射键必须是字符串，实际为 " + (key == null ? "null" : key.getClass().getSimpleName()));
        }
    }
}
