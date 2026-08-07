package io.aster.policy.rest;

import io.smallrye.mutiny.Uni;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 并发闸门许可的一次性租约：保证「恰好归还一次」。
 *
 * <p><b>为什么要抽成一个类</b>：许可在**请求线程**取得、却要在 **worker 线程**归还，
 * 中间隔着 Mutiny 的惰性订阅边界。这段生命周期此前内联在 REST 方法里，
 * 而那个方法需要注入 {@code jaxrsCtx} 等一堆依赖、必须 {@code @QuarkusTest} + 真库
 * 才跑得起来——于是测试只能在测试文件里**手写一份副本**来测，
 * 生产逻辑整条失效也不报红（实测：让归还永不执行，13 条测试全绿）。
 *
 * <p>抽出来之后，生产与测试**共用同一份实现**，纯 JUnit 即可覆盖全部路径。
 *
 * <h2>三条归还路径</h2>
 * <ol>
 *   <li><b>准备失败</b>——acquire 之后、supplier 之前的同步代码抛出</li>
 *   <li><b>worker 结束</b>——supplier 真正跑完或抛异常（{@code finally}）</li>
 *   <li><b>调度被拒</b>——worker 池饱和，supplier <b>永不执行</b></li>
 * </ol>
 *
 * <p>第 3 条最容易漏：{@code runSubscriptionOn} 只是**装配** Uni，真正的
 * {@code executor.execute} 发生在稍后的订阅阶段，那时 REST 方法早已返回。
 * Mutiny 自己捕获拒绝并转成 Uni failure（见 {@code UniRunSubscribeOn#subscribe}），
 * 所以任何写在 REST 方法里的同步 {@code catch} 都<b>不可能</b>收到它。
 * 后果比取消绕过更糟：单向累积、不可恢复，最终整站 503。
 *
 * <h2>为什么是 onFailure 而不是 onTermination</h2>
 *
 * <p>{@code onTermination} 在**取消**时也触发，而取消时 supplier 往往仍在烧 CPU。
 * 提前归还等于让闸门可被「反复发起再取消」绕过——那正是这条闸门要防的攻击。
 * {@code onFailure} 只在真失败时触发，取消不触发。
 *
 * <h2>为什么用 CAS</h2>
 *
 * <p>路径 2 与 3 可能**先后都触发**（worker 抛异常时 finally 先跑、onFailure 后跑）。
 * CAS 让后到者成为 no-op：既不泄漏，也不双重释放——
 * 双重释放会让许可凭空变多，把闸门上限悄悄抬高，比泄漏更隐蔽。
 */
final class PermitLease {

    private final Semaphore permits;
    private final AtomicBoolean released = new AtomicBoolean(false);

    private PermitLease(Semaphore permits) {
        this.permits = permits;
    }

    /** 包装一个**已经取得**的许可。调用方负责先 acquire 成功。 */
    static PermitLease of(Semaphore permits) {
        return new PermitLease(permits);
    }

    /** 归还许可；重复调用是 no-op。三条路径共用此方法。 */
    void release() {
        if (released.compareAndSet(false, true)) {
            permits.release();
        }
    }

    /** 仅供测试断言：许可是否已归还。 */
    boolean isReleased() {
        return released.get();
    }

    /**
     * 在租约保护下同步执行 {@code work}，覆盖「准备失败」这条路径。
     *
     * <p>{@code work} 抛出时归还许可并原样抛出——用于 acquire 之后、
     * 切到 worker 之前的准备工作。
     */
    <T> T guardSetup(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException | Error setupFailure) {
            release();
            throw setupFailure;
        }
    }

    /**
     * 把 supplier 包装成受租约保护的 Uni，覆盖「worker 结束」与「调度被拒」两条路径。
     *
     * @param work     同步业务逻辑，将在 {@code executor} 上执行
     * @param executor worker 线程池
     */
    <T> Uni<T> guardAsync(Supplier<T> work, java.util.concurrent.Executor executor) {
        return Uni.createFrom().<T>item(() -> {
            try {
                return work.get();
            } finally {
                // 路径2：worker 真正跑完（正常或异常）。
                // 取消**不会**触发这里——不归还一个仍在烧 CPU 的 worker 的许可。
                release();
            }
        }).runSubscriptionOn(executor)
            // 路径3：调度被拒时 supplier 永不执行，上面的 finally 永不触发。
            // 只能在这里兜底。用 onFailure 而非 onTermination：后者在取消时也会触发。
            .onFailure().invoke(t -> release());
    }
}
