package io.aster.policy.runtime;

import aster.truffle.runtime.interop.AsterListValue;
import aster.truffle.runtime.interop.AsterMapValue;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 */
public final class PolyglotValues {

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
     * 宿主值 → guest 值：Map 转 {@link AsterMapValue}，List / 数组转 {@link AsterListValue}，
     * 递归处理嵌套；标量原样传递（由 polyglot 边界按原语处理）。总是复制，guest 不会改到调用方的对象。
     */
    public static Object toGuest(Object value) {
        if (value instanceof AsterMapValue || value instanceof AsterListValue) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> entries = new LinkedHashMap<>();
            map.forEach((k, v) -> entries.put(String.valueOf(k), toGuest(v)));
            return new AsterMapValue(entries);
        }
        if (value instanceof Object[] array) {
            return toGuest(Arrays.asList(array));
        }
        if (value instanceof Collection<?> collection) {
            List<Object> elements = new ArrayList<>(collection.size());
            collection.forEach(e -> elements.add(toGuest(e)));
            return new AsterListValue(elements);
        }
        return value;
    }

    /**
     * guest 值 → 可被 Jackson 序列化的宿主值。
     *
     * <p>形态约定沿用既有口径：数组 → {@code Object[]}，对象/映射 → {@code Map}（保留键序），
     * 整数优先收窄为 int/long。无法识别的值退化为字符串表示，避免序列化失败。
     */
    public static Object toHost(Value value) {
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
            return hostToPlain(value.asHostObject());
        }
        if (value.hasArrayElements()) {
            return arrayToHost(value);
        }
        if (value.hasHashEntries()) {
            return hashToHost(value);
        }
        if (value.hasMembers()) {
            return membersToHost(value);
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

    private static Object[] arrayToHost(Value value) {
        int size = (int) value.getArraySize();
        Object[] array = new Object[size];
        for (int i = 0; i < size; i++) {
            array[i] = toHost(value.getArrayElement(i));
        }
        return array;
    }

    private static Map<String, Object> hashToHost(Value value) {
        Map<String, Object> map = new LinkedHashMap<>();
        Value iterator = value.getHashEntriesIterator();
        while (iterator.hasIteratorNextElement()) {
            Value entry = iterator.getIteratorNextElement();
            map.put(String.valueOf(toHost(entry.getArrayElement(0))), toHost(entry.getArrayElement(1)));
        }
        return map;
    }

    private static Map<String, Object> membersToHost(Value value) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : value.getMemberKeys()) {
            map.put(key, toHost(value.getMember(key)));
        }
        return map;
    }

    /**
     * 解包后的宿主 Java 对象：集合递归复制成与 guest 出参同一形态（数组 → {@code Object[]}，
     * 映射 → {@code Map}），元素若仍是 polyglot {@link Value} 则回到 {@link #toHost}。
     */
    private static Object hostToPlain(Object host) {
        if (host instanceof Value nested) {
            return toHost(nested);
        }
        if (host instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), hostToPlain(v)));
            return copy;
        }
        if (host instanceof Object[] array) {
            return hostToPlain(Arrays.asList(array));
        }
        if (host instanceof Collection<?> collection) {
            return collection.stream().map(PolyglotValues::hostToPlain).toArray();
        }
        return host;
    }
}
