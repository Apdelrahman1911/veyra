# Windows AWK program transport correction — 2026-11-09

Combined run `42643143184` tests `baa837009f355d68a2baf98f9c4d67ef81988df2`.
Android and Linux completed successfully, including the Linux test/package lane.
Both macOS lanes were still running during this correction. Windows failed in
`ProductionFfmpegBuildTest.test_windows_dependency_recipe_runs_the_upstream_awk_filter_with_explicit_bash`
and in independent packaging's identical preflight. Kotlin tests did **not** run
on Windows in this attempt; earlier commentary calling them passed was incorrect.
The prior run's Windows Kotlin results belong to `7b91522`, not this attempt.

The actual Windows console preserves the damaged parser input: the upstream
`gsub(/\/, "/")` arrives as `gsub(/\/, "/")` with only one literal backslash
in the received regular expression. The exact boundary that removed it among
Make, shell and AWK argument handling is not captured. Explicit Bash selection
alone did not fix the toolchain. The early discriminator stopped before the long
configure, retaining the failing program and caret rather than hiding it.

## Correction

The original pinned MSVC filter now lives in `ffbuild/veyra-msvc-deps.awk`, read
with `awk -f`. The filter logic is unchanged; Make's target expansion is passed
with `-v target="$@"`, and the script uses normal AWK `$0`. The generated
`ffbuild/config.mak` adapter replaces only the exact known upstream inline
program, refuses an unknown filter, and creates its script exclusively so stale
or linked output is not overwritten. The source archive is unchanged.

The preflight uses the same generated-config adapter, real Make/Bash/AWK, and
an authored include file with a backslash path plus a path containing spaces.
It checks normalized dependencies and exclusion of the spaced path. Production
applies the adapter after configure and before Make. Windows packages retain the
exact script with the recipe/configuration in corresponding source; reuse checks
its bytes independently of a mutable manifest. No tools were installed and no
runtime source/native fallback was added.

## Local checks and limits

- FFmpeg recipe: **34/34** tests, including real local Make/Bash/AWK execution,
  unknown-program refusal, stale-file preservation, and independently rejecting a
  changed packaged script even if its receipt is resealed.
- Native bundle: **36/36** tests.
- Package notices: **74/74** tests.
- The pinned official FFmpeg source archive size/hash verify, and its `configure`
  contains the exact original program used to derive the file.
- `git diff --check` passes.

These are Linux executions of the applied working tree. No Windows host,
compiler, Gradle/native build, installer or successor workflow was executed here.
Evidence: `.work/verification/task-b-combined-42643143184/`, especially
`windows.log`, `windows-awk-tests.log`, `native-bundle-tests.log`,
`package-notice-tests.log` and `windows-awk-receipt.json`. The initial 30-test pass
before the four additional regressions remains in `windows-awk-tests-initial.log`.
The current Mac jobs continue; no duplicate workflow was dispatched. Retention,
installation, physical output and release acceptance remain separate.
