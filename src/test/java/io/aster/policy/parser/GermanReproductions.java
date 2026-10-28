package io.aster.policy.parser;

import java.util.List;
import java.util.Map;

/**
 * ADR 0051 §1 的四个生产复现：德语源码 + 领域词汇，供编译、evaluate-source、What-If、Cedar 导出各入口共用。
 * 词汇形同 cloud 发送的 DomainVocabulary JSON（mapping 带 kind，字段与枚举成员带 parent）。
 */
public final class GermanReproductions {

    private GermanReproductions() {
    }

    /**
     * 一个复现：按 entry 以 context（命名参数）执行应得到 expected。
     * canonicalNames 为编译产物中必须出现的规范名，writtenNames 为不得作为声明名出现的本地化写法。
     */
    public record Repro(String name, String source, Map<String, Object> vocabulary, String entry,
                        Map<String, Object> context, Object expected,
                        List<String> canonicalNames, List<String> writtenNames) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** 复现 1：struct Stufe↔Level 与 field stufe↔level 并存。 */
    public static final Repro TYPE_AND_FIELD = new Repro("type-and-field", """
        Modul versicherung.stufe.

        Definiere Stufe hat punkte als Ganzzahl.

        Definiere Fahrer hat stufe als Stufe, alter als Ganzzahl.

        Regel bewerten gegeben f als Fahrer, liefert Ganzzahl:
          gib zurueck f.stufe.punkte plus f.alter.
        """,
        vocabulary(
            List.of(struct("Level", "Stufe"), struct("Driver", "Fahrer")),
            List.of(field("points", "punkte", "Level"), field("level", "stufe", "Driver"),
                field("age", "alter", "Driver")),
            List.of(), List.of()),
        "bewerten", Map.of("f", Map.of("level", Map.of("points", 3), "age", 40)), 43,
        List.of("Level", "Driver"), List.of("Stufe", "Fahrer"));

    /** 复现 2：规则 hoch 与枚举成员 Hoch（Stufe.High）并存，以限定写法引用成员。 */
    public static final Repro ENUM_MEMBER_VS_RULE = new Repro("enum-member-vs-rule", """
        Modul versicherung.einstufung.

        Definiere Stufe als eines von Hoch, Niedrig.

        Regel hoch gegeben x als Ganzzahl, liefert Ganzzahl:
          gib zurueck x.

        Regel einstufen gegeben x als Ganzzahl, liefert Boolesch:
          wenn x groesser als 10:
            gib zurueck einstufung(Stufe.Hoch).
          gib zurueck einstufung(Stufe.Niedrig).

        Regel einstufung gegeben s als Stufe, liefert Boolesch:
          gib zurueck s entspricht Stufe.Hoch.
        """,
        vocabulary(
            List.of(struct("Level", "Stufe")),
            List.of(),
            List.of(function("high", "hoch")),
            List.of(enumValue("High", "Hoch", "Level"), enumValue("Low", "Niedrig", "Level"))),
        "einstufen", Map.of("x", 11), true,
        List.of("Level", "high"), List.of("Stufe", "hoch"));

    /** 复现 3：参数 fahrer 与 struct Fahrer 同词，参数不被改写。 */
    public static final Repro PARAM_VS_STRUCT = new Repro("param-vs-struct", """
        Modul versicherung.fahrer.

        Definiere Fahrer hat alter als Ganzzahl.

        Regel pruefen gegeben fahrer als Fahrer, liefert Ganzzahl:
          gib zurueck fahrer.alter.
        """,
        vocabulary(
            List.of(struct("Driver", "Fahrer")),
            List.of(field("age", "alter", "Driver")),
            List.of(), List.of()),
        "pruefen", Map.of("fahrer", Map.of("age", 42)), 42,
        List.of("Driver"), List.of("Fahrer"));

    /** 复现 4：同一本地化字段名 alter 在两个 struct 中对应不同规范字段。 */
    public static final Repro SAME_FIELD_TWO_STRUCTS = new Repro("same-field-two-structs", """
        Modul versicherung.alter.

        Definiere Fahrer hat alter als Ganzzahl.

        Definiere Fahrzeug hat alter als Ganzzahl.

        Regel summe gegeben fahrer als Fahrer, wagen als Fahrzeug, liefert Ganzzahl:
          gib zurueck fahrer.alter plus wagen.alter.
        """,
        vocabulary(
            List.of(struct("Driver", "Fahrer"), struct("Vehicle", "Fahrzeug")),
            List.of(field("age", "alter", "Driver"), field("vehicleAge", "alter", "Vehicle")),
            List.of(), List.of()),
        "summe", Map.of("fahrer", Map.of("age", 30), "wagen", Map.of("vehicleAge", 5)), 35,
        List.of("Driver", "Vehicle"), List.of("Fahrer", "Fahrzeug"));

    public static final List<Repro> ALL =
        List.of(TYPE_AND_FIELD, ENUM_MEMBER_VS_RULE, PARAM_VS_STRUCT, SAME_FIELD_TWO_STRUCTS);

    /** 复现 2 的裸写形式：Hoch 同时命中规则 hoch 与期望枚举成员 → E804，必须写限定名。 */
    public static final String BARE_ENUM_MEMBER = """
        Modul versicherung.einstufung.

        Definiere Stufe als eines von Hoch, Niedrig.

        Regel hoch gegeben x als Ganzzahl, liefert Ganzzahl:
          gib zurueck x.

        Regel einstufen gegeben x als Ganzzahl, liefert Stufe:
          gib zurueck Hoch.
        """;

    /**
     * Cedar 导出用的德语 guard：类型 Anfrage↔GuardRequest 与参数 anfrage 同词（复现 3），
     * 字段 aktion 按 GuardRequest 解析为 action，规则 entscheiden 解析为 decide。
     */
    public static final String GUARD_SOURCE = """
        Modul waechter.deutsch.

        Definiere Anfrage hat principal_id, principal_type, principal_roles, aktion, resource_type, resource_id, context, agent_model.

        @id("DE-001")
        Regel entscheiden gegeben anfrage als Anfrage, liefert Verdict:
          wenn anfrage.aktion entspricht "refund":
            gib zurueck Verdict.deny("refund blocked").
          gib zurueck Verdict.allow().
        """;

    public static final Map<String, Object> GUARD_VOCABULARY = vocabulary(
        List.of(struct("GuardRequest", "Anfrage")),
        List.of(field("action", "aktion", "GuardRequest")),
        List.of(function("decide", "entscheiden")),
        List.of());

    public static Map<String, Object> vocabulary(List<Map<String, Object>> structs, List<Map<String, Object>> fields,
                                                 List<Map<String, Object>> functions,
                                                 List<Map<String, Object>> enumValues) {
        return Map.of("id", "adr0051.repro", "name", "ADR 0051 Repro", "locale", "de-DE", "version", "user",
            "structs", structs, "fields", fields, "functions", functions, "enumValues", enumValues);
    }

    public static Map<String, Object> struct(String canonical, String localized) {
        return Map.of("canonical", canonical, "localized", localized, "kind", "struct");
    }

    public static Map<String, Object> field(String canonical, String localized, String parent) {
        return Map.of("canonical", canonical, "localized", localized, "kind", "field", "parent", parent);
    }

    public static Map<String, Object> function(String canonical, String localized) {
        return Map.of("canonical", canonical, "localized", localized, "kind", "function");
    }

    public static Map<String, Object> enumValue(String canonical, String localized, String parent) {
        return Map.of("canonical", canonical, "localized", localized, "kind", "enum_value", "parent", parent);
    }
}
