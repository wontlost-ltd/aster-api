// ADR 0059 P2 T11：以 aster-lang-test conformance package-v1 的参照实现检查 aster-api 导出的策略包。
// 用法：node reference-verify.mjs <aster-lang-test 目录> <包路径> <JWKS 文件>
// 输出一行 JSON {ok, code, why}。manifest / sig 的 JSON Schema 由 Java 侧（networknt）校验，这里只跑
// 读包、manifest 规范字节与语义规则、签名、完整性（参照实现的 read → manifest → signature → integrity）；
// 1.1 包（manifest 有 display）另跑参照实现的显示排版 L1–L7（ADR 0059 修订 4 D5 的 display 阶段），失败为 PKG_DISPLAY_INVALID。
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
let out = { ok: r.ok, code: r.code ?? null, why: r.why ?? null, display: false };
if (r.ok && r.manifest.display !== undefined) {
  const lib = (f) => pathToFileURL(join(testDir, 'scripts/lib/package-v1', f)).href;
  const { checkPackageLayout, DisplayRuleError } = await import(lib('display.mjs'));
  const { ruleContext } = await import(lib('display-fixtures.mjs'));
  const json = (p) => (r.files.has(p) ? JSON.parse(r.files.get(p).toString('utf8')) : null);
  const ctx = ruleContext(r.manifest.source.locale, json('policy/compile/alias-set.json'), json('policy/compile/vocabulary.json'));
  try {
    checkPackageLayout({
      layoutBytes: r.files.get('policy/display/layout.json'),
      displayBytes: r.files.get('policy/display/display.aster'),
      source: r.files.get('policy/policy.aster'),
      layoutVersion: r.manifest.display.layoutVersion,
      ...ctx,
    });
    out = { ...out, display: true };
  } catch (e) {
    if (!(e instanceof DisplayRuleError)) throw e;
    out = { ok: false, code: 'PKG_DISPLAY_INVALID', why: e.message, display: true };
  }
}
process.stdout.write(JSON.stringify(out));
