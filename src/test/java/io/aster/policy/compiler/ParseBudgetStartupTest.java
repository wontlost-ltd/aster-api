package io.aster.policy.compiler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code aster.parse.time-budget} 的启动期校验：负值与超过 30s 拒绝启动，0 为不限时。 */
class ParseBudgetStartupTest {

    @Test
    void acceptsZeroDefaultAndUpperBound() {
        assertEquals(Duration.ZERO, ParseBudgetStartup.validate(Duration.ZERO));
        assertEquals(Duration.ofMillis(500), ParseBudgetStartup.validate(Duration.ofMillis(500)));
        assertEquals(Duration.ofSeconds(30), ParseBudgetStartup.validate(Duration.ofSeconds(30)));
    }

    @Test
    void rejectsNegativeAndAboveUpperBound() {
        assertThrows(IllegalStateException.class, () -> ParseBudgetStartup.validate(Duration.ofMillis(-1)));
        assertThrows(IllegalStateException.class, () -> ParseBudgetStartup.validate(Duration.ofSeconds(31)));
        assertThrows(IllegalStateException.class, () -> ParseBudgetStartup.validate(null));
    }
}
