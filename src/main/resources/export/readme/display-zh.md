## 显示排版

本包的格式版本为 1.1，带作者在平台上保存的显示排版：

- `policy/display/display.aster`：给人读的显示文本（例如去掉空格、换了标点的原文）。它**不是编译输入**，对它运行 `aster compile` 会得到编译错误；执行的永远是 `policy/policy.aster`。
- `policy/display/layout.json`：显示文本与规范源码逐段的对应关系。

两个文件都在签名与 `SHA256SUMS` 覆盖之内，`aster verify` 会核对它们与规范源码一致（检查项 `display`，加 `--recompile` 时另有 `display-ir`）。版本早于 {{cliName}} {{cliVersion}} 的 CLI 不认得 1.1 包，会以 `PKG_UNSUPPORTED_FORMAT` 退出，请升级。

```
aster show <package.zip>                          # 打印显示文本；stderr 列出哪些文字只是排版、字面量宏展开成什么
aster show <package.zip> --canonical              # 打印规范源码
aster run <package.zip> --display poem.txt --input input.json
```

`run --display` 从改过的显示文本还原规范源码后编译运行：只能改内容，标点、换行与关键词别名等排版文字不能改；还原不唯一（`DISPLAY_AMBIGUOUS`）或对不上（`DISPLAY_UNMAPPABLE`）时不运行；文件须用 LF 换行，编辑器在文末补的换行会被忽略。运行的程序不在包签名覆盖之内：输出的 `meta.display.matchesPackage` 说明它是否与签名的源码等价，`meta.coreIrSha256` 为 `null`，还原出的源码先打印到 stderr。用 `aster show <package.zip> --from-display poem.txt` 可以只看还原结果而不运行。
