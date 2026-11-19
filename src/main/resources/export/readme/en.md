# {{moduleName}} — Aster policy package (B form, {{target}})

This package carries the **source and metadata** of version {{version}} of the policy `{{moduleName}}`, exported from aster-lang.cloud. It contains no executable code: a separately installed Aster CLI verifies the signature, recompiles the source locally and checks that the result is the same Core IR the platform approved (`coreIr.positionFreeSha256`). Verify first, then run.

## Install a CLI

- Node (24 or later): download the single-file runner `aster.mjs` and `aster.mjs.sha256` from {{tsReleaseUrl}}, check it with `sha256sum -c aster.mjs.sha256`, and run `node aster.mjs …` (no `npm install` needed).
- JRE (Java 25): download `aster-cli-{{jreVersion}}.jar` and `aster-cli-{{jreVersion}}.jar.sha256` from https://github.com/aster-cloud/aster-lang-truffle/releases/tag/v{{jreVersion}}, check it with `sha256sum -c aster-cli-{{jreVersion}}.jar.sha256`, and run `java --sun-misc-unsafe-memory-access=allow -jar aster-cli-{{jreVersion}}.jar …` (the flag only silences a JDK 25 warning from GraalVM Truffle).

This package was built for `{{cliName}}` {{cliVersion}}. If `verify` reports `PKG_IR_DRIFT`, install that exact version.

## Verify and run

```
aster verify <package.zip> --probes
aster run <package.zip> --input input.json
```

Here `aster` stands for `node aster.mjs` (Node) or `java --sun-misc-unsafe-memory-access=allow -jar aster-cli-{{jreVersion}}.jar` (JRE).

The entry is `{{entry}}`; its input shape is `schema/input.json` (`#/$defs/entry.{{entry}}`). Decimal values are decimal strings; Long values above 2^53 are decimal strings or exact integers.

## Limits

- This package is **not** a Guard execution point: running it records nothing, issues no receipt and enforces no approval. `require_approval` and `escalate` are only return values.
- Facts are **not** verified: inputs are taken as given.{{factSourcesNote}}
- `tests/probes.json` is a sample of inputs evaluated on the platform, not a proof of equivalence.
- A downloaded package cannot be revoked; check the version you run against the platform when it matters.
