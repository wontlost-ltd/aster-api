package io.aster.policy.revalidation;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class StartupRevalidationTrigger {
    @Inject RevalidationRunService runs;
    @ConfigProperty(name = "aster.revalidation.enabled", defaultValue = "true") boolean enabled;

    void onStart(@Observes StartupEvent event) {
        if (enabled) runs.enqueueIfToolchainChanged();
    }
}
