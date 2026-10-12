package io.aster.policy.cedar;

import java.util.List;
import java.util.Map;

/**
 * 原生库冒烟入口：仅供 scripts/ci/cedar-native-smoke.sh 在生产基础镜像内运行，
 * 证明 cedar-java 原生库可在该镜像加载并完成一次授权；成功时输出 CEDAR_OK。
 */
public final class CedarNativeSmoke {

    private CedarNativeSmoke() {
    }

    public static void main(String[] args) {
        String policies = "@id(\"s#1\")\npermit (principal, action, resource);\n";
        var request = new CedarEngine.CedarRequest("p", "agent", List.of(), "read", "doc", "d1", Map.of());
        var decision = new CedarEngine().authorize(policies, List.of("s#1"), request);
        if (!decision.allow() || !decision.determiningPolicyIds().equals(List.of("s#1"))) {
            System.err.println("CEDAR_FAIL " + decision);
            System.exit(1);
        }
        System.out.println("CEDAR_OK");
    }
}
