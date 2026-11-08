# Guard 执行收据签名密钥运维手册

适用对象：aster-api 的 Action Guard 执行收据（ADR 0040 §12 D1/D6，#463）。服务用 Ed25519 密钥给收据签名，
`GET /api/v1/guard/keys` 发布验签公钥（JWKS）与配置 `revision`。本手册只描述操作步骤，不含任何真实密钥。

## 1. 配置模型

| 项 | 位置 | 说明 |
| --- | --- | --- |
| `signing-keys` | Vault `apps/aster-api-guard-receipt` 属性 `signing-keys` → env `ASTER_GUARD_RECEIPT_SIGNING_KEYS` | JSON：`{"revision":N,"keys":[{"kid","privateKey","notAfter"?,"revoked"?}]}` |
| `active-kid` | Vault 同路径属性 `active-kid` → env `ASTER_GUARD_RECEIPT_ACTIVE_KID` | 当前用于签发的 kid，必须在 `keys` 中 |

- `privateKey`：32 字节 Ed25519 种子的 base64url（无填充，43 字符）。
- `notAfter`（可选，ISO-8601）：`now >= notAfter` 的 key 不再可签发，也不再出现在 `/keys`。
- `revoked`（可选，`true`）：紧急撤销，见第 5 节。
- 未知字段、重复键、kid 重复、种子长度错误都会让启动失败；报错信息不含种子。
- Pod 的 env 在容器启动时快照，**任何密钥变更都要 `kubectl rollout restart deployment/aster-api`**。

### revision 规则（强制）

**每一次 `signing-keys` 配置变更都必须让 `revision` 增加。** 服务启动时把 `revision` 写入
`guard_signing_key_revisions`，若配置的 `revision` 低于库中已记录的最大值则**拒绝启动**（报「签名密钥配置 revision 回退」）
（防止回滚到旧密钥集后悄悄让已撤销的 key 复活）。`revision` 相同但内容变化同样不可取：
客户端以 `/keys` 返回的 `revision` 判断本地缓存是否过期。

## 2. 生成密钥（在操作员本机执行）

私钥种子只在本机终端和 Vault 中出现，不要写入仓库、工单或聊天记录。

需要 **OpenSSL 3.x**。macOS 自带的是 LibreSSL，会报 `Algorithm ed25519 not found`；请先 `brew install openssl@3`，
并用 `/opt/homebrew/opt/openssl@3/bin/openssl`（Intel Mac 为 `/usr/local/opt/openssl@3/bin/openssl`）替换下面命令里的 `openssl`。
先用 `openssl version` 确认输出以 `OpenSSL 3` 开头。

```bash
# 生成 Ed25519 私钥，取 DER 末尾 32 字节种子，转为 base64url（无填充）
SEED=$(openssl genpkey -algorithm ed25519 -outform DER \
  | tail -c 32 | openssl base64 -A | tr '+/' '-_' | tr -d '=')
[ "${#SEED}" -eq 43 ] && echo ok   # 不是 ok 就重新生成；不要 echo "$SEED" 到共享终端
```

种子必须恰为 43 个字符（上面的自检输出 `ok`）。把 `$SEED` 填入某个 key 的 `privateKey`。kid 建议用 `YYYY-MM` 加序号字母（如 `2026-11a`）。

## 3. 首次部署（revision 1）

按下面顺序执行，任一步未通过即停：

1. **Vault**：写入 `apps/aster-api-guard-receipt`，属性 `signing-keys` 为 `{"revision":1,"keys":[{"kid":"2026-11a","privateKey":"<第 2 节生成的种子>"}]}`，属性 `active-kid` 为 `2026-11a`。
2. **k3s**：`ExternalSecret aster-api-guard-receipt-keys`（`secretStoreRef: vault-backend`，两项 `data` 指向 `apps/aster-api-guard-receipt`）；`aster-api` 容器增加 `ASTER_GUARD_RECEIPT_SIGNING_KEYS`、`ASTER_GUARD_RECEIPT_ACTIVE_KID` 两个 `secretKeyRef`。同步确认（只看键名，不解码值）：
   ```bash
   kubectl -n aster-cloud get externalsecret aster-api-guard-receipt-keys \
     -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}'      # 期望 True
   kubectl -n aster-cloud get secret aster-api-guard-receipt-keys -o jsonpath='{.data}' | jq 'keys'
   ```
3. **镜像**：Secret 就绪后再发布含执行收据的 aster-api 镜像。新 Pod 日志应含 `签名密钥已加载 revision=1 kids=[2026-11a]`。缺密钥时 Pod 启动失败（CrashLoopBackOff）而旧 ReplicaSet 继续服务，这是有意的 fail-fast。
4. **验收**：
   ```bash
   curl -H "Authorization: Bearer <key>" https://<api>/api/v1/guard/keys
   ```
   返回 `revision: 1` 与一个 `kid`，响应头含 `Cache-Control: max-age=300`。

## 4. 计划轮换与退役

每一步都是一次配置变更：改 Vault → 等 ExternalSecret 同步 → `kubectl -n aster-cloud rollout restart deployment/aster-api` → `kubectl -n aster-cloud rollout status deployment/aster-api` → 用 `/keys` 核对 `revision` 与 kid 列表。

1. **加入新 key**：`keys` 追加新 kid，`revision` +1，`active-kid` 不变。目的：让所有验签方先从 `/keys` 拿到新公钥（等至少一个 `max-age=300` 周期）。
2. **切换签发**：`active-kid` 改为新 kid，`revision` +1。此后新收据由新 key 签发，旧 key 仍发布，旧收据照常验签。
3. **退役旧 key**：距第 2 步至少 24 小时（远大于收据最大有效期 `aster.guard.receipt.ttl`，默认 5 分钟；调大过 TTL 时须相应延长）后，从 `keys` 删除旧 kid，`revision` +1。

退役语义：从列表删除即退役，之后 `/keys` 不再列出该 kid，离线验签不再通过；服务端 consume 对仍在 TTL 内的旧 kid 收据照常（它是正常到期，不是撤销）。也可预先给旧 key 设置 `notAfter`，到点后它自动不再发布，但仍应随后删除并 `revision` +1。`active-kid` 指向的 key 已到 `notAfter` 时启动失败，轮换必须赶在到期前完成。

## 5. 紧急撤销（疑似泄露）

1. 若泄露的是 active key，先按第 4 节第 1–2 步准备好替代 key（可与下一步合并为同一次变更）：新 kid 加入并把 `active-kid` 切走（`active-kid` 不能指向 `revoked` 的 key，否则启动失败）。
2. 把泄露 key 标为 `"revoked": true`，**`revision` +1**。已撤销 key 不再出现在 `/keys`，其收据 consume 返回 409 `kid_revoked`。
3. 更新 Vault，等 ExternalSecret 同步后执行：
   ```bash
   kubectl -n aster-cloud rollout restart deployment/aster-api
   kubectl -n aster-cloud rollout status deployment/aster-api
   ```
4. **`rollout status` 成功完成才算撤销完成**（所有 Pod 都已加载新配置）。随后用 `/keys` 确认 `revision` 已增加且该 kid 消失。
5. 离线验签方依赖缓存的 `/keys`，对撤销的感知最多滞后 300 秒（`max-age=300`）；需要更短窗口时只能要求执行方强制刷新缓存。
6. 撤销后 revoked 条目保留在配置中（它才能让 consume 返回 `kid_revoked` 而不是普通的未知 kid）；确认其签发的收据都已超过 TTL 后，可按退役流程删除，`revision` 再 +1。

## 6. 故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| Pod CrashLoopBackOff，日志提示「签名密钥配置 revision 回退」 | `signing-keys.revision` 没有递增或被回滚 | 把 `revision` 改为大于库中 `guard_signing_key_revisions` 最大值的整数，重启 |
| 启动失败：`active-kid` 不在 keys / 已 revoked / 已过 notAfter | 轮换配置不一致 | 修正 `active-kid` 或 `keys`，`revision` +1 |
| 启动失败：privateKey 必须是 base64url 编码的 32 字节种子 | 种子被截断、含填充 `=` 或用了标准 base64 | 按第 2 节重新生成或修正编码 |
| `/keys` 缺少某 kid | 已 `revoked`、已过 `notAfter` 或已从配置删除 | 核对配置；这是预期行为 |
