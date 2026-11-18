# package-v1 测试夹具副本

来源：aster-lang-test `conformance/package-v1/`，提交 `c7219e2`（包格式 v1 的唯一真相源，见其 `README.md`）。
本目录只是测试用副本，**不得手改**：`PackageV1FixtureCopyTest` 按下表校验每个文件的 SHA-256；兄弟检出
`../aster-lang-test`（或 `ASTER_LANG_TEST_DIR`）存在时另断言与其逐字节相同。上游更新时整份重新复制并更新本表。

`keys/test-ed25519.priv.b64u` 是**有意公开**的测试私钥（种子为固定标签的 SHA-256），只用于夹具，绝不用于任何真实导出。

## SHA-256 清单（`shasum -a 256`，路径相对本目录，按路径字节序）

```
1267e20e2918f5ef02755da330470b72be91854ae1003a8a39e2ef0914fe2539  keys/test-ed25519.priv.b64u
364bc64b22b8f6ffd67a0fe52c3bcd2e45bee4e15f35ee57c2fc644d2fdb6cb1  keys/test-jwks.json
92bb4ff687abf79773d05110834b09029d6eb62342e6e512ba36aa9aef3ae6d1  schema/manifest-1.0.schema.json
d2253b12ff6a1e89e50fe3adba1020e8ef1b017cc125fd41cc02b22245a68419  schema/sig-1.0.schema.json
66c5c16b7c04add9ff7c5d4c69ac8ead994a12c423bc61ac1584fc9b7057d747  vectors/position-free-hash.json
adcf8b9209656215bf09178c68366cd12e0a3e0edf6e542519ee628915e98b2b  vectors/signing.json
81959f0f6cbb91f4bf510f7c2ab96ef7a71495eeb8f9b3db22a33b303f3d38d1  vectors/value-json.json
```
