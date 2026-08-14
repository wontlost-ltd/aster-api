package io.aster.policy.runtime;

import io.aster.policy.compiler.CompilationMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Truffle 策略运行时
 *
 * 管理 GraalVM Polyglot Context 池，提供策略执行 API。
 * Context 池大小 = CPU 核心数，平衡并发性能和内存占用。
 */
@ApplicationScoped
public class TrufflePolicyRuntime {

    private static final Logger LOG = Logger.getLogger(TrufflePolicyRuntime.class);

    private BlockingQueue<Context> contextPool;
    private Engine sharedEngine;
    private int poolSize;
    private ResourceLimits pooledLimits;

    /**
     * 单次策略执行的 wall-clock 上界（毫秒）。
     *
     * <p>这是**真实**的执行上界——statementLimit 在本语言上不触发（见 init() 的实测记录）。
     * 取值参照 What-If 路径（ReplayBatchService）的同类超时：合法策略典型 &lt; 50ms，
     * 5s 留足冷启动与 GC 抖动的余量，同时把失控执行的影响限制在单个请求。
     */
    private static final long EXECUTION_TIMEOUT_MS = Long.getLong(
        "aster.policy.execution-timeout-ms", 5_000L);

    /**
     * 看门狗调度器：到点用 {@code ctx.close(true)} 中断执行中的 guest 代码。
     *
     * <p>daemon 线程，单线程即可——它只负责“到点踹一脚”，不承载业务。
     */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aster-policy-watchdog");
            t.setDaemon(true);
            return t;
        });

    // Core IR JSON → 已构建的 Truffle Source（cached=true）。
    // 此前 execute() 每次都 ctx.eval("aster", coreJson) 传 raw String，GraalVM 每次把 coreJson
    // 重新 parse 成 AST——即便编译缓存命中（CompiledPolicyCache 存的是 coreJson 字符串），执行仍
    // 每次冷 parse，是 executionWait 命中/未命中几乎不变的主因之一。改用按内容缓存的 cached Source：
    // 相同 coreJson 复用同一 Source → 共享 Engine 的 code cache 复用已解析的 AST，避免重复 parse。
    // 有界（LRU 近似：超上限清空重建，coreJson 集有限且随版本增长，容量兜底防无界）。
    private final java.util.concurrent.ConcurrentHashMap<String, Source> sourceCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SOURCE_CACHE_MAX = 2048;

    @PostConstruct
    void init() {
        LOG.info("初始化 TrufflePolicyRuntime...");

        // 1. 创建共享 Engine
        sharedEngine = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();

        // 2. 初始化 Context 池（大小 = CPU 核心数）
        poolSize = Runtime.getRuntime().availableProcessors();
        contextPool = new LinkedBlockingQueue<>(poolSize);

        LOG.infof("创建 Context 池，大小: %d", poolSize);

        // P0-R21 (audit R21): 收紧 polyglot sandbox 策略.
        // 之前 `allowAllAccess(true)` 把 host classpath / file IO / native /
        // socket / process 全部开放给策略代码——策略来自 untrusted tenant
        // input, 等价于 RCE. 现在显式列举允许的能力, 默认 deny.
        //
        // 允许:
        //   - HostAccess.EXPLICIT: 仅 @HostAccess.Export 标注的 host 方法可调用
        //     (Truffle DSL 节点 + Builtins 表都用了 @HostAccess.Export)
        //   - createThread/createProcess: 已隐式禁用 (allowCreateThread 默认 false)
        // 禁止:
        //   - IO (文件/网络) — 策略不应读盘或发起 socket
        //   - Native access — 不应 dlopen
        //   - Host class lookup — 不应 `Java.type("java.lang.Runtime")`
        //   - Polyglot bindings 共享 — 各 Context 隔离
        //
        // 对比 DynamicCnlExecutor (parser/) 已是 EXPLICIT + IOAccess.NONE,
        // 这次把 production execution path 拉齐.
        //
        // P1-R22 (audit R22) 的原始注释宣称「statementLimit 提供执行上限」。
        // ★这是**假的**，已实测证伪（issue #235）：
        //
        //   对照实验（无限递归策略，同一份 Core IR）：
        //     无任何 ResourceLimits   → Stack overflow, 177ms
        //     statementLimit=100      → Stack overflow,  51ms
        //     statementLimit=1        → Stack overflow,  29ms
        //
        //   三者表现完全一致，连 limit=1 都不触发——抛出的 ResourceExhausted
        //   全部来自 **JVM 栈溢出**，与 statementLimit 无关。
        //
        //   根因：AsterLanguage 未声明 ProvidedTags，AST 节点上没有 StatementTag，
        //   Truffle 无从计数。这不是配置问题，是**语言实现层缺一整套 tag**。
        //
        // 保留 statementLimit 是**无害的**（将来若补上 tag 即自动生效），但绝不能
        // 再把它当作执行上限的依据。真实上界改由 wall-clock 超时提供——与 What-If
        // 路径（ReplayBatchService.replayOne）已经采用的手段一致，那边同样是在
        // 发现 statementLimit 不触发后改回来的。
        //
        // 威胁面评估（比原注释描述的小）：AsterParser.g4 无任何循环结构
        // （无 while/for each/repeat），「恶意策略写无限循环」在本语言里**无法表达**。
        // 剩余无界路径是**递归**——已由 JVM 栈溢出兜住（上面实测 <200ms 即抛），
        // 以及深层表达式展开等非语言层构造，由 wall-clock 超时兜底。
        ResourceLimits limits = ResourceLimits.newBuilder()
            .statementLimit(10_000_000L, null)
            .build();
        this.pooledLimits = limits;
        for (int i = 0; i < poolSize; i++) {
            contextPool.offer(newPooledContext());
        }

        LOG.info("TrufflePolicyRuntime 初始化完成");
    }

    /**
     * 执行策略
     *
     * @param coreJson Core IR JSON
     * @param contextArgs 上下文参数
     * @param metadata 编译元数据
     * @return 执行结果
     */
    public ExecutionResult execute(
        String coreJson,
        Object[] contextArgs,
        CompilationMetadata metadata
    ) {
        long startTime = System.currentTimeMillis();
        Context ctx = null;
        boolean contextTainted = false;

        try {
            // 1. 从池中获取 Context
            ctx = contextPool.take();

            // 2. 看门狗：到点用 ctx.close(true) 中断**正在执行**的 Truffle 代码。
            //    这是真实上界的来源（statementLimit 不触发，见 init() 注释）。
            //    ★用 close(true) 而不是 Thread.interrupt()：Truffle 不响应 Java
            //    中断，只有 close(cancelIfExecuting=true) 能把执行中的 guest 代码停下。
            final Context watched = ctx;
            java.util.concurrent.ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
                try {
                    watched.close(true);
                } catch (Exception ignored) {
                    // 关闭本身失败不该再抛——此时已在超时路径上
                }
            }, EXECUTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

            try {
                // 3. 评估 Core IR（用按内容缓存的 cached Source，避免每次重新 parse coreJson）
                Value evalResult = ctx.eval(sourceFor(coreJson));

                // 4. 执行函数
                Object result = executeFunction(evalResult, contextArgs);

                long executionTime = System.currentTimeMillis() - startTime;
                return ExecutionResult.success(result, executionTime);
            } finally {
                // 没超时就取消看门狗；已经跑过则 cancel 返回 false
                if (!watchdog.cancel(false)) {
                    contextTainted = true;
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf(e, "获取 Context 被中断");
            return ExecutionResult.failure("执行被中断: " + e.getMessage());
        } catch (Exception e) {
            // ★资源耗尽 / 被看门狗取消的 Context **不可复用**，必须重建。
            //   原实现在 finally 里无条件 offer 回池，一个被 close 的 Context
            //   回池后，后续每个拿到它的请求都会立刻失败——一次超时污染整个池。
            if (isFatalToContext(e)) {
                contextTainted = true;
            }
            LOG.errorf(e, "策略执行失败: %s", e.getMessage());
            return ExecutionResult.failure("执行失败: " + e.getMessage());
        } finally {
            // 5. 归还 Context：干净的直接回池，被污染的丢弃并补一个新的，
            //    保证池容量不缩水（否则连续超时会把池耗成空，请求全部阻塞在 take()）。
            if (ctx != null) {
                if (contextTainted) {
                    discardAndReplace(ctx);
                } else {
                    contextPool.offer(ctx);
                }
            }
        }
    }

    /**
     * 按池化配置新建一个 Context。
     *
     * <p>初始化与「污染后重建」共用同一份配置——分成两处写就迟早漂移，
     * 重建出的 Context 少一条 sandbox 限制而无人察觉是最坏的形态。
     */
    private Context newPooledContext() {
        // 注意：engine 选项已在 sharedEngine 上设置，Context 不能重复设置
        return Context.newBuilder("aster")
            .engine(sharedEngine)
            .allowHostAccess(HostAccess.EXPLICIT)
            .allowIO(IOAccess.NONE)
            .allowNativeAccess(false)
            .allowHostClassLookup(name -> false)
            .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE)
            .allowCreateProcess(false)
            .resourceLimits(pooledLimits)
            .build();
    }

    /**
     * 判断异常是否已让该 Context 不可再用。
     *
     * <p>资源耗尽（含 JVM 栈溢出——递归策略的实际兜底）与 Context 被取消/关闭后，
     * 该 Context 的内部状态不保证可继续承载新的执行，回池即污染。
     */
    // 包级可见：供单测直接验证分类规则。这条规则是「失败后池是否可用」的全部依据，
    // 而它的两个触发源（看门狗超时、资源耗尽）都无法在共享 JVM 的单测里安全制造——
    // 递归会打坏整个 test fork，超时阈值是 static final 读不到测试设的属性。
    // 与其为了走通端到端而把测试写成不可靠的计时竞争，不如直接钉住这条规则本身。
    static boolean isFatalToContext(Exception e) {
        if (e instanceof org.graalvm.polyglot.PolyglotException pe) {
            return pe.isResourceExhausted() || pe.isCancelled() || pe.isExit();
        }
        return e instanceof IllegalStateException; // Context already closed
    }

    /** 丢弃被污染的 Context 并补建一个，维持池容量。 */
    private void discardAndReplace(Context tainted) {
        try {
            tainted.close(true);
        } catch (Exception ignored) {
            // 已关闭或正在关闭，忽略
        }
        try {
            contextPool.offer(newPooledContext());
        } catch (Exception e) {
            // 补建失败只缩水一个槽位，比放回一个坏 Context 好；记录以便观测
            LOG.errorf(e, "重建 Context 失败，池容量临时缩水");
        }
    }

    /**
     * 返回 coreJson 对应的 cached Truffle Source（按内容缓存，供共享 Engine 复用已解析 AST）。
     * cached(true)：让 GraalVM engine 级 code cache 对同一 Source 复用解析结果。
     */
    private Source sourceFor(String coreJson) {
        Source cached = sourceCache.get(coreJson);
        if (cached != null) {
            return cached;
        }
        // 容量兜底：coreJson 集随版本增长，超上限清空重建（近似 LRU，避免无界）。
        if (sourceCache.size() >= SOURCE_CACHE_MAX) {
            sourceCache.clear();
        }
        Source built;
        try {
            // name 用内容 hash，保证同内容同 name（Source 等价 → engine code cache 命中）。
            built = Source.newBuilder("aster", coreJson, "core-" + Integer.toHexString(coreJson.hashCode()))
                .cached(true)
                .build();
        } catch (Exception e) {
            // 构建 Source 理论不该失败（literal String content）；万一失败退回按内容直接构造。
            built = Source.create("aster", coreJson);
        }
        Source prev = sourceCache.putIfAbsent(coreJson, built);
        return prev != null ? prev : built;
    }

    /**
     * 执行函数并转换结果
     */
    private Object executeFunction(Value evalResult, Object[] contextArgs) {
        if (evalResult.canExecute()) {
            // 返回值是可执行的函数，传参调用
            Value execResult;
            if (contextArgs == null || contextArgs.length == 0) {
                execResult = evalResult.execute();
            } else {
                execResult = evalResult.execute(contextArgs);
            }
            return convertValue(execResult);
        } else if (evalResult.isHostObject()) {
            // 尝试获取底层 Java 对象
            Object hostObject = evalResult.asHostObject();

            if (hostObject instanceof aster.truffle.nodes.LambdaValue lambdaValue) {
                // 直接调用 LambdaValue.apply()
                Object[] args = contextArgs != null ? contextArgs : new Object[0];
                return lambdaValue.apply(args, null);
            } else {
                return hostObject;
            }
        } else {
            // 直接返回结果
            return convertValue(evalResult);
        }
    }

    /**
     * 转换 Truffle Value 为 Java 对象
     */
    private Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }

        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            } else if (value.fitsInLong()) {
                return value.asLong();
            } else if (value.fitsInDouble()) {
                return value.asDouble();
            }
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            Object[] array = new Object[(int) size];
            for (int i = 0; i < size; i++) {
                array[i] = convertValue(value.getArrayElement(i));
            }
            return array;
        }
        if (value.hasMembers()) {
            // 转换为 Java Map，确保可被 Jackson 序列化
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                Value memberValue = value.getMember(key);
                map.put(key, convertValue(memberValue));
            }
            return map;
        }

        // 返回 host object 或尝试转字符串
        if (value.isHostObject()) {
            return value.asHostObject();
        }

        // 无法转换的 Value 类型，返回其字符串表示以避免序列化问题
        LOG.warnf("无法转换 Truffle Value 类型: %s，返回字符串表示", value.getMetaObject());
        return value.toString();
    }

    @PreDestroy
    void cleanup() {
        LOG.info("清理 TrufflePolicyRuntime...");

        if (contextPool != null) {
            contextPool.forEach(Context::close);
            contextPool.clear();
        }

        if (sharedEngine != null) {
            sharedEngine.close();
        }

        LOG.info("TrufflePolicyRuntime 清理完成");
    }
}
