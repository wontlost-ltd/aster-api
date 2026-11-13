package io.aster.guard.provenance;

import io.aster.policy.evidence.EvaluationEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据形状映射（ADR 0046 §11.6 第 3、4 条）：写评估事件时把引擎源码路径映射为 {@code context.<实际匹配键>.<path>}，
 * 并执行 {@link EvaluationEvidence#MAX_INPUT_REFS} 项 / {@link EvaluationEvidence#MAX_INPUT_REFS_BYTES} 字节上限。
 */
class EvidenceInputRefPathsTest {

  @Test
  void namedInputWithEnglishKey() {
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("action", "applicant.age"), "req"))
        .containsExactly("context.req.action", "context.req.applicant.age");
  }

  /** 本地化写法的键（第一个参数由它实际匹配）原样进入路径前缀。 */
  @Test
  void namedInputWithLocalizedKey() {
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("betrag"), "Antrag"))
        .containsExactly("context.Antrag.betrag");
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("金额"), "申请"))
        .containsExactly("context.申请.金额");
  }

  /** 根路径 {@code ""}（标量参数或整条记录被消费）映射为 {@code context.<键>}。 */
  @Test
  void rootPathMapsToMatchedKey() {
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of(""), "amount")).containsExactly("context.amount");
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("", "x"), "req"))
        .containsExactly("context.req", "context.req.x");
  }

  /** 位置数组输入没有匹配键：任何引用都映射为 {@code *}。 */
  @Test
  void positionalInputIsStar() {
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("action"), null)).containsExactly("*");
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of(""), null)).containsExactly("*");
  }

  /** 匹配键缺失或无法用点路径无歧义表示（空、含点、含控制字符）→ {@code *}。 */
  @Test
  void missingOrUnrepresentableKeyIsStar() {
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("x"), "")).containsExactly("*");
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("x"), "a.b")).containsExactly("*");
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("x"), "a\nb")).containsExactly("*");
  }

  @Test
  void starIsExclusive() {
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("*"), "req")).containsExactly("*");
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of("a", "*"), "req")).containsExactly("*");
  }

  /** 规则不读输入：{@code []} 照写（不需要匹配键）；IR 未提供（null）原样返回 null。 */
  @Test
  void emptyStaysEmptyAndNullStaysNull() {
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of(), null)).isEmpty();
    assertThat(EvidenceInputRefPaths.evidenceShape(List.of(), "req")).isEmpty();
    assertThat(EvidenceInputRefPaths.evidenceShape(null, "req")).isNull();
  }

  @Test
  void moreThan32EntriesIsStar() {
    assertThat(EvaluationEvidence.MAX_INPUT_REFS).isEqualTo(32);
    List<String> refs = new ArrayList<>();
    for (int i = 0; i < 33; i++) {
      refs.add("f" + i);
    }
    assertThat(EvidenceInputRefPaths.evidenceShape(refs, "r")).containsExactly("*");
    assertThat(EvidenceInputRefPaths.evidenceShape(refs.subList(0, 32), "r")).hasSize(32);
  }

  /** 上限按映射后 JSON 数组的 UTF-8 字节数计：多字节字符按字节而非字符计。 */
  @Test
  void moreThanByteCapIsStar() {
    // 每项 "context.k.<50 个汉字>"：汉字 3 字节
    String han = "字".repeat(50);
    List<String> refs = List.of("a" + han, "b" + han, "c" + han);
    // 每项 11 + 150 字节，加引号 163；三项 3 × 163 + 2 个逗号 + 2 个方括号 = 493 ≤ 512，四项 657 超出
    assertThat(EvidenceInputRefPaths.evidenceShape(refs, "k")).hasSize(3);
    List<String> more = List.of("a" + han, "b" + han, "c" + han, "d" + han);
    assertThat(EvidenceInputRefPaths.evidenceShape(more, "k")).containsExactly("*");
    assertThat(EvaluationEvidence.MAX_INPUT_REFS_BYTES).isEqualTo(512);
    assertThat(EvaluationEvidence.inputRefsJsonBytes(refs.stream().map(r -> "context.k." + r).toList()))
        .isEqualTo(493);
    assertThat(EvaluationEvidence.inputRefsJsonBytes(more.stream().map(r -> "context.k." + r).toList()))
        .isGreaterThan(512);
  }
}
