// ADR 0059 P2 T11：以 aster-lang-test conformance package-v1 的参照实现检查 aster-api 导出的策略包。
// 用法：node reference-verify.mjs <aster-lang-test 目录> <包路径> <JWKS 文件>
// 输出一行 JSON {ok, code, why}。manifest / sig 的 JSON Schema 由 Java 侧（networknt）校验，这里只跑
// 读包、manifest 规范字节与语义规则、签名、完整性（参照实现的 read → manifest → signature → integrity）。
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const [, , testDir, pkg, jwksPath] = process.argv;
const { checkPackage } = await import(pathToFileURL(join(testDir, 'scripts/lib/package-v1/reference-check.mjs')).href);
const keys = new Map();
for (const k of JSON.parse(readFileSync(jwksPath, 'utf8')).keys) keys.set(k.kid, Buffer.from(k.x, 'base64url'));
const r = checkPackage(pkg, {
  validateManifest: () => true,
  validateSig: () => true,
  readCoreIr: () => {
    throw new Error('B 包不读 compiled/core.json');
  },
  anchor: { kind: 'jwks', keys },
  allowUnsigned: false,
});
process.stdout.write(JSON.stringify({ ok: r.ok, code: r.code ?? null, why: r.why ?? null }));
