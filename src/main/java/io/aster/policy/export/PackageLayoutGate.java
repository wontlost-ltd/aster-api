package io.aster.policy.export;

import aster.core.embed.InProcessCnlParser;
import aster.core.embed.PolicySourceCompiler;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreIrFormatException;
import aster.core.layout.LayoutException;
import aster.core.layout.L10bVerifier;
import aster.core.layout.LayoutFormat;
import aster.core.layout.PackageLayoutRules;
import aster.core.layout.PolicyLayout;
import aster.core.lexicon.SemanticTokenKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.replay.core.ReplayExecutionCore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 签名前的显示排版闸门（ADR 0059 修订 4 D11–D12、修订 5）：cloud 送来的排版用 core {@code aster.core.layout} 跑 L1–L10，
 * 再查锁文件该 target 的 CLI 是否认得 1.1，全部通过才随包写入 {@code policy/display/*}，否则省略排版、照常导出 1.0。
 *
 * <p>不变式：签名的 1.1 包一定通过 CLI 的 {@code verify}（含 {@code --recompile} 的 {@code display-ir}）。为此这里与两个 CLI
 * 用同一份规则、同一组编译输入（locale、aliasSet、allowStructural、词汇）：L1–L7 由 {@link PackageLayoutRules#checkPackage}
 * 判定（{@code display.aster} 由本闸门按 {@code toDisplay} 生成，L4 自然成立），L8 用平台编译出的 IR 找名字，L9 编译补空格后的
 * 源码并比较投影哈希；L10a（隐藏转义符形状）随 {@code checkPackage} 跑，L10b（去反引号不变性）与 CLI {@code verify} 的
 * {@code display-ir} 同序放在 L9 之后，用 CLI 同一个编译入口与包内同一组编译输入编译每个 {@code C⁻(S)}
 * （{@link #variantPositionFreeSha256}）。没有转义三元组的排版不多编译一次。
 *
 * <p>排版不是编译输入（ADR 0058 §7.2），所以这里的任何失败——包括规则抛出的意外异常——都只把排版省略并给出原因码，
 * 不让一条可执行的已批准策略因显示层问题导不出（修订 4 §11 Q1）。原因码不回显排版内容。
 */
@ApplicationScoped
public class PackageLayoutGate {
  private static final Logger LOG = Logger.getLogger(PackageLayoutGate.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 带排版的包格式版本（修订 4 D2）。 */
  public static final String LAYOUT_FORMAT_VERSION = "1.1";
  /** manifest {@code display.layoutVersion}，即 ADR 0058 §7.3 的 {@code v}。 */
  public static final int LAYOUT_VERSION = 1;
  /** 请求体 {@code layout} 的上限（UTF-8 字节，同 {@code LAYOUT_LIMITS.serializedBytes}），超出由端点 400 {@code layout_too_large}。 */
  public static final int MAX_LAYOUT_BYTES = 49_152;

  public static final String INCLUDED = "included";
  public static final String ABSENT = "absent";
  public static final String OMITTED = "omitted";
  /** 排版过不了 L1–L9（或规则执行出错）。 */
  public static final String REASON_RULES_FAILED = "display_rules_failed";
  /** 锁文件中该 target 的 CLI 不认得 1.1。 */
  public static final String REASON_CLI_UNSUPPORTED = "cli_unsupported";

  /**
   * 闸门结论。{@code included} 时两段字节非空：{@code layoutJson} 为 cloud 送来的规范序列化字节（L1 已证与
   * {@code serializeLayout} 相同），{@code displayAster} 为 {@code toDisplay} 的 UTF-8；其余状态两者为 null。
   * {@code reason} 只在 {@code omitted} 时非 null。
   */
  public record LayoutOutcome(byte[] layoutJson, byte[] displayAster, String status, String reason) {
    public static LayoutOutcome absent() {
      return new LayoutOutcome(null, null, ABSENT, null);
    }

    public static LayoutOutcome omitted(String reason) {
      return new LayoutOutcome(null, null, OMITTED, reason);
    }

    public static LayoutOutcome included(byte[] layoutJson, byte[] displayAster) {
      return new LayoutOutcome(layoutJson, displayAster, INCLUDED, null);
    }

    public boolean isIncluded() {
      return INCLUDED.equals(status);
    }

    /** 审计 {@code layoutOutcome}：{@code included} / {@code absent} / {@code omitted:<reason>}。 */
    public String auditValue() {
      return OMITTED.equals(status) ? OMITTED + ":" + reason : status;
    }
  }

  private final PolicyCompiler compiler;
  private final ExportRuntimes runtimes;

  @Inject
  public PackageLayoutGate(PolicyCompiler compiler, ExportRuntimes runtimes) {
    this.compiler = compiler;
    this.runtimes = runtimes;
  }

  /**
   * 判定本次导出能否带排版。
   *
   * @param layoutJsonOrNull   cloud 送来的排版序列化文本；null 表示版本无排版或 cloud 已省略
   * @param spec               本次导出的编译输入（与包内 {@code policy/compile/*} 同源）
   * @param platformCoreIrJson 平台编译出的 Core IR（L8 的名字来源）
   * @param positionFreeSha256 该 IR 的投影哈希（L9 的比较基准，即 manifest {@code coreIr.positionFreeSha256}）
   */
  public LayoutOutcome evaluate(String layoutJsonOrNull, PackageExportService.ExportSpec spec, String platformCoreIrJson,
                                String positionFreeSha256) {
    if (layoutJsonOrNull == null) {
      return LayoutOutcome.absent();
    }
    try {
      byte[] layoutJson = layoutJsonOrNull.getBytes(StandardCharsets.UTF_8);
      String failed = checkRules(layoutJson, spec, platformCoreIrJson, positionFreeSha256);
      if (failed != null) {
        LOG.infof("策略包导出省略显示排版（%s）：tenant=%s policy=%s", failed, spec.tenantId(), spec.claimedRef().policyId());
        return LayoutOutcome.omitted(REASON_RULES_FAILED);
      }
      if (runtimes.target(spec.target()).filter(t -> t.supportsFormat(LAYOUT_FORMAT_VERSION)).isEmpty()) {
        return LayoutOutcome.omitted(REASON_CLI_UNSUPPORTED);
      }
      byte[] display = LayoutFormat.parseStrict(layoutJson).toDisplay().getBytes(StandardCharsets.UTF_8);
      return LayoutOutcome.included(layoutJson, display);
    } catch (RuntimeException e) {
      LOG.warnf(e, "显示排版闸门执行出错，省略排版：tenant=%s target=%s", spec.tenantId(), spec.target());
      return LayoutOutcome.omitted(REASON_RULES_FAILED);
    }
  }

  /**
   * L1–L10；全部通过返回 null，否则返回第一条失败的规则号（只进日志）。规则内部的 {@code LayoutException} 也在此转成规则号。
   * 判定次序同 CLI {@code verify}：L1–L7 与 L10a、L8、L9、L10b。包可见仅供测试断言规则号。
   */
  String checkRules(byte[] layoutJson, PackageExportService.ExportSpec spec, String platformCoreIrJson,
                            String positionFreeSha256) {
    PolicyLayout layout;
    try {
      // L4 的 display.aster 由本闸门生成：先按 L1 严格解析取 toDisplay，再让 checkPackage 走完整的 L1–L7
      byte[] display = LayoutFormat.parseStrict(layoutJson).toDisplay().getBytes(StandardCharsets.UTF_8);
      layout = PackageLayoutRules.checkPackage(layoutJson, display, spec.source(), LAYOUT_VERSION,
          PackageLayoutRules.vocabularyLocalized(spec.vocabulary() == null ? null : JSON.valueToTree(spec.vocabulary())),
          PackageLayoutRules.literalKeywords(spec.locale()),
          PackageLayoutRules.operatorSpellings(spec.locale(), spec.aliasSet()), L10bVerifier.DEFERRED);
    } catch (LayoutException e) {
      return e.rule();
    }
    List<String> hits = PackageLayoutRules.structuralNameHits(layout, PackageLayoutRules.irNameStrings(platformCoreIrJson));
    if (!hits.isEmpty()) {
      return "L8";
    }
    String paddedIr = compile(PackageLayoutRules.padStructural(layout), spec);
    if (paddedIr == null || !positionFreeSha256.equals(positionFreeSha256(paddedIr))) {
      return "L9";
    }
    try {
      PackageLayoutRules.checkHiddenEscapes(layout, verifier(spec, positionFreeSha256));
    } catch (LayoutException e) {
      return e.rule();
    }
    return null;
  }

  /**
   * L10b 的编译接缝（ADR 0059 修订 5 §2.3、修订 4 D11）：编译 {@code C⁻(S)} 并返回投影哈希，编译失败返回 null（core 据此判该读法
   * 不是程序）。L3 已证 {@code toCanonical(layout)} 等于 {@code spec.source()}，而 {@code C} 的投影哈希就是平台已算好的
   * {@code positionFreeSha256}（manifest 同值），故不为 {@code C} 再编译：至多 15 次额外编译，且只在有三元组时发生。
   */
  private L10bVerifier verifier(PackageExportService.ExportSpec spec, String positionFreeSha256) {
    CanonicalInputs[] inputs = new CanonicalInputs[1];
    return source -> {
      if (source.equals(spec.source())) {
        return positionFreeSha256;
      }
      if (inputs[0] == null) {
        inputs[0] = CanonicalInputs.of(spec);
      }
      return variantPositionFreeSha256(source, inputs[0]);
    };
  }

  /**
   * 包内 {@code policy/compile/*} 与 manifest {@code source} 给出的编译输入，按 CLI {@code PackagePipeline.compileSource} 的读法
   * 解析（先序列化成包里那份规范 JSON 再读回），保证两侧编译的是同一组输入。
   */
  record CanonicalInputs(String locale, VocabularyScopes scopes, Map<SemanticTokenKind, List<String>> aliasSet,
                         boolean allowStructural) {
    static CanonicalInputs of(PackageExportService.ExportSpec spec) {
      return new CanonicalInputs(spec.locale(),
          PolicySourceCompiler.vocabularyScopesOf(PackageExportService.vocabularyJson(spec.vocabulary())),
          PolicySourceCompiler.parseAliasSetJson(PackageExportService.aliasSetJson(spec.aliasSet())), spec.allowStructural());
    }
  }

  /**
   * 用 CLI {@code verify} 的 L10b 同一个编译入口 {@link PolicySourceCompiler#compileCanonical}（解析、名字解析、lower；不做类型检查）
   * 编译 {@code C⁻(S)}。不能用 {@link PolicyCompiler}：它另做类型检查，会把「能编译但成了另一个程序」的变体（向量 E4：
   * {@code Return none.} 在 {@code produce Int} 下类型不符）当成编译失败而放行，签出 CLI {@code verify} 拒绝的包。
   * 解析类失败返回 null；其余异常向上抛，由 {@link #evaluate} 省略排版（比 CLI 更严，失败封闭）。包可见供测试计数。
   */
  String variantPositionFreeSha256(String source, CanonicalInputs in) {
    String ir;
    try {
      ir = PolicySourceCompiler.compileCanonical(source, in.locale(), in.scopes(), in.aliasSet(), in.allowStructural());
    } catch (InProcessCnlParser.CnlParseException e) {
      return null;
    } catch (StackOverflowError e) {
      throw new IllegalStateException("C⁻(S) 编译栈溢出", e);
    }
    return positionFreeSha256(ir);
  }

  private static String positionFreeSha256(String coreJson) {
    try {
      return PolicySourceCompiler.positionFreeSha256(coreJson);
    } catch (CoreIrFormatException e) {
      throw new IllegalStateException("排版闸门编译出的 Core IR 无法严格读入: " + e.getMessage(), e);
    }
  }

  /** 用与本次导出相同的编译输入编译补空格后的源码；失败（含 error 级诊断）返回 null。 */
  private String compile(String source, PackageExportService.ExportSpec spec) {
    CompilationResult r = compiler.compile(source, spec.locale(), PackageExportService.aliasSetJson(spec.aliasSet()),
        ReplayExecutionCore.buildVocabularyScopes(spec.vocabulary()), spec.allowStructural());
    boolean errors = r.getDiagnostics().stream().anyMatch(d -> "error".equals(d.severity()));
    return r.isSuccess() && !errors ? r.getCoreJson() : null;
  }
}
