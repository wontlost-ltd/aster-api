#!/usr/bin/env bash
# cedar-java 原生库生产镜像闸门（ADR 0047 §2）：
# 在与 Dockerfile.jvm 相同的基础镜像（glibc）与平台上，用 quarkus-app 产出的依赖 jar 运行 CedarNativeSmoke，
# 输出 CEDAR_OK 即证明原生库可加载；任何加载失败都以非零退出码结束。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PODMAN="${PODMAN:-podman}"
IMAGE="${CEDAR_SMOKE_IMAGE:-azul/zulu-openjdk-debian:25-jre}"
PLATFORM="${CEDAR_SMOKE_PLATFORM:-linux/arm64}"

cd "$ROOT"
./gradlew --offline quarkusBuild testClasses -x test

# 类路径与生产 fast-jar 一致：quarkus-app/lib/main 的依赖 + app 产物，外加测试类中的冒烟入口
CP="/work/build/quarkus-app/lib/main/*:/work/build/quarkus-app/app/*:/work/build/classes/java/test"

"$PODMAN" run --rm --platform "$PLATFORM" \
  -v "$ROOT/build:/work/build:ro" \
  "$IMAGE" \
  sh -c "uname -m; head -1 /etc/os-release; java -version 2>&1 | head -1; java -cp '$CP' io.aster.policy.cedar.CedarNativeSmoke"
