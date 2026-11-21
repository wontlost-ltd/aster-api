## Display layout

This package has format version 1.1 and carries the display layout the author saved on the platform:

- `policy/display/display.aster`: the text meant for readers (for example the original wording without spaces or with different punctuation). It is **not a compile input**; running `aster compile` on it fails. What runs is always `policy/policy.aster`.
- `policy/display/layout.json`: how each piece of the display text maps onto the canonical source.

Both files are covered by the signature and `SHA256SUMS`, and `aster verify` checks that they agree with the canonical source (check `display`, plus `display-ir` with `--recompile`). CLIs older than {{cliName}} {{cliVersion}} do not know format 1.1 and exit with `PKG_UNSUPPORTED_FORMAT`; upgrade them.

```
aster show <package.zip>                          # print the display text; stderr lists layout-only text and literal macros
aster show <package.zip> --canonical              # print the canonical source
aster run <package.zip> --display poem.txt --input input.json
```

`run --display` rebuilds the canonical source from an edited display text, then compiles and runs it. Only content may change: punctuation, line breaks and keyword aliases are layout and must stay as they are. Nothing runs when the edit maps in more than one way (`DISPLAY_AMBIGUOUS`) or not at all (`DISPLAY_UNMAPPABLE`). Save the file with LF line endings; a final newline added by the editor is ignored. The program that runs is not covered by the package signature: `meta.display.matchesPackage` says whether it is equivalent to the signed source, `meta.coreIrSha256` is `null`, and the rebuilt source is printed to stderr first. `aster show <package.zip> --from-display poem.txt` shows the rebuilt source without running it.
