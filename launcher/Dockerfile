# runner-launcher 镜像（S2-1a-2 Slice-2b-launch）：multi-stage Go 构建 → distroless static 运行时。
# ★集群节点 ARM64（OCI Ampere），必须真 arm64（承 runner/Dockerfile arch 断言纪律，防 QEMU 误标）。

# ── builder：编 launcher 静态二进制 ──
FROM --platform=$BUILDPLATFORM golang:1.25-alpine AS builder
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
# 先拷 go.mod/go.sum 单独缓存依赖层（源码变更不失效依赖缓存）。
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# CGO_ENABLED=0 静态编译（distroless static 无 libc）；-trimpath 去构建路径；交叉编到 TARGETARCH。
RUN set -eux; \
    CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} \
    go build -trimpath -ldflags="-s -w" -o /out/launcher ./cmd/launcher

# ★构建期 arch 断言（内容级，非元数据级）：读**二进制自身**记录的 GOARCH 并与
#   TARGETARCH 比对。这是本文件下方注释一直声称"构建期在 builder 校验"、但此前
#   **并不存在**的那一步。
#   为何必需：TARGETARCH 若为空（未经 buildx / --platform 的裸 docker build），
#   go build 会静默按宿主 arch（amd64）编译，而镜像 manifest 仍可被标成 arm64
#   → 上 ARM 节点 exec format error → CrashLoopBackOff（本仓踩过的真实生产事故）。
#   workflow 侧只校验 OCI manifest 的 architecture 字段，而该字段正是兄弟
#   Dockerfile 明确指出"会撒谎"的东西；distroless 运行时又无 shell/uname 可供
#   in-image 断言。故唯一可靠的拦截点就是这里——趁 builder 还有完整 Go 工具链时，
#   直接问二进制自己是给哪个架构编的。
#   实现细节：`go version -m` 会输出制表符缩进的 `build\tGOARCH=arm64` 行。用
#   awk 精确匹配字段而非模糊 grep——同一段输出里还有 `GOARM64=v8.0` 等形近键，
#   宽松正则会误取。
#   ★覆盖边界（诚实说明，勿高估）：本断言拦两种失效——(a) TARGETARCH 为空（裸
#   docker build 未经 buildx，go 会按宿主 arch 静默编译）；(b) 二进制实际 GOARCH
#   与 TARGETARCH 不符（QEMU 异常、构建缓存串味等致 GOARCH 未真正生效）。
#   它**不**拦"buildx 把正确的 arm64 二进制推到错误 manifest 平台"——那属于推送
#   层，由 workflow 的单平台 push + manifest 校验负责。二者互补，缺一不可。
RUN set -eux; \
    test -n "${TARGETARCH}" || { echo "TARGETARCH 为空：必须经 buildx/--platform 构建，否则会静默编成宿主 arch"; exit 1; }; \
    actual="$(go version -m /out/launcher | awk -F= '$1 ~ /(^|[[:space:]])GOARCH$/ {print $2; exit}')"; \
    echo "binary GOARCH=[${actual}] TARGETARCH=[${TARGETARCH}]"; \
    test -n "${actual}" || { echo "无法从二进制读出 GOARCH（go version -m 输出格式异常）"; exit 1; }; \
    test "${actual}" = "${TARGETARCH}" || { echo "arch 不符：二进制实际=${actual} 目标=${TARGETARCH}"; exit 1; }

# ── runtime：distroless static（无 shell/包管理器，最小攻击面）+ 非 root ──
FROM --platform=$TARGETPLATFORM gcr.io/distroless/static-debian12:nonroot
ARG TARGETARCH
# arch 断言：防 buildx/QEMU 跨构建静默产 amd64 却标 arm64 → 节点 exec format error。
# ★distroless 无 shell/uname，运行期无法 in-image 断言——故校验前移到 builder 阶段，
#   由上面的 `go version -m` 内容级断言完成（读二进制自身记录的 GOARCH 与 TARGETARCH
#   比对）。builder 与 runtime 同 TARGETARCH，故这里只 COPY 已校验过的二进制。
#   注：此处原注释声称"构建期在 builder 校验"，但该校验此前**并不存在**（builder 段
#   只有 go build）；workflow 侧亦仅读 OCI manifest 的 architecture 字段——正是兄弟
#   Dockerfile 明确指出"会撒谎"的那个字段。2026-07-30 审计补上真实校验。
WORKDIR /app
COPY --from=builder /out/launcher /app/launcher
# distroless:nonroot 默认 UID 65532（与 Deployment securityContext runAsUser 65532 一致）。
USER 65532:65532
ENTRYPOINT ["/app/launcher"]
