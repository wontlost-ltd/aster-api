# {{moduleName}} – Aster-Richtlinienpaket (Form B, {{target}})

Dieses Paket enthält **Quelltext und Metadaten** der Version {{version}} der Richtlinie `{{moduleName}}`, exportiert aus aster-lang.cloud. Es enthält keinen ausführbaren Code: Eine separat installierte Aster-CLI prüft die Signatur, übersetzt den Quelltext lokal neu und prüft, dass das Ergebnis dieselbe Core IR ist, die die Plattform freigegeben hat (`coreIr.positionFreeSha256`). Erst prüfen, dann ausführen.

## CLI installieren

- Node (ab 24): den Einzeldatei-Runner `aster.mjs` und `aster.mjs.sha256` von {{tsReleaseUrl}} herunterladen, mit `sha256sum -c aster.mjs.sha256` prüfen und mit `node aster.mjs …` starten (kein `npm install` nötig).
- JRE (Java 25): `aster-cli-{{jreVersion}}.jar` und `aster-cli-{{jreVersion}}.jar.sha256` von https://github.com/aster-cloud/aster-lang-truffle/releases/tag/v{{jreVersion}} herunterladen, mit `sha256sum -c aster-cli-{{jreVersion}}.jar.sha256` prüfen und mit `java --sun-misc-unsafe-memory-access=allow -jar aster-cli-{{jreVersion}}.jar …` starten (der Schalter unterdrückt nur eine JDK-25-Warnung von GraalVM Truffle).

Das Paket wurde für `{{cliName}}` {{cliVersion}} erstellt. Meldet `verify` `PKG_IR_DRIFT`, installieren Sie genau diese Version.

## Prüfen und ausführen

```
aster verify <package.zip> --probes
aster run <package.zip> --input input.json
```

`aster` steht dabei für `node aster.mjs` (Node) bzw. `java --sun-misc-unsafe-memory-access=allow -jar aster-cli-{{jreVersion}}.jar` (JRE).

Der Einstiegspunkt ist `{{entry}}`; die Eingabeform steht in `schema/input.json` (`#/$defs/entry.{{entry}}`). Decimal-Werte sind Dezimalzeichenketten; Long-Werte über 2^53 sind Dezimalzeichenketten oder exakte Ganzzahlen.

## Grenzen

- Dieses Paket ist **kein** Guard-Ausführungspunkt: Die Ausführung protokolliert nichts, stellt keine Quittung aus und erzwingt keine Freigabe. `require_approval` und `escalate` sind nur Rückgabewerte.
- Fakten werden **nicht** geprüft: Eingaben werden so übernommen, wie sie sind.{{factSourcesNote}}
- `tests/probes.json` ist eine Stichprobe von Eingaben, die auf der Plattform ausgewertet wurden, kein Gleichwertigkeitsbeweis.
- Ein heruntergeladenes Paket kann nicht widerrufen werden; prüfen Sie bei Bedarf die ausgeführte Version gegen die Plattform.
