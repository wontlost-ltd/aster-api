# ui-messages（界面文案，aster-api 自带副本）

这些 `<locale-id>.json` 是统一语言包的**界面文案**（ADR 0018），由 `UiMessagesService`
启动时从 classpath 加载，`/api/v1/messages/{locale}` 端点直接吐内存。

## 为什么 api 自带一份

ADR 0018 规定界面文案的真相源是 `aster-lang-locales` 仓（`locales/<lang>/src/main/resources/ui-messages/`）
与 `aster-lang-hi` 仓，前端经 `@aster-cloud/ui-messages`(+`-hi`) npm 包读取。

语言包 JVM jar 里其实也带着同名资源：`cloud.aster-lang:aster-lang-locales-en` jar 含 `ui-messages/en-US.json`
（zh/de 包同理），与这里的副本同路径。api 仍自带一份，是为了让 `/api/v1/messages` 的内容由本仓显式锁定，
而不是取决于 classpath 上哪个 jar 先被找到；`UiMessagesClasspathSiblingParityTest` 与
`scripts/ci/check-ui-messages-drift.py` 保证副本与 pin 到的 locales/hi 版本逐字节一致，
因此每次生态 pin bump 都要先同步这里（否则 CI 失败）。

`runtimeOnly` 的 en/zh/de/hi 语言包 jar 主要提供 **lexicon SPI**（关键词/编译）。

## 语法错误文案不在这里

语法错误文案目录 `syntax-diagnostics/<locale-id>.json`（en-US / zh-CN / de-DE / hi-IN）**不再**在 api 留副本：
它随 `aster-lang-locales-en` jar 上 classpath，`SyntaxMessages` 直接读取（aster-api#423 / #439）。
改语法错误文案只需改 `aster-lang-locales` 仓根的 `syntax-diagnostics/`，随生态发版生效。

评估 400 诊断文案目录 `evaluation-diagnostics/<locale-id>.json` 同理（aster-api#445，aster-lang-locales#121）：
随 `aster-lang-locales-en` jar 上 classpath，`EvaluationDiagnostics` 直接读取；本仓不留同路径副本（会遮蔽 jar），
nightly 的 `check-ui-messages-drift.py` 会把重新出现的副本报为漂移。

## 同步

改文案 = 改真相源（locales 仓）→ 同步到这里：

```bash
cp ../../../../../aster-lang-locales/locales/en/src/main/resources/ui-messages/en-US.json en-US.json
cp ../../../../../aster-lang-locales/locales/zh/src/main/resources/ui-messages/zh-CN.json zh-CN.json
cp ../../../../../aster-lang-locales/locales/de/src/main/resources/ui-messages/de-DE.json de-DE.json
cp ../../../../../aster-lang-hi/src/main/resources/ui-messages/hi-IN.json hi-IN.json
```

> 运行时热刷新（Redis pub/sub `aster.i18n.messages.reload`）只会重载 classpath 上已有的
> 资源；新增/改文案需重新同步 + 重新部署（或后续接 build-time 从 npm 拉取自动化）。
