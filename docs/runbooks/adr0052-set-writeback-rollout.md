# ADR 0052 上线 Runbook：Set 语句与块作用域写回

本文是 ADR 0052（`docs/adr/0052-assignment-and-block-scope-writeback.md`）§6 的执行手册。所有命令都由人执行；
扫描只读，不写任何数据。

## 0. 变更摘要

- Java 前端开始把 `Set x to …` / `将 x 设为 …` / `setze x auf …` 解析为赋值语句，不再改写成 `Let` 声明。
- `Set` 只能赋值给**已有的 Let 绑定**。首次声明用 `Set` 的源码升级后报 E101；`Set` 形参、模式绑定、lambda 内的捕获变量报 E001。
- 涉及制品：aster-lang-locales、aster-lang-core（含 en-US 内置词法表）、aster-lang-ts。truffle 无源码变更，随列车 lockstep 打 tag。

## 为什么必须在升级前改写

升级后，执行门（`PolicyExecutionGate`）在新 toolchain 下找不到该版本的非 advisory 校验行时，会**在请求里同步做类型检查**。
`aster.compile.typecheck=enforce`（默认）下，检查失败直接返回 422 `policy_version_invalid`，并写入一条非 advisory 的 invalid 行。

所以 `ASTER_REVALIDATION_ENABLED=false` **不能**提供「先 report、后 enforce」的缓冲期。它只关掉启动期的全量重验
（`StartupRevalidationTrigger`），命中的版本在第一次执行时照样被拦截。report 模式重验只写 advisory 行，执行门不读，
它只用来看影响面，不保护执行。

改写后的 `Let` 写法在新旧工具链下都能编译，所以正确顺序是：**先扫描 → 作者改写并发布新版本 → 再升级**。

## 1. 发版前：只读扫描存量

在能连生产库的运维机上执行（只读事务，脚本以 `ROLLBACK` 收尾）：

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/ops/adr0052-scan-set-statements.sql
```

脚本对每个版本都跑 en / de / zh 三条模式，不看 `locale` 列（它可能为空或写成 `zh_CN`）。
输出列：`id, policy_id, version, tenant_id, status, active, locale, pattern_lang, set_target, has_let_same_name`。

- `has_let_same_name = f`：文中没有同名 Let，**升级后必然报 E101**，必须改写。
- `has_let_same_name = t`：通常是合法的写回赋值。但脚本不检查先后与作用域，须人工确认：同名 Let **在该 Set 之前**，
  且在同一条作用域链上（同一规则内、外层或同一块）。不满足的同样要改写。
- `Set` 位于 lambda 体内、目标是 lambda 外的变量：升级后报 E001，也要改写（让 lambda 返回新值，由外层 `Set`）。
- 结果为空：直接按常规发版。

脚本已在一次性 Postgres 17 容器中验证：en / de 首次声明、locale 为 `zh_CN` 与空串的 zh 首次声明都标为 `f`；
合法写回（含小写 `set`）标为 `t`；字符串字面量里的 `Set x to` 不会命中；三种语言之间没有交叉误报。

## 2. 发版前：作者改写并发布新版本

由策略作者修改源码，走正常的提交、审批、发布流程。不要直接改库。

| 语言 | 改前 | 改后 |
|---|---|---|
| en | `Set x to 1.` | `Let x be 1.` |
| zh | `将 x 设为 1。` | `令 x 定义为 1。` |
| de | `setze x auf 1.` | `sei x gleich 1.` |

新版本在旧工具链下即可编译与执行。改写完成后重跑第 1 步，确认剩下的命中只有确认无误的合法写回，或者只出现在已停用的旧版本里。
旧版本升级后会被判为 invalid；只有它们仍会被执行（被固定引用或回放）时才需要处理。

## 3. 升级 aster-api

修改 aster-api 的生态 pin（`settings.gradle` 与 `setup-aster-build` 的 `sibling-ref`），构建并部署。启动期重验保持开启。
重验完成后，在 aster-cloud 管理后台的「无效版本」列表（代理到 `GET /api/v1/admin/revalidation/invalid?toolchainId=<新 toolchain id>`）
确认没有落在 `Set` 行上的 E101 / E001。

### 兜底：来不及在升级前改完

只有在必须按时升级、又有命中版本无法提前改写时才用：

1. 部署时临时设 `ASTER_COMPILE_TYPECHECK=warn`。**整个窗口内所有类型错误都只告警**：命中的版本能继续执行，
   新保存的有错误的策略也能通过，所以窗口要尽量短，并通知策略作者。
2. 在后台以 `{"scope": "all", "mode": "report"}` 发起重验，按结果让作者改写并发布新版本。
3. 命中清零后，把 `ASTER_COMPILE_TYPECHECK` 改回 `enforce`（或删除该变量）并滚动重启。
   窗口期间执行门对未改写的版本已经写下 invalid 判定，恢复 enforce 后这些旧版本会被拦截，这是预期行为。

不要用 `ASTER_REVALIDATION_ENABLED=false` 代替这个兜底，原因见上文。`ASTER_COMPILE_GATE=off` 会连带关闭所有执行前校验，不推荐。

## 4. 回滚

1. aster-api 的生态 pin 回退到上一版本，重新构建并部署。
2. toolchain id 随之变回，启动期按旧工具链重验。
3. 第 2 步改写成 `Let` 的版本在新旧工具链下都能编译，不需要回退。
4. 如果用过兜底，确认 `ASTER_COMPILE_TYPECHECK` 已恢复为 `enforce`。
5. aster-cloud 若已升级到新的 aster-lang-ts，同时回退 `package.json` 与 `lsp-package.json` 中的版本，否则编辑器会对服务端接受的源码报 E101 / E001。

## 5. 上线后验证

- 发布一份 en 策略：`Let total be 0.`，在 case 体内 `Set total to 10.`，执行结果为写回后的值。
- 对 zh、de 各做一次同样的检查。
- 发布一份首次声明用 `Set` 的策略，确认编辑器与服务端都报 E101 且行号相同。
