## Anzeigelayout

Dieses Paket hat das Format 1.1 und enthält das Anzeigelayout, das der Autor auf der Plattform gespeichert hat:

- `policy/display/display.aster`: der Text für Leser (zum Beispiel der Originalwortlaut ohne Leerzeichen oder mit anderer Zeichensetzung). Er ist **keine Kompiliereingabe**; `aster compile` darauf schlägt fehl. Ausgeführt wird immer `policy/policy.aster`.
- `policy/display/layout.json`: wie jeder Abschnitt des Anzeigetexts dem kanonischen Quelltext entspricht.

Beide Dateien sind von der Signatur und `SHA256SUMS` abgedeckt, und `aster verify` prüft, dass sie zum kanonischen Quelltext passen (Prüfung `display`, mit `--recompile` zusätzlich `display-ir`). CLIs älter als {{cliName}} {{cliVersion}} kennen das Format 1.1 nicht und beenden sich mit `PKG_UNSUPPORTED_FORMAT`; aktualisieren Sie sie.

```
aster show <package.zip>                          # Anzeigetext ausgeben; stderr nennt reine Layouttexte und Literalmakros
aster show <package.zip> --canonical              # kanonischen Quelltext ausgeben
aster run <package.zip> --display poem.txt --input input.json
```

`run --display` stellt aus einem bearbeiteten Anzeigetext den kanonischen Quelltext wieder her, kompiliert und führt ihn aus. Nur Inhalte dürfen sich ändern: Zeichensetzung, Zeilenumbrüche und Schlüsselwort-Aliasse sind Layout und müssen bleiben. Ist die Zuordnung mehrdeutig (`DISPLAY_AMBIGUOUS`) oder unmöglich (`DISPLAY_UNMAPPABLE`), wird nichts ausgeführt. Speichern Sie die Datei mit LF-Zeilenenden; ein vom Editor angefügter letzter Zeilenumbruch wird ignoriert. Das ausgeführte Programm ist nicht von der Paketsignatur abgedeckt: `meta.display.matchesPackage` gibt an, ob es dem signierten Quelltext entspricht, `meta.coreIrSha256` ist `null`, und der wiederhergestellte Quelltext wird zuerst auf stderr ausgegeben. `aster show <package.zip> --from-display poem.txt` zeigt den wiederhergestellten Quelltext, ohne ihn auszuführen.
