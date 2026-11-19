# 策略包导出签名密钥运维手册

适用对象：aster-api 的 B 形态策略包导出（ADR 0059 §6.2，修订 3 P2-R2 / P2-R3）。服务用 Ed25519 导出密钥给包内
`manifest.json` 签名（写成 `manifest.sig.json`），`GET /api/v1/exports/keys` **匿名**发布验签公钥（JWKS）与配置 `revision`，
供 CLI 的 `--jwks <url>` 使用。本手册只描述操作步骤，不含任何私钥。导出密钥与 Guard 执行收据密钥
（`docs/runbooks/guard-receipt-signing-keys.md`）**相互独立**，配置形状与解析器相同。

## 1. 配置模型

| 项 | 位置 | 说明 |
| --- | --- | --- |
| `signing-keys` | Vault `apps/aster-api-export-signing` 属性 `signing-keys` → ExternalSecret `aster-api-export-signing-keys` → env `ASTER_EXPORT_SIGNING_KEYS` | JSON：`{"revision":N,"keys":[{"kid","privateKey","notAfter"?,"revoked"?}]}` |
| `active-kid` | Vault 同路径属性 `active-kid` → env `ASTER_EXPORT_ACTIVE_KID` | 当前用于签名的 kid，必须在 `keys` 中 |
| 锁文件 | 仓内 `src/main/resources/export/export-runtimes.lock.json` | 每个 target 的 CLI 版本与**内置信任锚 kid**（`trustAnchorKids`）；在列车发布之后由单独的 PR 更新（可以落后于生态 pin，不得领先） |
| `allow-unsigned` | env `ASTER_EXPORT_PACKAGE_ALLOW_UNSIGNED`（缺省 `false`） | **只供 on-prem**：为 `true` 且未配置密钥时导出无签名包。只在 `aster.plan-gate.enabled=false`（无 cloud 计划门的 on-prem）时被接受；云上部署同时打开即启动失败 |

- `privateKey`：32 字节 Ed25519 种子的 base64url（无填充，43 字符）。
- kid：不超过 64 字符，**不得以 `test-` 开头**（prod profile 下启动失败；conformance `keys/README.md` M4）。
- 两项 env 都缺省：Pod 照常启动，导出返回 503 `export_signing_unavailable`（导出是可选功能，镜像可以先于 Vault 上线）。
- **只给了其中一项、JSON 畸形、未知字段、重复键、kid 重复、种子长度错误、active kid 不在列表 / 已 revoked / 已过 notAfter：Pod 启动失败**；报错信息不含种子。
- ExternalSecret 与两个 `secretKeyRef` 都是 `optional: true`：Vault 未写入时 Secret 不存在，env 缺省，Pod 照常启动。
- Pod 的 env 在容器启动时快照，**任何密钥变更都要 `kubectl -n aster-cloud rollout restart deployment/aster-api`**（并等 `rollout status` 完成）。

### revision 规则（强制）

**每一次 `signing-keys` 配置变更都必须让 `revision` 增加。** 服务启动时把 `revision` 写入 `export_signing_key_revisions`
（V6.40.0），低于库中已记录的最大值则**拒绝启动**（「导出签名密钥配置 revision 回退」），防止回滚到旧密钥集让已撤销的 key 复活。

### 签名前的三道检查（都在组装 zip 之前，失败时不写审计）

1. target 在锁文件中为 `null`（如 P2 首发时的 `node`）→ 503 `export_runtime_unavailable`。
2. 未配置密钥 → 503 `export_signing_unavailable`（on-prem 显式 `allow-unsigned=true` 时改为无签名导出）。
3. `active-kid` 不在该 target 的 `trustAnchorKids` → 503 `export_signing_kid_unanchored`。

第 3 条把 ADR §6.2「先发布锚，再签名」变成机械检查：用户机器上的 CLI 只认它**内置**的锚；用一个 CLI 还不认识的 kid 签出的包，
离线 `aster verify` 必然失败，所以平台宁可拒绝导出。

## 2. 生成密钥（在操作员本机执行）

私钥种子只在本机终端和 Vault 中出现，不要写入仓库、工单或聊天记录。

需要 **OpenSSL 3.x**。macOS 自带的是 LibreSSL，会报 `Algorithm ed25519 not found`；请先 `brew install openssl@3`，
并用 `/opt/homebrew/opt/openssl@3/bin/openssl` 替换下面命令里的 `openssl`。先用 `openssl version` 确认输出以 `OpenSSL 3` 开头。

```bash
SEED=$(openssl genpkey -algorithm ed25519 -outform DER \
  | tail -c 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')
[ "${#SEED}" -eq 43 ] && echo ok   # 不是 ok 就重新生成；不要 echo "$SEED"
```

kid 用 `export-YYYY-MM` 加序号字母（如 `export-2026-11a`）。

### 写入 Vault 的是 JSON，不是裸种子

> 教训：Guard 收据密钥首发时曾把**裸种子**写进 `signing-keys`，新 Pod 解析失败 CrashLoopBackOff。导出密钥同样
> **配置畸形即启动失败**（只有「两项都缺省」才是安全的降级），所以必须用 `jq` 生成完整 JSON 并先核对结构。

```bash
umask 077
KEYS_FILE=$(mktemp)
# 首次部署（revision 1）
SEED="$SEED" jq -n '{revision: 1, keys: [{kid: "export-2026-11a", privateKey: env.SEED}]}' > "$KEYS_FILE"
# 轮换：在现有配置上追加并递增 revision（旧种子同样只经文件流转）
#   vault kv get -field=signing-keys apps/aster-api-export-signing > "$KEYS_FILE.old"
#   SEED="$SEED" jq '.revision += 1 | .keys += [{kid: "export-2027-05a", privateKey: env.SEED}]' "$KEYS_FILE.old" > "$KEYS_FILE"
jq '{revision, kids: [.keys[].kid], seedLengths: [.keys[].privateKey | length]}' "$KEYS_FILE"   # 期望 seedLengths 全为 43
vault kv put apps/aster-api-export-signing signing-keys=@"$KEYS_FILE" active-kid=export-2026-11a
for f in "$KEYS_FILE" "$KEYS_FILE.old"; do [ -f "$f" ] && { shred -u "$f" 2>/dev/null || rm -P "$f" 2>/dev/null || rm -f "$f"; }; done
unset SEED KEYS_FILE
```

`vault kv put` 会覆盖该路径的全部属性，所以 `active-kid` 必须同一条命令一起写。

**公钥**（JWK 的 `kid` 与 `x`，非机密）交给工程侧写入两个 CLI 的内置锚（aster-lang-ts `src/policy-package/trust/export-jwks.json`
与 aster-lang-truffle `cli/src/main/resources/aster/cli/export-jwks.json`，计划 T25）。公钥可由种子推导，也可在部署后从
`/api/v1/exports/keys` 读取。

## 3. 首次部署（revision 1，kid `export-2026-11a`）

首次上线时**导出一定先 503 `export_signing_kid_unanchored`，这是预期的 fail-closed**：

- 仓内锁文件首个内容为 `{"formatVersion":1,"jre":{"cliName":"cloud.aster-lang:aster-cli","cliVersion":"1.0.49","trustAnchorKids":[]},"node":null}`。
- 已发布的 CLI（jre 1.0.49 / ts 3.2.0）内置锚里**没有** `export-2026-11a`，所以锁文件也不列它；此时就算生产已配置该密钥，
  jre 导出返回 503 `export_signing_kid_unanchored`，node 导出返回 503 `export_runtime_unavailable`。
- 列车 eco-1.0.50（含 T25：两个 CLI 内置 `export-2026-11a` 的公钥）在 L4 自动合入的 aster-api pin PR **只改生态 pin**，不碰锁文件；
  此时 aster-api 运行 1.0.50 引擎、锁文件仍为 1.0.49，属预期的「落后」：启动照常，只记一条 WARN「导出锁文件 jre.cliVersion=1.0.49 落后于引擎版本 1.0.50」，
  导出仍 503 `export_signing_kid_unanchored`。
- 列车之后，T15 以**单独的 PR** 把锁文件 bump 为 `"jre":{"cliVersion":"1.0.50","trustAnchorKids":["export-2026-11a"]}`（node 一项随 ts 3.3.x 补上），
  aster-api 部署后导出才可用。锁文件的 `cliVersion` 只能等于或落后于生态 pin / 运行中的引擎，**领先即启动失败**（`ExportRuntimesTest.jreCliVersionNotNewerThanEcosystemPin` 在 PR CI 拦下）。
- A6 的锁文件：`jre` 为 `1.0.50`、`node` 为 `@aster-cloud/aster-lang-ts` `3.3.0`，两项的 `trustAnchorKids` 都是 `["export-2026-11a"]`
  （已核：Release 资产 `aster-cli-1.0.50.jar` 与 npm 3.3.0 的 `dist/runner/aster.mjs` 的 `version` 都列出该 kid，公钥与 `/api/v1/exports/keys` 一致）。
  node 的 ts 版本 aster-api 不运行，无 pin 可比；与 cloud 依赖的 ts 版本不一致时由 cloud 返回 503 `export_runtime_skew`（修订 3 P2-R13）。
  node 白名单 `export/export-builtins-node.json` 是该 ts tag 的 `src/policy-package/export-builtins.json` 逐字节副本，随 `node.cliVersion` 一起更新。

按顺序执行，任一步未通过即停：

1. **Vault**：按第 2 节写入 `apps/aster-api-export-signing`（`signing-keys` 为 `{"revision":1,"keys":[{"kid":"export-2026-11a","privateKey":"<种子>"}]}`，`active-kid` 为 `export-2026-11a`）。
2. **k3s**：合并 ExternalSecret `aster-api-export-signing-keys` 与两个 `optional: true` 的 `secretKeyRef`（k3s PR K1）。同步确认（只看键名，不解码值）：
   ```bash
   kubectl -n aster-cloud get externalsecret aster-api-export-signing-keys \
     -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}'      # 期望 True
   kubectl -n aster-cloud get secret aster-api-export-signing-keys -o jsonpath='{.data}' | jq 'keys'   # ["active-kid","signing-keys"]
   ```
3. **重启**：`kubectl -n aster-cloud rollout restart deployment/aster-api && kubectl -n aster-cloud rollout status deployment/aster-api`。
   新 Pod 日志应含 `导出签名密钥已加载 revision=1 kids=[export-2026-11a]`。
4. **验收**（匿名，不带任何头）：
   ```bash
   curl -si https://<api>/api/v1/exports/keys
   ```
   返回 `revision: 1`、一个 `kid: "export-2026-11a"`，`x` 与交给 CLI 锚的公钥相同，响应头含 `Cache-Control: max-age=300`。
5. **列车之后**：T15 锁文件 bump 合入并部署后，再打开 `ASTER_EXPORT_PACKAGE_ENABLED`（计划 U6）。

## 4. 计划轮换（锚先于签名）

导出包离线验签只信 CLI **内置**的锚，所以轮换顺序与收据密钥不同——新公钥必须先随 CLI 发布：

1. **加入新 key**：Vault `keys` 追加新 kid，`revision` +1，`active-kid` 不变；重启后 `/exports/keys` 同时列出新旧两把。
2. **锚进 CLI**：把新公钥加入两个 CLI 的内置锚（T25 同样的两份文件，逐字节相同），**随一次列车发布**。
3. **锁文件**：列车发布之后，以单独的 PR 把新 kid 加入锁文件各 target 的 `trustAnchorKids`（`cliVersion` 同时更新到已发布的、内置该锚的 CLI 版本），部署。
4. **切换签名**：`active-kid` 改为新 kid，`revision` +1，重启。若跳过第 2–3 步直接切换，导出会 503 `export_signing_kid_unanchored`（这是保护，不是故障）。
5. **退役旧 key**：从 `keys` 删除旧 kid，`revision` +1。旧 kid 应继续留在 CLI 内置锚中，否则已下发的旧包无法再验证。

## 5. 紧急撤销（疑似泄露）

1. 若泄露的是 active key，先把 `active-kid` 切到另一把**已进锚**的 key；没有可用的已进锚 key 时，直接撤销，导出将 503 直至新锚随列车发布。
2. 把泄露 key 标为 `"revoked": true`，**`revision` +1**，更新 Vault，等 ExternalSecret 同步，`rollout restart` 并等 `rollout status` 完成。
3. 用 `/exports/keys` 确认该 kid 已消失、`revision` 已增加。
4. **已下发的包无法撤销**（ADR §5.2）：用户机器上的旧 CLI 内置锚仍含该公钥，离线 `aster verify` 仍会通过。需要在 CLI 侧把该 kid
   从内置锚移除并发布新版本，并通知用户升级；在线核对可让用户以 `--jwks https://<api>/api/v1/exports/keys` 验证（该 kid 已不在其中）。

## 6. 故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 导出 503 `export_signing_unavailable` | 未配置密钥（Secret 不存在或两项 env 都缺省），或 active key 运行中已过 `notAfter` | 按第 3 节写 Vault 并重启；过期则按第 4 节切换 |
| 导出 503 `export_signing_kid_unanchored` | `active-kid` 不在锁文件该 target 的 `trustAnchorKids`（首次上线、列车前的预期状态） | 等列车发布含该锚的 CLI 并 bump 锁文件；不要绕过 |
| 导出 503 `export_runtime_unavailable` | 锁文件该 target 为 `null`（P2 首发时的 node） | 等 ts 版本进锁文件 |
| Pod CrashLoopBackOff：`aster.export.signing-keys 无效` | JSON 畸形 / 裸种子 / 未知字段 / 种子长度错误 | 按第 2 节用 jq 重新生成 JSON，`revision` +1 |
| Pod CrashLoopBackOff：必须同时配置或同时缺省 | Vault 只写了一个属性 | 两个属性一起写 |
| Pod CrashLoopBackOff：kid 以 test- 开头 | 用了测试 kid | 换正式 kid，`revision` +1 |
| Pod CrashLoopBackOff：导出签名密钥配置 revision 回退 | `revision` 没有递增或被回滚 | 改为大于 `export_signing_key_revisions` 最大值的整数 |
| Pod CrashLoopBackOff：导出锁文件 jre.cliVersion 比实际加载的引擎版本新 | 锁文件先于引擎 bump（或回滚了引擎） | 锁文件只在引擎升级之后（或同时）bump；PR CI 的 `ExportRuntimesTest` 已拦截领先于生态 pin 的锁文件 |
| 日志 WARN：导出锁文件 jre.cliVersion 落后于引擎版本 | 列车已升级引擎、锁文件 PR 尚未合入（预期状态） | 列车之后合入锁文件 PR（T15）；不影响启动 |
