# ADR 0052 上线 Runbook：Set 语句与块作用域写回

本文是 ADR 0052（`docs/adr/0052-assignment-and-block-scope-writeback.md`）§6 的执行手册。所有命令都由人执行；
扫描只读，不写任何数据。

## 0. 变更摘要

- Java 前端开始把 `Set x to …` / `将 x 设为 …` / `setze x auf …` 解析为赋值语句，不再改写成 `Let` 声明。
- `Set` 只能赋值给**已有的 Let 绑定**。首次声明用 `Set` 的源码升级后报 E101；`Set` 形参、模式绑定、lambda 内的捕获变量报 E001。
- 涉及制品：aster-lang-locales、aster-lang-core（含 en-US 内置词法表）、aster-lang-ts。truffle 无源码变更，随列车 lockstep 打 tag。

## 1. 发版前：只读扫描存量

在能连生产库的运维机上执行（只读事务，脚本以 `ROLLBACK` 收尾）：

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/ops/adr0052-scan-set-statements.sql
```

输出列：`id, policy_id, version, tenant_id, status, active, lang, set_target, has_let_same_name`。

- `has_let_same_name = f`：文中没有同名 Let，**升级后必然报 E101**，必须在第 3 步前处理。
- `has_let_same_name = t`：通常是合法的写回赋值，升级后语义不变；但同名 Let 是否在作用域内、是否在 Set 之前，以第 2 步的 report 结果为准。
- 结果为空：跳过第 2、3 步，按常规发版。
- `lang` 取自 `policy_versions.locale` 的语言段，空值按 en 处理。hi-IN 不扫描：旧版 Java 解析不了 hi 的 Set，存量不可能有这种写法。

脚本已在一次性 Postgres 17 容器中用 en / de / zh 样例验证：首次声明用 Set 的两行被标为 `f`，合法写回的两行标为 `t`，字符串字面量里的 `Set x to` 不会命中。

## 2. 升级 aster-api：先以 report 模式重验

1. 修改 aster-api 的生态 pin（`settings.gradle` 与 `setup-aster-build` 的 `sibling-ref`），构建镜像。
2. 部署时设环境变量 `ASTER_REVALIDATION_ENABLED=false`。这会关闭启动期的 enforce 重验（`StartupRevalidationTrigger`），
   但不影响手动发起的重验任务。
3. 在 aster-cloud 管理后台的重验入口发起 report 模式重验。它经 `/api/admin/revalidation/runs` 代理到
   aster-api 的 `POST /api/v1/admin/revalidation/runs`，请求体：
   ```json
   {"scope": "all", "mode": "report"}
   ```
   report 模式只写 `advisory = true` 的校验行，执行门不读。
4. 任务完成后，在后台的「无效版本」列表（`/api/admin/revalidation/invalid?toolchainId=<新 toolchain id>`）里找
   诊断落在 `Set` 行上的 E101 / E001。它们应与第 1 步中 `has_let_same_name = f` 的行一一对应；多出的行（如 lambda 内 Set 捕获变量的 E001）一并处理。

## 3. 处理命中的版本

由策略作者修改源码，重新提交审批并发布新版本。不要直接改库。

| 语言 | 改前 | 改后 |
|---|---|---|
| en | `Set x to 1.` | `Let x be 1.` |
| zh | `将 x 设为 1。` | `令 x 定义为 1。` |
| de | `setze x auf 1.` | `sei x gleich 1.` |

lambda 体内 `Set` 捕获的外层变量：改为让 lambda 返回新值，由外层 `Set`。

## 4. 恢复 enforce

命中版本清零后，把 `ASTER_REVALIDATION_ENABLED` 恢复为 `true` 并滚动重启。启动期按新 toolchain id 自动发起 enforce 重验。
也可以在后台直接发起 `{"scope": "all", "mode": "enforce"}`。

## 5. 回滚

1. aster-api 的生态 pin 回退到上一版本，重新构建并部署。
2. toolchain id 随之变回，启动期按旧工具链重验（如果第 2 步关掉了，先恢复 `ASTER_REVALIDATION_ENABLED=true`）。
3. 第 3 步改写成 `Let` 的版本在新旧工具链下都能编译，不需要回退。
4. aster-cloud 若已升级到新的 aster-lang-ts，同时回退 `package.json` 与 `lsp-package.json` 中的版本，否则编辑器会对服务端接受的源码报 E101 / E001。

## 6. 上线后验证

- 发布一份 en 策略：`Let total be 0.`，在 case 体内 `Set total to 10.`，执行结果为写回后的值。
- 对 zh、de 各做一次同样的检查。
- 发布一份首次声明用 `Set` 的策略，确认编辑器与服务端都报 E101 且行号相同。
