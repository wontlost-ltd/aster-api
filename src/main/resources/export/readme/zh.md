# {{moduleName}}——Aster 策略包（B 形态，{{target}}）

本包是从 aster-lang.cloud 导出的策略 `{{moduleName}}` 第 {{version}} 版的**源码与元数据**，不含可执行代码：由单独安装的 Aster CLI 验证签名、在本地重新编译源码，并核对结果与平台批准的 Core IR 相同（`coreIr.positionFreeSha256`）。请先验证，再运行。

## 安装 CLI

- Node：`npm i -g @aster-cloud/aster-lang-ts@{{tsVersion}}`，之后使用 `aster`（或 `npx @aster-cloud/aster-lang-ts@{{tsVersion}} …`）。
- JRE（Java 25）：从 https://github.com/aster-cloud/aster-lang-truffle/releases/tag/v{{jreVersion}} 下载 `aster-cli-{{jreVersion}}.jar` 与 `aster-cli-{{jreVersion}}.jar.sha256`，用 `sha256sum -c aster-cli-{{jreVersion}}.jar.sha256` 校验后运行 `java --sun-misc-unsafe-memory-access=allow -jar aster-cli-{{jreVersion}}.jar …`（该参数只是消除 GraalVM Truffle 在 JDK 25 上的警告）。

本包按 `{{cliName}}` {{cliVersion}} 构建。若 `verify` 报 `PKG_IR_DRIFT`，请安装该版本。

## 验证与运行

```
aster verify <package.zip> --probes
aster run <package.zip> --input input.json
```

入口为 `{{entry}}`，输入形态见 `schema/input.json`（`#/$defs/entry.{{entry}}`）。Decimal 写成十进制字符串；超过 2^53 的 Long 写成十进制字符串或精确整数。

## 限制

- 本包**不是** Guard 执行点：运行不留记录、不签发收据、不执行审批；`require_approval` 与 `escalate` 只是返回值。
- 事实**未经验证**：输入按原样采用。{{factSourcesNote}}
- `tests/probes.json` 是在平台上求值的抽样输入，不是等价性证明。
- 已下载的包无法撤销；需要时请对照平台核对所运行的版本。
