# Veyra working agreement

## Scope and resumption
- This is Veyra (ڤيرا), a Kotlin/Compose Multiplatform music and podcast application.
- Read `docs/STATE.md`, `docs/PLAN.md`, and `docs/DECISIONS.md` before resuming work. Verify Git status and actual test/artifact state; reports are not evidence by themselves.
- Required targets: Android, iOS, Windows, macOS, Linux. English and Arabic/RTL are required, not optional polish.
- Do not mark the Goal complete while an essential acceptance criterion is missing or unverified. Distinguish builds, automated tests, emulator/simulator runs, physical-device tests, signing, and distribution.

## Strict modification boundary
- All files created, changed, or removed must be within this repository or the authorized `Apdelrahman1911/kmpYT` repository. The working upstream checkout is `.work/kmpYT` (with its own instructions).
- Do not modify system configuration, other repositories, global caches, or shared tool directories. Read-only use of installed tools/SDKs is allowed.
- Project temporary files, tool homes/caches, emulator data, downloads, native build caches and outputs belong under ignored `.work/` or module build directories. Explicitly set process cache/temp locations before using tools that write outside their working directory.
- Cleanup is selective: inspect task-owned disposable outputs first; preserve code, tests, lockfiles, required resume state and latest useful artifacts.
- Never commit credentials, cookies, signed source URLs, private captures, user listening data, or downloaded copyrighted audio. Tests use authored redistributable fixtures or explicit, separately labeled live checks.

## Team and quality
- The user requests all useful parallel capacity. Subagents must be explicitly configured `model: gpt-6-astra`, `reasoning_effort: max` with a non-full context fork when setting overrides. Record accepted configuration and any runtime-attestation limitation honestly.
- Give each agent exclusive file ownership. Cross-review consequential decisions; the lead resolves disagreements with evidence or experiments.
- Research must precede implementation. Native playback, real source access, downloads and offline restart verification are release gates, not mockable UI milestones.
- Use only free existing resources. Veyra's GitHub repository must be public. Use standard free public-repository GitHub Actions runners only; no purchases or billable features.

## Important owner notifications
Use `/usr/local/bin/pocketterminal-notify` for concise milestones, blockers, questions needing an owner decision, meaningful errors, and a verified final summary. Normally only the lead notifies. Use the existing terminal context; never guess another session ID. If absent use `--unlinked` and identify the task. Never send secrets, files, or terminal transcripts. If delivery is rejected/unknown, report in the terminal, do not automatically retry, and continue safe work. Acceptance is not a read receipt. Keep normal terminal replies too.
