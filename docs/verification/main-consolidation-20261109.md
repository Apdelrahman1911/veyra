# Main checkout consolidation — 9 November 2026

The owner requested one project checkout on `main`, no additional worktrees and at most 3 GiB including `.git`. Useful source/history, required assets, signing materials and irreplaceable evidence must survive. No force worktree removal, interruption of other agents or moving disposable files elsewhere on the VPS is permitted.

## Source selection and history

The active application/build/test/workflow tree was selected from `cf78955d4363599f42f40ce539e3608a31c4040d` (PR27). The complete five-lane trial installer run `42688713102` passed at that exact revision. This is packaging/readback evidence, not physical installation, signing/notarization, listening or final release acceptance.

All 81 branch/remote tips from the initial audit are ancestors of consolidated `main`. The latest source was merged normally. Older bootstrap workflow, source snapshots and superseded experiment histories were then deliberately merged with the `ours` strategy after comparison, preserving their commits without restoring their old implementation over the verified tree. Before adding this note, the complete main tree equaled `cf78955` byte-for-byte.

Five previously dirty source trees were committed without dropping tracked or untracked edits:

| Original checkout | Preserved snapshot |
| --- | --- |
| `/root/projects/Veyra` | `eb9da8222327c107db90a25084b60d3b064596c7` |
| `/root/.t3/worktrees/Veyra/t3-98140935` | `7501763f09495e072c81951e09af47c95d0af2e9` |
| `/root/projects/Veyra/.work/veyra-acquisition` | `ecfe9bac94914bf3c525c832b4093d93ddb9cb64` |
| `/root/projects/Veyra/.work/veyra-download-scheduling` | `7dec5d2ca373eb8db92d36892adc28ef7594e6e4` |
| `/root/projects/Veyra/.work/veyra-d010-compile` | `1450eb1a03064835efe5a9fcb57a405d0106335d` |

- The root's legal previews were superseded by the current generator/index; two files already matched the selected final tree exactly. Their original bytes remain in the snapshot.
- The recovery-regression and download-scheduling snapshots contain earlier fixture/coordinator forms. Most Android owner/scheduler/file-lease additions already match the final source exactly. Final canonical recovery/ownership implementations and strengthened tests take precedence.
- The acquisition snapshot preserves original schema-2 benchmark/timing fixtures. They remain historical tools requiring schema-3 adaptation, not newly passing schema-3 tests.
- The detached `.work/veyra-d010-compile` copy was a compilation/staging assembly containing older execution, collection and metadata-producer experiments. Its 285 local path changes are preserved in history. D010/D011 capabilities that remain unfinished are not activated merely because staging files exist; no completeness claim is made.
- The older app-composition port branch and the root continuation note were already superseded by subsequent correction work. Their complete commits remain reachable.

All local upstream kmpYT heads/branches/tags from the removed copies are retained in the primary submodule repository under `refs/heads/retained/<checkout>/...` and `refs/tags/retained/<checkout>/...`. The active gitlink remains `6805d4db8077650992d342e5395b1f0d7c80e984`. Preserving unaccepted upstream experiments does not change that tested pin.

## Verification before reclamation

- 85 authored focused tests passed from the actual main checkout: workflow 22, package manifest 20, desktop legal 8, retention 18 and tooling 17.
- Actual offline `:composeApp:tasks --all` Gradle configuration passed in 53 seconds under `vps-build` light admission, in-process Kotlin and the existing shared project-lock inode. No dependency installation or full native rebuild was needed.
- Veyra and primary-submodule full Git object checks passed.
- All initial Veyra branch tips are reachable from main; source snapshot and upstream-ref identities are recorded locally.

## Evidence and cleanup

Local operational inventories/check logs are under `.work/consolidation-20261109/`. Important ignored working notes, staging, fixture state and test reports are preserved inside this same project in `.work/retained-evidence/consolidation-20261109/`. The archive stores each distinct content hash once; its manifest maps original paths to payload hashes. Complete archive readback and unchanged-original checks are required before deleting any source copy. Archives outside this repository from earlier storage coordination remain protected and are not used to meet the size target.

Cleanup completed with **one Veyra checkout on `main`, zero additional worktrees**, and the required kmpYT submodule at its original tested pin. All 31 additional Veyra worktrees and eight upstream worktrees were removed with ordinary `git worktree remove`, never `--force`. For initialized submodules, normal deinitialization alone left Git administrative repositories that still blocked removal; their refs/objects and complete metadata were preserved and verified, only the redundant per-worktree metadata was deleted, and ordinary removal then succeeded. Two redundant standalone primary clones were removed only after their source/history, metadata and ignored evidence were preserved; they had no remaining secondary worktrees.

Final allocated project size, including `.git`, hidden files and the retained local evidence: **3,052,929,024 bytes (2.843 GiB)**. Apparent size was 2,993,125,966 bytes. Both are below the 3 GiB limit. Final documentation commits add only a small amount of Git/text data; the final local receipt records the post-push measurement as well. No disposable data was moved elsewhere on the VPS to meet the target; pre-existing external fixture archives stayed untouched.

The evidence store contains 217,624 original-path entries, full submodule/clone metadata and five supplemental reports. Nine downloaded Chromium/Node/FFmpeg vendor-tool archives were removed as regenerable caches; their provenance, license/inspection records, sizes and hashes remain. All other selected source/staging/working/test-state payloads remain recoverable. The complete archive was read back, every original file rehashed before removal, the final store checksums reverified after cleanup, and an actual archived XML report restored and hash-checked successfully. Restoration instructions are `.work/retained-evidence/consolidation-20261109/README.md`; the local helper is `.work/consolidation-20261109/restore-evidence.py`.

One union of offline dependency artifacts and the installed Gradle distribution remain. Existing home/Android signing/configuration state, source assets, build configuration, the wrapper, full Git history and the shared project-lock inode remain intact. Old derived caches, outputs and duplicate evidence trees were removed after preservation. Final main/submodule Git object checks and clean-status checks passed. Shared caches and other projects were not modified.
