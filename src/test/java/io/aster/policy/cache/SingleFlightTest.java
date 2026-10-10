package io.aster.policy.cache;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightTest {

    private final SingleFlight<String> flight = new SingleFlight<>();
    private final AtomicInteger executions = new AtomicInteger();

    @Test
    void 在途期间的同键调用共享一次执行() {
        CompletableFuture<String> gate = new CompletableFuture<>();
        Uni<String> first = flight.run("k", () -> pending(gate));
        Uni<String> second = flight.run("k", () -> pending(gate));
        var a = first.subscribeAsCompletionStage();
        var b = second.subscribeAsCompletionStage();

        gate.complete("v");

        assertThat(a.join()).isEqualTo("v");
        assertThat(b.join()).isEqualTo("v");
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void 不同键各自执行() {
        flight.run("a", () -> Uni.createFrom().item(this::count)).await().atMost(Duration.ofSeconds(1));
        flight.run("b", () -> Uni.createFrom().item(this::count)).await().atMost(Duration.ofSeconds(1));
        assertThat(executions.get()).isEqualTo(2);
    }

    @Test
    void 执行结束后再次调用会重新执行且失败不被记住() {
        Uni<String> failing = flight.run("k", () -> Uni.createFrom().failure(() -> {
            executions.incrementAndGet();
            return new IllegalStateException("boom");
        }));
        assertThatThrownBy(() -> failing.await().atMost(Duration.ofSeconds(1))).hasMessage("boom");

        String value = flight.run("k", () -> Uni.createFrom().item(this::count)).await().atMost(Duration.ofSeconds(1));

        assertThat(value).isEqualTo("2");
        assertThat(executions.get()).isEqualTo(2);
    }

    private Uni<String> pending(CompletableFuture<String> gate) {
        return Uni.createFrom().completionStage(() -> {
            executions.incrementAndGet();
            return gate;
        });
    }

    private String count() {
        return String.valueOf(executions.incrementAndGet());
    }
}
