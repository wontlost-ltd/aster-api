package io.aster.policy.cache;

import io.smallrye.mutiny.Uni;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 同键并发调用合并为一次执行（single-flight）：执行期间到达的调用共享同一结果或失败。
 *
 * <p>用于冷缓存下的突发请求：若每个请求各自查库 / 编译，会各开一个事务、各占一条 JDBC
 * 连接重复同一工作，把连接池占满；调用方超时放弃后这些任务仍在排队占池，拖垮之后的请求。
 * 执行结束（成功或失败）即移出在途表，结果本身不在此缓存，失败也不会被记住。
 * 订阅者在完成执行的那个线程上收到结果，下游需要自行分派线程。
 */
public final class SingleFlight<T> {

    private final ConcurrentHashMap<String, Uni<T>> inFlight = new ConcurrentHashMap<>();

    /** 返回该键在途的执行；无在途执行时以 work 发起一次新的执行。 */
    public Uni<T> run(String key, Supplier<Uni<T>> work) {
        // 语句块 lambda 选中 eventually(Runnable)：表达式 lambda 会返回被移除的 Uni，
        // 匹配到 eventually(Supplier<Uni>) 而去等待自身，造成死锁。
        return inFlight.computeIfAbsent(key, k -> work.get()
            .eventually(() -> {
                inFlight.remove(k);
            })
            .memoize().indefinitely());
    }
}
