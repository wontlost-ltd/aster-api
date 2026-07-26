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

# ── runtime：distroless static（无 shell/包管理器，最小攻击面）+ 非 root ──
FROM --platform=$TARGETPLATFORM gcr.io/distroless/static-debian12:nonroot
ARG TARGETARCH
# arch 断言：防 buildx/QEMU 跨构建静默产 amd64 却标 arm64 → 节点 exec format error。
# ★distroless 无 shell/uname——改用 Go 二进制自身 GOARCH（编译期锁定）+ 构建期在 builder 校验：
#   builder 与 runtime 同 TARGETARCH，故这里只 COPY 已按 TARGETARCH 编好的二进制。
#   （运行期 arch 由 build-push-action 的 --platform + workflow 的 in-image uname 断言兜底，见 Task 6.2。）
WORKDIR /app
COPY --from=builder /out/launcher /app/launcher
# distroless:nonroot 默认 UID 65532（与 Deployment securityContext runAsUser 65532 一致）。
USER 65532:65532
ENTRYPOINT ["/app/launcher"]
