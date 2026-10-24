package io.aster.policy.revalidation;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class StartupRevalidationTriggerIT {
    @ConfigProperty(name = "aster.revalidation.enabled") boolean enabled;

    @Test void testProfileDisablesStartupRevalidationByDefault() { assertFalse(enabled); }
}
