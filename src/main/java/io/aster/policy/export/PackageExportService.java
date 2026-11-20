package io.aster.policy.export;

import aster.core.canonical.CanonicalJson;
import aster.core.embed.PolicySourceCompiler;
import aster.core.identifier.VocabularyScopes;
import aster.core.ir.CoreIrFormatException;
import aster.core.ir.CoreIrJson;
import aster.core.ir.CoreModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.compiler.CompilationResult;
import io.aster.policy.compiler.PolicyCompiler;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.parser.CompileIdentity;
import io.aster.policy.parser.EntryPointSelector;
import io.aster.policy.stability.ToolchainIdentityProvider;
import io.aster.replay.core.ReplayExecutionCore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * B 形态策略包导出编排（ADR 0059 P2，修订 3 P2-R4–P2-R14）：生产编译路径（{@link PolicyCompiler}）取 Core IR →
 * 选入口（显式 &gt; {@code @entry} &gt; 单 Rule）→ 闭世界检查与入口 schema → 探针与平台期望值 → manifest 字段推导 → 组装与签名。
 *
 * <p>{@link #check} 只编译与静态检查（廉价，供详情页决定显示）；{@link #prepare} 另跑探针（最多 256 次 Truffle 求值）；
 * {@link #export} 在 prepare 之上组装 zip。任一步的结论不依赖时间、请求 id 或 JVM 默认时区，同一输入两次导出逐字节相同。
 */
@ApplicationScoped
public class PackageExportService {
  public static final List<String> TARGETS = List.of("jre", "node");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, String> ENGINE = Map.of("jre", "aster-lang-truffle", "node", "aster-lang-ts-interpreter");
  private static final Map<String, Map<String, String>> REQUIRES = Map.of(
      "jre", Map.of("java", ">=25"), "node", Map.of("node", ">=24"));

  /** 导出输入。{@code aliasSet} / {@code vocabulary} 为 null 表示无；{@code entry} 为 null 表示按选择规则确定。 */
  public record ExportSpec(String source, String locale, Map<String, List<String>> aliasSet, boolean allowStructural,
                           Map<String, Object> vocabulary, String entry, String target, String uiLocale,
                           PackageAssembler.ClaimedRef claimedRef, String tenantId) {
  }

  /** 编译 + 入口选择的中间结果。 */
  private record Compiled(String coreJson, CoreModel.Module module, List<ExportDiagnostic> compileErrors,
                          String entry, List<String> candidates) {
  }

  private final PolicyCompiler compiler;
  private final ExportRuntimes runtimes;
  private final PackageAssembler assembler;
  private final ToolchainIdentityProvider toolchain;
  private final PackageLayoutGate layoutGate;

  @Inject
  public PackageExportService(PolicyCompiler compiler, ExportRuntimes runtimes, PackageAssembler assembler,
                              ToolchainIdentityProvider toolchain, PackageLayoutGate layoutGate) {
    this.compiler = compiler;
    this.runtimes = runtimes;
    this.assembler = assembler;
    this.toolchain = toolchain;
    this.layoutGate = layoutGate;
  }

  /** 两个 target 的导出结论（不跑探针、不签名）。 */
  public PackageExportCheck check(ExportSpec spec) {
    Compiled c = compile(spec);
    Map<String, PackageExportCheck.TargetResult> targets = new LinkedHashMap<>();
    for (String target : TARGETS) {
      targets.put(target, targetResult(c, target));
    }
    return new PackageExportCheck(targets, c.entry(), c.candidates());
  }

  /**
   * 检查 + 探针与平台期望值。
   *
   * @throws PackageExportUnavailableException target 在锁文件中为 null 或白名单缺失（503）
   * @throws EntryRequiredException            入口确定不了（400）
   */
  public PackagePrepared prepare(ExportSpec spec) {
    Compiled c = compile(spec);
    PackageExportCheck.TargetResult r = targetResult(c, spec.target());
    if (r.unavailable() != null) {
      throw new PackageExportUnavailableException(r.unavailable());
    }
    if (!c.compileErrors().isEmpty() || !r.diagnostics().isEmpty()) {
      return PackagePrepared.rejected(r.diagnostics());
    }
    if (c.entry() == null) {
      throw new EntryRequiredException(c.candidates());
    }
    List<JsonNode> inputs = ProbeGenerator.inputs(c.module(), c.entry(), ProbeGenerator.MAX_PROBES);
    ProbeRunner.ProbesFile probes = ProbeRunner.run(c.coreJson(), c.entry(), inputs);
    ExportRuntimes.Target lock = runtimes.target(spec.target()).orElseThrow();
    return new PackagePrepared(true, List.of(), c.entry(), c.coreJson(),
        PackageAssembler.sha256(c.coreJson().getBytes(StandardCharsets.UTF_8)), positionFreeSha256(c.coreJson()),
        probes, new PackagePrepared.Runtime(lock.cliName(), lock.cliVersion(), runtimes.ecosystem()));
  }

  /**
   * 导出 zip。{@code nodeCheck} 为 cloud 已核对的 Node 侧比对结果（jre 恒为 null）。
   *
   * @throws ExportRejectedException           有导出诊断（含编译错误）
   * @throws PackageExportUnavailableException 签名不可用、kid 未进锚、target 不可用（503，未组装 zip）
   * @throws PackageTooLargeException          zip 超过 1 MiB（E734）
   * @throws EntryRequiredException            入口确定不了
   */
  public PackageAssembler.AssembledPackage export(ExportSpec spec, PackageAssembler.NodeCheck nodeCheck) {
    return export(spec, nodeCheck, null);
  }

  /** 同上，{@code layout} 为 cloud 送来的显示排版序列化文本（null 表示无），经 {@link PackageLayoutGate} 判定是否随包。 */
  public PackageAssembler.AssembledPackage export(ExportSpec spec, PackageAssembler.NodeCheck nodeCheck, String layout) {
    PackagePrepared p = prepare(spec);
    if (!p.exportable()) {
      throw new ExportRejectedException(p.diagnostics());
    }
    return export(spec, p, nodeCheck, layout);
  }

  /**
   * 以已算好的 prepare 结果组装（端点在核对 nodeProbes 后调用，避免重复求值探针）。排版闸门在签名前、用 prepare 已编译的
   * 平台 IR 与本次导出相同的编译输入跑 L1–L9（修订 4 D11）；不过则省略排版、组装 1.0 包，结论随结果返回。
   */
  public PackageAssembler.AssembledPackage export(ExportSpec spec, PackagePrepared p, PackageAssembler.NodeCheck nodeCheck,
                                                  String layout) {
    PackageLayoutGate.LayoutOutcome layoutOutcome =
        layoutGate.evaluate(layout, spec, p.coreJson(), p.positionFreeSha256());
    CoreModel.Module module = readModule(p.coreJson());
    CoreModel.Func entry = EntrySchemaGenerator.findFunc(module, p.entry());
    String aliasJson = aliasSetJson(spec.aliasSet());
    VocabularyScopes scopes = ReplayExecutionCore.buildVocabularyScopes(spec.vocabulary());
    String toolchainId = toolchain.currentToolchainId();
    PackageAssembler.ManifestFields fields = new PackageAssembler.ManifestFields(
        p.coreIrSha256(), p.positionFreeSha256(), toolchainId,
        declaredEffects(module), spec.tenantId(),
        List.of(), isVerdict(entry.ret),
        sortedAnnotationValues(entry, "control"), p.entry(), module.profile,
        sortedAnnotationValues(entry, "id").stream().findFirst().orElse(null),
        ENGINE.get(spec.target()), p.runtime().cliName(), p.runtime().cliVersion(), REQUIRES.get(spec.target()),
        p.runtime().ecosystem(), spec.allowStructural(), spec.claimedRef(),
        PolicyVersion.computeSourceEnvelope(spec.source(), aliasJson, spec.locale(), toolchainId,
            CompileIdentity.scopesJson(scopes)),
        spec.locale(), nodeCheck);
    PackageAssembler.PackageInputs in = new PackageAssembler.PackageInputs(module.name, spec.target(),
        spec.uiLocale(), spec.source().getBytes(StandardCharsets.UTF_8), aliasJson == null ? "null" : aliasJson,
        vocabularyJson(spec.vocabulary()), EntrySchemaGenerator.generate(module, p.entry()), p.probes(), fields,
        runtimes.target("jre").map(ExportRuntimes.Target::cliVersion).orElse(p.runtime().ecosystem()),
        runtimes.target("node").map(ExportRuntimes.Target::cliVersion).orElse(null), layoutOutcome);
    return assembler.assemble(in);
  }

  private Compiled compile(ExportSpec spec) {
    String aliasJson = aliasSetJson(spec.aliasSet());
    VocabularyScopes scopes = ReplayExecutionCore.buildVocabularyScopes(spec.vocabulary());
    CompilationResult compiled = compiler.compile(spec.source(), spec.locale(), aliasJson, scopes, spec.allowStructural());
    List<ExportDiagnostic> errors = compileErrors(compiled);
    if (!errors.isEmpty()) {
      return new Compiled(null, null, errors, null, List.of());
    }
    CoreModel.Module module = readModule(compiled.getCoreJson());
    List<String> rules = new ArrayList<>();
    String annotated = null;
    for (CoreModel.Decl d : module.decls) {
      if (d instanceof CoreModel.Func f) {
        rules.add(f.name);
        if (annotated == null && f.annotations.stream().anyMatch(a -> "entry".equals(a.name))) {
          annotated = f.name;
        }
      }
    }
    String entry = switch (EntryPointSelector.select(spec.entry(), rules, annotated, false)) {
      case EntryPointSelector.Selected s -> s.function();
      default -> null;
    };
    return new Compiled(compiled.getCoreJson(), module, List.of(), entry, rules);
  }

  private PackageExportCheck.TargetResult targetResult(Compiled c, String target) {
    if (!c.compileErrors().isEmpty()) {
      return new PackageExportCheck.TargetResult(false, c.compileErrors(), null);
    }
    Optional<ExportBuiltins> builtins = ExportBuiltins.forTarget(target);
    if (runtimes.target(target).isEmpty() || builtins.isEmpty()) {
      return new PackageExportCheck.TargetResult(false, List.of(), PackageSigner.RUNTIME_UNAVAILABLE);
    }
    List<ExportDiagnostic> diagnostics = new ArrayList<>(ExportChecker.checkModule(c.module(), target, builtins.get()));
    if (c.entry() != null) {
      diagnostics.addAll(ExportChecker.checkEntry(c.module(), c.entry(), target));
      diagnostics.addAll(EntrySchemaGenerator.generate(c.module(), c.entry()).diagnostics());
    }
    return new PackageExportCheck.TargetResult(diagnostics.isEmpty() && c.entry() != null, diagnostics, null);
  }

  /** 编译失败或带 error 级诊断（同 Cedar 导出的口径）转为导出诊断，码沿用编译器的码。 */
  private static List<ExportDiagnostic> compileErrors(CompilationResult compiled) {
    List<CompilationResult.Diagnostic> source = compiled.isSuccess()
        ? compiled.getDiagnostics().stream().filter(d -> "error".equals(d.severity())).toList()
        : compiled.getDiagnostics();
    if (!compiled.isSuccess() && source.isEmpty()) {
      return compiled.getErrors().stream().map(m -> new ExportDiagnostic(null, m, "error", 1, 1)).toList();
    }
    return source.stream().map(d -> new ExportDiagnostic(d.code(), d.message(), "error", d.line(), d.column())).toList();
  }

  static String aliasSetJson(Map<String, List<String>> aliasSet) {
    return aliasSet == null || aliasSet.isEmpty() ? null : CanonicalJson.canonicalJson(JSON.valueToTree(aliasSet));
  }

  static String vocabularyJson(Map<String, Object> vocabulary) {
    return vocabulary == null || vocabulary.isEmpty() ? null : CanonicalJson.canonicalJson(JSON.valueToTree(vocabulary));
  }

  /** {@code effects.declared}：各函数声明效应的排序并集。 */
  static List<String> declaredEffects(CoreModel.Module module) {
    TreeSet<String> out = new TreeSet<>(ValueJson::compareByCodePoint);
    module.decls.stream().filter(d -> d instanceof CoreModel.Func).map(d -> (CoreModel.Func) d)
        .forEach(f -> out.addAll(f.effects == null ? List.of() : f.effects));
    return List.copyOf(out);
  }

  static boolean isVerdict(CoreModel.Type ret) {
    return ret instanceof CoreModel.TypeName tn && "Verdict".equals(tn.name);
  }

  /** 入口某注解全部首参（字符串）的排序去重列表。 */
  static List<String> sortedAnnotationValues(CoreModel.Func f, String name) {
    TreeSet<String> out = new TreeSet<>(ValueJson::compareByCodePoint);
    for (CoreModel.Annotation a : f.annotations) {
      if (name.equals(a.name)) {
        a.arg("$0").filter(String.class::isInstance).map(String.class::cast).ifPresent(out::add);
      }
    }
    return List.copyOf(out);
  }

  static CoreModel.Module readModule(String coreJson) {
    try {
      return CoreIrJson.read(coreJson);
    } catch (CoreIrFormatException e) {
      throw new IllegalStateException("编译产物不是合法的 v2 Core IR", e);
    }
  }

  /** {@code coreIr.positionFreeSha256}（修订 1 A2）：core 的唯一实现；平台编译产出的 IR 读不进来只可能是内部错误。 */
  private static String positionFreeSha256(String coreJson) {
    try {
      return PolicySourceCompiler.positionFreeSha256(coreJson);
    } catch (CoreIrFormatException e) {
      throw new IllegalStateException("平台编译产出的 Core IR 无法严格读入: " + e.getMessage(), e);
    }
  }
}
