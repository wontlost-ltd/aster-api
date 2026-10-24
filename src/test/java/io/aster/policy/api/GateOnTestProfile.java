package io.aster.policy.api;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** %test 默认关闭执行门禁（种子只有占位源码）；门禁 IT 用本 profile 显式开启。 */
public class GateOnTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("aster.compile.gate", "on");
    }
}
