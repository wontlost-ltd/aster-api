package io.aster.policy.api.convert;

import aster.core.ir.CoreModel;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 命名参数上下文映射器测试
 */
class NamedContextMapperTest {

    /**
     * 创建测试用的参数列表
     */
    private List<CoreModel.Param> createParams(String... names) {
        return java.util.Arrays.stream(names)
            .map(name -> {
                CoreModel.Param param = new CoreModel.Param();
                param.name = name;
                param.type = null; // 测试不需要类型
                return param;
            })
            .toList();
    }

    @Test
    void testNamedFormatMapping() {
        // 函数定义：func(申请, 年龄)
        List<CoreModel.Param> params = createParams("申请", "年龄");

        // 命名格式上下文
        Map<String, Object> context = new HashMap<>();
        context.put("申请", Map.of("编号", "A001", "金额", 50000));
        context.put("年龄", 25);

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertTrue(result.wasNamedFormat());
        assertEquals(2, result.positionalArgs().length);
        assertEquals(Map.of("编号", "A001", "金额", 50000), result.positionalArgs()[0]);
        assertEquals(25, result.positionalArgs()[1]);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testPositionalFormatMapping() {
        // 函数定义：func(申请, 年龄)
        List<CoreModel.Param> params = createParams("申请", "年龄");

        // 位置格式上下文
        List<Object> context = List.of(
            Map.of("编号", "A001", "金额", 50000),
            25
        );

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertFalse(result.wasNamedFormat());
        assertEquals(2, result.positionalArgs().length);
    }

    @Test
    void testSingleMapFallback() {
        // 函数定义：func(申请)
        List<CoreModel.Param> params = createParams("申请");

        // 单个 Map，键与参数名匹配
        Map<String, Object> context = new HashMap<>();
        context.put("申请", Map.of("编号", "A001"));

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertTrue(result.wasNamedFormat());
        assertEquals(1, result.positionalArgs().length);
    }

    @Test
    void testLegacySingleObjectFormat() {
        // 函数定义：func(data)
        List<CoreModel.Param> params = createParams("data");

        // 传入的 Map 键与参数名不匹配（旧版单对象格式）
        Map<String, Object> context = new HashMap<>();
        context.put("编号", "A001");
        context.put("金额", 50000);

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertFalse(result.wasNamedFormat()); // 不是命名格式
        assertEquals(1, result.positionalArgs().length);
        // 整个 Map 作为第一个参数
        assertEquals(context, result.positionalArgs()[0]);
    }

    @Test
    void testMissingParameter() {
        // 函数定义：func(申请, 年龄)
        List<CoreModel.Param> params = createParams("申请", "年龄");

        // 命名格式上下文，缺少 "年龄"
        Map<String, Object> context = new HashMap<>();
        context.put("申请", Map.of("编号", "A001"));

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertFalse(result.success());
        assertTrue(result.hasError());
        assertTrue(result.error().contains("年龄"));
    }

    @Test
    void testUnknownParameterWarning() {
        // 函数定义：func(申请)
        List<CoreModel.Param> params = createParams("申请");

        // 命名格式上下文，包含未知参数
        Map<String, Object> context = new HashMap<>();
        context.put("申请", Map.of("编号", "A001"));
        context.put("未知参数", "some value");

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertTrue(result.wasNamedFormat());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().get(0).contains("未知参数"));
    }

    @Test
    void testNullContext() {
        List<CoreModel.Param> params = createParams("申请");

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(null, params);

        assertTrue(result.success());
        assertEquals(0, result.positionalArgs().length);
    }

    @Test
    void testEmptyParams() {
        Map<String, Object> context = Map.of("key", "value");

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, List.of());

        assertTrue(result.success());
        assertEquals(1, result.positionalArgs().length);
    }

    @Test
    void testEnglishNamedFormat() {
        // 函数定义：func(application, age)
        List<CoreModel.Param> params = createParams("application", "age");

        // 命名格式上下文
        Map<String, Object> context = new HashMap<>();
        context.put("application", Map.of("id", "A001", "amount", 50000));
        context.put("age", 25);

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success());
        assertTrue(result.wasNamedFormat());
        assertEquals(2, result.positionalArgs().length);
    }

    /**
     * PII 脱敏：参数**值**不得进日志。
     *
     * <p><b>为什么必须有这条</b>：脱敏修复此前**全仓零断言**——把日志改回打印
     * {@code positionalArgs[i]} 本体，所有测试照样全绿。而 DEBUG 打开时重跑一批
     * 执行就会把成百上千条客户金融数据写进日志，绕过一切 PII 保留策略。
     *
     * <p>做法：挂一个 in-memory Handler 到 JUL 根 logger（JBoss LogManager 底层
     * 走 JUL），把级别提到 FINE（对应 JBoss DEBUG），跑一次含哨兵值的映射，
     * 断言产出的日志里**不含值**、但**含键名**（键名是排查所需，且非客户数据）。
     */
    @Test
    void 参数值不得写入日志_只记键名() {
        final String SENTINEL = "SECRET-金额-8675309";
        List<LogRecord> captured = new CopyOnWriteArrayList<>();
        Handler probe = new Handler() {
            @Override public void publish(LogRecord r) { captured.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };

        java.util.logging.Logger root = java.util.logging.LogManager.getLogManager().getLogger("");
        Level originalLevel = root.getLevel();
        root.addHandler(probe);
        root.setLevel(Level.ALL);
        java.util.logging.Logger target =
            java.util.logging.Logger.getLogger("io.aster.policy.api.convert.NamedContextMapper");
        Level targetOriginal = target.getLevel();
        target.setLevel(Level.ALL);
        try {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("amount", SENTINEL);
            NamedContextMapper.mapContext(ctx, createParams("amount"));

            String all = captured.stream()
                .map(r -> {
                    String m = r.getMessage() == null ? "" : r.getMessage();
                    Object[] ps = r.getParameters();
                    if (ps != null) {
                        for (Object o : ps) {
                            m = m + " " + o;
                        }
                    }
                    return m;
                })
                .reduce("", (a, b) -> a + "\n" + b);

            assertFalse(all.contains(SENTINEL),
                "★参数值泄漏进日志——DEBUG 打开时会把客户明文金融数据成批写盘。实际日志: " + all);
            assertTrue(all.contains("amount"),
                "键名应保留：排查『参数没映射上』需要它，且键名不是客户数据。实际日志: " + all);
        } finally {
            root.removeHandler(probe);
            root.setLevel(originalLevel);
            target.setLevel(targetOriginal);
        }
    }

    /**
     * ★多参数规则、且键一个都没对上时，必须在**映射层**报错。
     *
     * <p>此前这里和单参数走同一条兜底：返回 {@code new Object[]{map}}，
     * 把 1 个实参喂给 N 参函数。错误于是延迟到引擎里，炸成
     * {@code Lambda func_X expects at least 2 arguments} 或
     * {@code HostObject 不支持成员访问}——两者都指向引擎/HostAccess 配置，
     * 而真因只是**键名对不上**，排查方向被彻底带偏。
     *
     * <p>真实事故：内置样例的 defaultInput 只有一份英文键，
     * 而非英文规则的参数名是本地化的（de 是 {@code fahrer, fahrzeug}），
     * 于是 5 示例 × zh/de 共 10 例执行全挂。
     */
    @Test
    void 多参数且键全不匹配时必须报可诊断的错误() {
        List<CoreModel.Param> params = createParams("fahrer", "fahrzeug");

        Map<String, Object> context = new HashMap<>();
        context.put("driver", Map.of("age", 35));
        context.put("vehicle", Map.of("year", 2020));

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertFalse(result.success(), "★多参数键全不匹配必须失败，不能悄悄按单参数处理");
        assertNotNull(result.error());
        assertTrue(result.error().contains("driver"),
            "★错误必须列出实际收到的键，否则用户不知道自己传了什么");
        assertTrue(result.error().contains("fahrer"),
            "★错误必须列出期望的参数名，否则用户不知道该改成什么");
    }

    /** ★单参数规则的兜底行为必须**保持不变**（整个 Map 当作那一个参数）。 */
    @Test
    void 单参数规则的整体Map兜底必须保留() {
        List<CoreModel.Param> params = createParams("applicant");

        Map<String, Object> context = new HashMap<>();
        context.put("id", "A001");
        context.put("age", 35);

        NamedContextMapper.MappingResult result = NamedContextMapper.mapContext(context, params);

        assertTrue(result.success(), "★单参数场景是合法用法，不得因本次改动被打回");
        assertFalse(result.wasNamedFormat());
        assertEquals(1, result.positionalArgs().length);
        assertEquals(context, result.positionalArgs()[0]);
    }

    /** 构造一个声明为某 Data 类型的单参数。 */
    private List<CoreModel.Param> typedParam(String name, String typeName) {
        CoreModel.Param p = new CoreModel.Param();
        p.name = name;
        CoreModel.TypeName tn = new CoreModel.TypeName();
        tn.name = typeName;
        p.type = tn;
        return List.of(p);
    }

    private Map<String, java.util.Set<String>> decls(String typeName, String... fields) {
        return Map.of(typeName, new java.util.LinkedHashSet<>(java.util.Arrays.asList(fields)));
    }

    @Test
    void singleParam_keysCompletelyMismatch_reportsRealCause() {
        // ★issue #244：单参数规则把整个 Map 当作那一个参数是**合法且常用**的。
        //   但若参数声明为某 Data 类型、而 Map 的键与其字段**完全不相交**，
        //   那就是调用方给错了形状。此前照样放行，错误被推迟到引擎里炸成
        //     「HostObject 不支持成员访问，成员：age」
        //   ——把排查方向指向 HostAccess 配置，而真因是键名对不上。
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("wrongKey", Map.of("age", 30));

        var r = NamedContextMapper.mapContext(ctx, typedParam("applicant", "Applicant"),
            decls("Applicant", "id", "age", "name"));

        assertFalse(r.success(), "键与字段完全不相交时应在映射层就失败");
        assertTrue(r.error().contains("wrongKey"), "错误应回显实际键，实际：" + r.error());
        assertTrue(r.error().contains("age"), "错误应回显期望字段，实际：" + r.error());
    }

    @Test
    void singleParam_wholeMapAsStruct_stillWorks() {
        // ★同等重要的一半：整体传入是正常用法，不得被误伤。
        //   否则「单参数一律报错」也能让上面那条通过，那是假修复。
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("id", "A1");
        ctx.put("age", 30);

        var r = NamedContextMapper.mapContext(ctx, typedParam("applicant", "Applicant"),
            decls("Applicant", "id", "age", "name"));

        assertTrue(r.success(), "键与字段相交时必须继续按整体 Map 传入");
        assertEquals(1, r.positionalArgs().length);
    }

    @Test
    void singleParam_partialOverlap_stillWorks() {
        // 部分匹配（只给了必填字段）同样是正常用法。
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("id", "A1");

        var r = NamedContextMapper.mapContext(ctx, typedParam("applicant", "Applicant"),
            decls("Applicant", "id", "age", "name"));

        assertTrue(r.success(), "只要有交集就应放行");
    }

    @Test
    void singleParam_noDeclsProvided_behaviourUnchanged() {
        // 未提供声明表（旧签名/非 Data 类型）时不做校验，行为与改动前一致。
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("wrongKey", 1);

        var r = NamedContextMapper.mapContext(ctx, typedParam("applicant", "Applicant"));

        assertTrue(r.success(), "无声明表时不得改变原有行为");
    }
}
