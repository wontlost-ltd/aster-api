package io.aster.policy.runtime;

import aster.truffle.runtime.interop.AsterDecimalValue;
import aster.truffle.runtime.interop.AsterListValue;
import aster.truffle.runtime.interop.AsterMapValue;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PolyglotValues} 的边界转换契约（aster-cloud#639）。
 *
 * <p>两条执行路径的 Context 都以 EXPLICIT 为基线、只放开数组/List/Map 读取；
 * 这里用同一宿主访问口径构造 Context，钉住「host Map 不得被读成 {}」这一核心事实。
 */
@SuppressWarnings("unchecked")
class PolyglotValuesTest {

    private Context context;

    @BeforeEach
    void setUp() {
        context = Context.newBuilder()
            .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowMapAccess(true)
                .build())
            .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static Map<String, Object> person() {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", "Ann");
        person.put("tags", List.of("a", "b"));
        person.put("address", Map.of("city", "Wellington"));
        return person;
    }

    @Test
    void 宿主映射出参解包为完整映射而非空对象() {
        Map<?, ?> map = assertInstanceOf(Map.class, PolyglotValues.toHost(context.asValue(person())));

        assertEquals("Ann", map.get("name"));
        assertArrayEquals(new Object[]{"a", "b"}, (Object[]) map.get("tags"));
        assertEquals(Map.of("city", "Wellington"), map.get("address"));
    }

    @Test
    void 宿主列表与数组出参解包为数组() {
        assertArrayEquals(new Object[]{1, 2}, (Object[]) PolyglotValues.toHost(context.asValue(List.of(1, 2))));
        assertArrayEquals(new Object[]{"x", 3},
            (Object[]) PolyglotValues.toHost(context.asValue(new Object[]{"x", 3})));
    }

    @Test
    void guest映射与列表出参保持既有形态() {
        Object guestMap = PolyglotValues.toGuest(person());
        Map<?, ?> map = assertInstanceOf(Map.class, PolyglotValues.toHost(context.asValue(guestMap)));
        Object list = PolyglotValues.toHost(context.asValue(PolyglotValues.toGuest(List.of(1, 2))));

        assertEquals("Ann", map.get("name"));
        assertEquals(Map.of("city", "Wellington"), map.get("address"));
        assertArrayEquals(new Object[]{1, 2}, (Object[]) list);
    }

    @Test
    void 标量出参按原语转换() {
        assertEquals(7, PolyglotValues.toHost(context.asValue(7)));
        assertEquals(1L << 40, PolyglotValues.toHost(context.asValue(1L << 40)));
        assertEquals(1.5d, PolyglotValues.toHost(context.asValue(1.5d)));
        assertEquals("s", PolyglotValues.toHost(context.asValue("s")));
        assertEquals(true, PolyglotValues.toHost(context.asValue(true)));
        assertNull(PolyglotValues.toHost(context.asValue(null)));
        assertNull(PolyglotValues.toHost(null));
    }

    @Test
    void 入参递归转成guest原生集合() {
        AsterMapValue map = assertInstanceOf(AsterMapValue.class, PolyglotValues.toGuest(person()));

        assertInstanceOf(AsterListValue.class, map.entries().get("tags"));
        assertInstanceOf(AsterMapValue.class, map.entries().get("address"));
        assertInstanceOf(AsterListValue.class, PolyglotValues.toGuest(new Object[]{1, List.of(2)}));
    }

    @Test
    void 入参转换不改调用方对象且对已转换值幂等() {
        Map<String, Object> original = person();
        Object guest = PolyglotValues.toGuest(original);

        assertEquals(person(), original);
        assertSame(guest, PolyglotValues.toGuest(guest));
        assertEquals(42, PolyglotValues.toGuest(42));
        assertNull(PolyglotValues.toGuest(null));
        assertEquals(0, PolyglotValues.toGuestArgs(null).length);
    }

    // ── 边界约束：环、深度、键（aster-cloud#639 复审）────────────────────

    /** n 层嵌套映射 {"a":{"a":...{}}}，最外层计为第 1 层。 */
    private static Map<String, Object> nestedMaps(int levels) {
        Map<String, Object> current = new LinkedHashMap<>();
        for (int i = 1; i < levels; i++) {
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("a", current);
            current = outer;
        }
        return current;
    }

    private static String codeOf(Executable conversion) {
        return assertThrows(ValueShapeException.class, conversion).code();
    }

    @Test
    void 自引用映射_列表_数组入参被拒为input_cyclic() {
        Map<String, Object> map = new HashMap<>();
        map.put("self", map);
        List<Object> list = new ArrayList<>();
        list.add(list);
        Object[] array = new Object[1];
        array[0] = array;
        Map<String, Object> indirect = new HashMap<>();
        indirect.put("items", List.of(indirect));

        assertEquals(ValueShapeException.INPUT_CYCLIC, codeOf(() -> PolyglotValues.toGuest(map)));
        assertEquals(ValueShapeException.INPUT_CYCLIC, codeOf(() -> PolyglotValues.toGuest(list)));
        assertEquals(ValueShapeException.INPUT_CYCLIC, codeOf(() -> PolyglotValues.toGuest(array)));
        assertEquals(ValueShapeException.INPUT_CYCLIC, codeOf(() -> PolyglotValues.toGuestArgs(new Object[]{indirect})));
    }

    @Test
    void 同一子对象被多处共享不算环() {
        Map<String, Object> shared = Map.of("city", "Wellington");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("home", shared);
        root.put("work", shared);
        root.put("both", List.of(shared, shared));

        AsterMapValue guest = assertInstanceOf(AsterMapValue.class, PolyglotValues.toGuest(root));
        assertEquals(3, guest.entries().size());
    }

    @Test
    void 入参嵌套恰好到上限可用_超过一层被拒为input_too_deep() {
        assertInstanceOf(AsterMapValue.class, PolyglotValues.toGuest(nestedMaps(PolyglotValues.MAX_DEPTH)));
        assertEquals(ValueShapeException.INPUT_TOO_DEEP,
            codeOf(() -> PolyglotValues.toGuest(nestedMaps(PolyglotValues.MAX_DEPTH + 1))));

        List<Object> deepList = new ArrayList<>();
        Object current = deepList;
        for (int i = 1; i <= PolyglotValues.MAX_DEPTH; i++) {
            List<Object> next = new ArrayList<>();
            ((List<Object>) current).add(next);
            current = next;
        }
        assertEquals(ValueShapeException.INPUT_TOO_DEEP, codeOf(() -> PolyglotValues.toGuest(deepList)));
    }

    @Test
    void 非字符串映射键被拒_避免1与字符串1互相覆盖() {
        Map<Object, Object> colliding = new LinkedHashMap<>();
        colliding.put(1, "number");
        colliding.put("1", "text");

        ValueShapeException e = assertThrows(ValueShapeException.class, () -> PolyglotValues.toGuest(colliding));
        assertEquals(ValueShapeException.INPUT_INVALID_KEY, e.code());
        assertTrue(e.inputError());
        assertEquals(400, e.httpStatus());
    }

    @Test
    void 入参标量_大整数与小数转成合法guest值_其余宿主对象被拒() {
        assertEquals(5L, PolyglotValues.toGuest(BigInteger.valueOf(5)));
        assertEquals(BigInteger.TWO.pow(70).doubleValue(), PolyglotValues.toGuest(BigInteger.TWO.pow(70)));
        AsterDecimalValue decimal = assertInstanceOf(AsterDecimalValue.class, PolyglotValues.toGuest(new BigDecimal("1.50")));
        assertEquals(0, decimal.decimal().compareTo(new BigDecimal("1.5")));
        assertEquals(ValueShapeException.INPUT_UNSUPPORTED_VALUE,
            codeOf(() -> PolyglotValues.toGuest(Map.of("when", java.time.LocalDate.of(2026, 1, 1)))));
    }

    @Test
    void 出参宿主环_过深_非字符串键分别被拒且为422() {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        Map<Object, Object> numericKey = new HashMap<>();
        numericKey.put(1, "x");

        ValueShapeException e = assertThrows(ValueShapeException.class,
            () -> PolyglotValues.toHost(context.asValue(cyclic)));
        assertEquals(ValueShapeException.OUTPUT_CYCLIC, e.code());
        assertEquals(422, e.httpStatus());
        assertEquals(ValueShapeException.OUTPUT_TOO_DEEP,
            codeOf(() -> PolyglotValues.toHost(context.asValue(nestedMaps(PolyglotValues.MAX_DEPTH + 1)))));
        assertEquals(ValueShapeException.OUTPUT_INVALID_KEY,
            codeOf(() -> PolyglotValues.toHost(context.asValue(numericKey))));
        assertInstanceOf(Map.class, PolyglotValues.toHost(context.asValue(nestedMaps(PolyglotValues.MAX_DEPTH))));
    }

    @Test
    void guest出参超过上限被拒为output_too_deep() {
        Object guest = PolyglotValues.toGuest(nestedMaps(PolyglotValues.MAX_DEPTH));
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("a", guest);
        AsterMapValue deeper = new AsterMapValue(wrapper);

        assertInstanceOf(Map.class, PolyglotValues.toHost(context.asValue(guest)));
        assertEquals(ValueShapeException.OUTPUT_TOO_DEEP, codeOf(() -> PolyglotValues.toHost(context.asValue(deeper))));
    }
}
