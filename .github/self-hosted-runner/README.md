# 本地 Linux self-hosted runner（Podman）

给 `wontlost-ltd` org 提供一个 **arm64 Linux** 容器化 runner，让 CI 优先跑在本机、
不消耗 GitHub 托管额度；本机不可用时 CI 自动回落托管 runner。

## 为什么必须是 Linux，而不是复用已有的 macOS runner

`aster-api` 的 `ci` / `deploy` / `nightly` / `perf` / `aster-replay-runner-deploy`
五个 workflow 都用了 `services:`（Postgres/Redis）。`services:` 依赖 Linux 容器网络，
**macOS runner 不支持**。按实测用量拆分：

| | 月度 min | 占比 |
| --- | --- | --- |
| 需 `services:`（只能 Linux） | ~7,073 | 40% |
| 不需 `services:`（macOS 可跑） | ~10,487 | 60% |

只用 macOS runner 承接 60%，托管仍需 ~7,073 min/月，超 Team 额度（3,000）2.4 倍。
盈亏线是 self-hosted 承担 **≥83%**，所以必须有 Linux runner。

**容器里跑不了 macOS**：容器共享宿主内核，Podman 在 macOS 上本身就先起一个 Linux VM。
Docker Hub 上名为 `macos` 的镜像实为「Linux 容器内用 QEMU 启动 macOS 虚拟机」
（实测其 config 为 `os=linux` / `architecture=amd64`，且带 `RAM_SIZE`/`CPU_CORES` 字段），
在 Apple Silicon 上等于"Linux VM → amd64 模拟 → 再模拟 macOS"，性能不可用，
且 Apple EULA 对 macOS 虚拟化有硬性限制。

## 架构

```
macOS 宿主
└── podman machine（applehv，Linux arm64 VM）
    ├── runner 容器（本目录 Dockerfile；--ephemeral，跑完一个 job 即注销退出）
    │     └── 挂载 /run/podman/podman.sock → DOCKER_HOST
    └── services:/Testcontainers 的容器（由宿主 Podman 创建，sibling 模式）
```

**sibling 而非嵌套**：runner 容器内不跑第二个 daemon，而是挂宿主 socket，让
`services:` 与 Testcontainers 的容器都由宿主 Podman 创建。已实测容器内
`docker version` 得到 `server=5.1.2` 并能起兄弟容器。

## 前置

- Podman machine 在跑：`podman machine list`（本机名为 `podman-machine`，**不是**默认名，
  用 `podman machine ssh podman-machine …`）
- rootful socket 已启用：`sudo systemctl is-active podman.socket` → `active`

## 构建镜像

```bash
tar czf /tmp/ctx.tgz Dockerfile entrypoint.sh
podman machine ssh podman-machine 'mkdir -p ~/runner-build'
cat /tmp/ctx.tgz | podman machine ssh podman-machine \
  'cat > ~/ctx.tgz && tar xzf ~/ctx.tgz -C ~/runner-build'
podman machine ssh podman-machine \
  'cd ~/runner-build && sudo podman build -t localhost/aster-runner:2.336.0 .'
```

runner tarball 的 sha256 已在 Dockerfile 里固定，且与 `actions/runner` release
公布值逐字比对一致；校验失败即中止构建。

## 运行

```bash
export RUNNER_PAT=<有 admin:org 的 PAT>   # 只用于换取短时注册令牌，不落盘
./run-runner.sh
```

守护循环：每轮拉起一个 `--ephemeral` 实例，跑完一个 job 即自动从 org 注销并退出，
随后拉起全新实例。**好处**是 job 之间不共享文件系统残留（self-hosted runner 最大的
固有风险就是状态跨 job 残留）；代价是每个 job 前约 10s 注册开销，相对 api CI 的
~12 分钟可忽略。

停止：`Ctrl-C`，或
`podman machine ssh podman-machine 'sudo podman rm -f aster-linux-runner'`。

## CI 侧如何选 runner

`ci.yml` 是调用方，步骤定义在 `ci-build.yml`（reusable），两条路径共用同一份：

1. `pick-runner` —— 托管上跑几秒，用 `.github/actions/pick-runner` 查 org runner
   是否有 online 且非 busy、标签全覆盖的实例。
2. `build-local` —— 判定 local 时跑在本机。
3. `build-hosted` —— `always() && needs.build-local.result != 'success'`，覆盖
   ①probe 判本地不可用（build-local 被 skip）②本地在线但 job 真的挂了。
4. `ci-result` —— 汇总成单一结论供分支保护用（两条互斥分支都可能 skipped，
   skipped 不能直接作 required check）。

**为什么需要 probe**：GitHub 没有原生的"本地优先否则托管"。只写
`runs-on: [self-hosted, …]` 时 runner 离线会让 job 无限排队（默认 24h 超时）而不回落。

`RUNNER_PROBE_TOKEN`（org secret，fine-grained 只需 self-hosted runners: read）
未配置时 probe 一律回落托管——功能不坏，只是省不到额度。

## 安全

- **仅限私有仓**：`local-mac` runner group 设了 `allows_public_repositories: false`。
  public 仓 + self-hosted runner 意味着任意外部人员 fork 提 PR 就能在你的机器上
  执行任意代码，且 self-hosted 不像托管那样每次销毁。GitHub 官方明确不建议。
- `--ephemeral` + 每轮重建，限制状态跨 job 残留。
- 容器以 root 运行是为了读挂载进来的宿主 podman socket（`root:root` mode 660）；
  这是本地开发机上的 runner，且仓库转私后无外部 fork PR 可触达。
- 注册令牌短时（1h）；长期 PAT 只在启动瞬间用于换取令牌，不写入镜像或文件。

## 开机自启（launchd）

```bash
cd .github/self-hosted-runner

# 1) 把 PAT 存进 login keychain（需 admin:org；仅用于换取短时注册令牌）
read -rs "P?粘贴 PAT: " && echo && printf '长度=%s\n' "${#P}" && \
  security add-generic-password -a "$USER" -s aster-runner-pat -U -w "$P" && \
  unset P
#    ★先打印长度再写入：确认粘贴真的生效。
#    ★不要用裸 `security add-generic-password ... -w`（不带值）——它会连续打印
#      "password data for new item: retype password for new item:" 挤在同一行，
#      极易误以为已输入而实际存进**空值**；此时 keychain 项存在、读取 exit=0、
#      但值长度为 0，守护脚本只能报"拿不到 PAT"。实测踩过一次。
#    ★也不要用管道（`printf ... | security ... -w`）——`-w` 不读 stdin，仍走交互
#      两次确认，管道内容只被当作第一次输入，报 "passwords don't match"。
#      唯一可靠的非交互写法是 `-w "$VALUE"` 把值作为参数（代价：短暂出现在 ps 里）。
#    重存/覆盖用 -U。

# 2) 安装 LaunchAgent
cp com.wontlost.aster-runner.plist ~/Library/LaunchAgents/
/usr/bin/sed -i '' "s#__SUPERVISOR__#$PWD/launchd-supervisor.sh#" \
  ~/Library/LaunchAgents/com.wontlost.aster-runner.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.wontlost.aster-runner.plist

# 3) 确认
launchctl print gui/$(id -u)/com.wontlost.aster-runner | grep -E 'state|pid'
tail -f ~/Library/Logs/aster-runner.log
```

卸载：

```bash
launchctl bootout gui/$(id -u)/com.wontlost.aster-runner
rm ~/Library/LaunchAgents/com.wontlost.aster-runner.plist
```

### 设计要点（都是实测踩出来的）

- **用 LaunchAgent（`gui/<uid>`）而非 LaunchDaemon**：podman machine 是**用户级**资源
  （VM 与 socket 都在 `~/.local/share/containers` 下），以 root 跑 LaunchDaemon 看不到
  当前用户的 machine。代价是需用户登录后才启动——对开发机可接受，也更安全（不给 root）。
- **`PATH` 必须含 `/opt/podman/bin`**：本机 podman 由 Podman.app 安装器装在那里，
  **不是** Homebrew 的 `/opt/homebrew/bin`。launchd 的默认 PATH 极窄
  （`/usr/bin:/bin:/usr/sbin:/sbin`），写错会导致开机后 `command not found` 而**静默失败**。
  故脚本开头显式断言 podman 可用并打印实际 PATH——fail-loud 而非空日志。
- **守护脚本自己启 podman machine**：实测本机没有 podman 的 LaunchAgent，machine 只在
  手动或 Podman Desktop 启动时才跑，开机后默认是停的。不自己拉起就每次开机都失败。
- **keychain 可非交互读取**：已用测试项验证 `security find-generic-password` 在
  剥离环境（`env -i`）下能直接返回值，不弹解锁框——这是本方案成立的前提。
- **指数退避封顶 300s**：连续失败时逐步拉长间隔（10→20→…→300），避免开机瞬间或
  GitHub API 故障时疯狂重试刷爆日志/触发限流。
- 缺 keychain 项时日志给出**可直接复制的修复命令**，而不是含糊报错。
