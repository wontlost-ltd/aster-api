package io.aster.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemperatureOmissionMemoTest {

    @Test
    @DisplayName("记住后同 key 命中，其他 key 不命中；重复 remember 返回 false")
    void rememberAndLookup() {
        TemperatureOmissionMemo memo = new TemperatureOmissionMemo();
        assertThat(memo.shouldOmit("anthropic|api.anthropic.com|m1")).isFalse();
        assertThat(memo.remember("anthropic|api.anthropic.com|m1")).isTrue();
        assertThat(memo.remember("anthropic|api.anthropic.com|m1")).isFalse();
        assertThat(memo.shouldOmit("anthropic|api.anthropic.com|m1")).isTrue();
        assertThat(memo.shouldOmit("anthropic|api.anthropic.com|m2")).isFalse();
    }

    @Test
    @DisplayName("★有界：写入远超容量后 size 不超过上限，且最新写入的 key 可命中")
    void bounded() {
        TemperatureOmissionMemo memo = new TemperatureOmissionMemo(16);
        for (int i = 0; i < 1000; i++) {
            memo.remember("p|h|model-" + i);
            assertThat(memo.size()).isLessThanOrEqualTo(16);
        }
        assertThat(memo.shouldOmit("p|h|model-999")).isTrue();
    }

    @Test
    @DisplayName("默认容量为 256")
    void defaultCapacity() {
        TemperatureOmissionMemo memo = new TemperatureOmissionMemo();
        for (int i = 0; i < 600; i++) {
            memo.remember("k" + i);
        }
        assertThat(memo.size()).isEqualTo(TemperatureOmissionMemo.DEFAULT_CAPACITY).isEqualTo(256);
    }
}
