package io.aster.policy.runtime;

import aster.truffle.runtime.interop.AsterListValue;
import aster.truffle.runtime.interop.AsterMapValue;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link PolyglotValues} 的边界转换契约（aster-cloud#639）。
 *
 * <p>两条执行路径的 Context 都以 EXPLICIT 为基线、只放开数组/List/Map 读取；
 * 这里用同一宿主访问口径构造 Context，钉住「host Map 不得被读成 {}」这一核心事实。
 */
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
}
