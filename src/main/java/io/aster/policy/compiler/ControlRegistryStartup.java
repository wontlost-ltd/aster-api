package io.aster.policy.compiler;

import aster.core.typecheck.governance.ControlRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/** 启动即加载控制注册表：缺失或不合法让启动失败，而不是让治理检查静默失效（ADR 0045 §2.2）。 */
@ApplicationScoped
public class ControlRegistryStartup {
    private static final Logger LOG = Logger.getLogger(ControlRegistryStartup.class);

    void onStart(@Observes StartupEvent event) {
        ControlRegistry registry = ControlRegistry.loadDefault();
        LOG.infof("控制注册表 %s 已加载（%d 个控制点）", registry.version(), registry.keys().size());
    }
}
