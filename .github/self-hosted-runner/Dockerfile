# 本地 Linux 容器化 self-hosted runner（arm64）
#
# 为什么要 Linux 而不是复用已有的 macOS runner：aster-api 的 ci/deploy/nightly/perf/
# replay-runner 五个 workflow 都用了 `services:`（Postgres/Redis），而 `services:` 依赖
# Linux 容器网络，**macOS runner 不支持**。实测负载拆分：需 services 的占 40%
# (~7073 min/月)，只靠 macOS runner 承接剩余 60% 时托管额度仍超 2.4 倍。故必须有
# Linux runner 才能把 self-hosted 占比推到盈亏线（≥83%）之上。
#
# 容器里跑不了 macOS——容器共享宿主内核，Podman 在 macOS 上本身就先起一个 Linux VM。
# 那些名为 "macos" 的镜像实为「Linux 容器内 QEMU 启动 macOS 虚拟机」（实测其 config
# 为 os=linux/arch=amd64 且带 RAM_SIZE/CPU_CORES 字段），性能与 EULA 均不可取。
#
# Testcontainers / services 的容器由**宿主 Podman** 创建（挂 socket 的 sibling 模式），
# 不在本容器内嵌套 daemon——已实测容器内经 socket 起兄弟容器成功（server=5.1.2）。

FROM docker.io/library/ubuntu:24.04

ARG RUNNER_VERSION=2.336.0
# 官方发布的 linux-arm64 校验和（已与 actions/runner release body 公布值逐字比对一致）
ARG RUNNER_SHA256=58b758e420b87093fbd4bfddd368074960053e2f1388f01848c82624b90f27d1

# ★LANG/LC_ALL 必须设成 UTF-8（2026-07-30 实测）：Ubuntu 精简镜像默认 LANG 为空、
#   LC_CTYPE=POSIX，JVM 因此按 ASCII 处理文件名。本仓有大量中文测试方法名
#   （如 parseError_应返回ERROR_且脱敏不回显provider_message），Gradle 写 HTML 测试报告时
#   会报 `Malformed input or input contains unmappable characters` 并让 :test 任务失败——
#   注意失败发生在**报告生成阶段**，测试本身已跑完，极具迷惑性（日志里看不到任何
#   FAILED 用例，只有 "Task :test FAILED"）。托管 runner 默认 C.UTF-8 故无此问题。
#   镜像内已有 C.utf8（locale -a 可见），直接用它，无需装 locales 包。
ENV DEBIAN_FRONTEND=noninteractive \
    RUNNER_ALLOW_RUNASROOT=1 \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    DOCKER_HOST=unix:///var/run/docker.sock

# runner 自身依赖 + workflow 常用工具。
# ★docker-cli（非 daemon）：workflow 里的 docker build/run 经挂载的宿主 socket 执行。
# ★postgresql-client：ci.yml 有 `psql -h localhost` 建 aster_test 库的步骤。
# ★jq/yq 由各 workflow 自行按 pin 安装，这里不预置以免版本漂移。
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl git jq sudo unzip zip tar gzip xz-utils \
        build-essential libicu74 libssl3 \
        python3 python3-venv \
        postgresql-client \
        docker.io \
        uidmap \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /actions-runner

# 下载 + 校验和固定（供应链：失败即中止，不静默继续）
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    test "$arch" = "arm64" || { echo "本镜像仅面向 arm64（宿主为 Apple Silicon）"; exit 1; }; \
    curl -fsSL -o runner.tar.gz \
      "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-arm64-${RUNNER_VERSION}.tar.gz"; \
    echo "${RUNNER_SHA256}  runner.tar.gz" | sha256sum -c -; \
    tar xzf runner.tar.gz; \
    rm runner.tar.gz; \
    ./bin/installdependencies.sh

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# 以 root 运行：需要读挂载进来的宿主 podman socket（root:root mode 660）。
# 这是本地开发机上的 runner，且仓库转私后无外部 fork PR 可触达（见 README 的安全说明）。
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
