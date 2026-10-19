package io.aster.llm.client;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「(provider, 网关 host, 实际发出的 model) 不接受 temperature」的进程内记忆。
 *
 * <p>新一代模型（如 Anthropic claude-opus-5-5、OpenAI 推理模型）会对 temperature 直接 400。
 * 首次检测到后记住该组合，之后的请求首发就不带 temperature，省掉一次必败的往返。
 *
 * <p>约束：key 只由 provider / host / model 组成，不含 API key 与完整 baseUrl；容量有界（默认 256），
 * 满了淘汰任意一个旧条目——误淘汰的代价只是多一次「失败后重试」，无需 LRU 的复杂度；无 TTL。
 * 读路径无锁，写入（仅在首次检测时发生，极少）串行化以保证容量上界严格成立。
 */
final class TemperatureOmissionMemo {

    static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final ConcurrentHashMap<String, Boolean> omitted = new ConcurrentHashMap<>();

    TemperatureOmissionMemo() {
        this(DEFAULT_CAPACITY);
    }

    TemperatureOmissionMemo(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    static String key(String provider, String host, String model) {
        return provider + "|" + host + "|" + model;
    }

    boolean shouldOmit(String key) {
        return omitted.containsKey(key);
    }

    /** @return 是否为首次记住该 key（调用方据此只打一行 INFO）。 */
    synchronized boolean remember(String key) {
        if (omitted.containsKey(key)) {
            return false;
        }
        evictWhileFull();
        omitted.put(key, Boolean.TRUE);
        return true;
    }

    int size() {
        return omitted.size();
    }

    private void evictWhileFull() {
        Iterator<String> it = omitted.keySet().iterator();
        while (omitted.size() >= capacity && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
