package io.aster.policy.stability;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/** 在启动时确认发布环境使用了完整的工具链身份。 */
@ApplicationScoped
public class ToolchainIdentityGuard {
    private static final Logger LOG = Logger.getLogger(ToolchainIdentityGuard.class);

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String profile;

    @ConfigProperty(name = "aster.toolchain.require-release-identity", defaultValue = "false")
    boolean requireReleaseIdentity;

    @Inject
    ToolchainIdentityProvider provider;

    void onStart(@Observes StartupEvent event) {
        check(provider.currentToolchainId(), profile, requireReleaseIdentity);
    }

    void check(String identity, String activeProfile, boolean strict) {
        List<String> missing = missingSegments(identity);
        if (missing.isEmpty()) return;
        String message = "工具链身份包含开发占位值: " + String.join(", ", missing);
        if (strict && "prod".equals(activeProfile)) throw new IllegalStateException(message);
        LOG.warn(message);
    }

    static List<String> missingSegments(String identity) {
        List<String> missing = new ArrayList<>();
        for (String segment : identity.split(";")) {
            String[] pair = segment.split("=", 2);
            if (pair.length == 2 && "dev".equals(pair[1])) missing.add(pair[0]);
        }
        return missing;
    }
}
