# Android Stage 0: no-guest containment review

**Status: three lead-authorized preflights remain incomplete.** The first
rejected inert `sit0`; the second rejected the observed IPv6 table omission.
The reviewed network repair passed the third attempt's network checks and
wrote `network.json`, but a later `PermissionError` has no retained operation
or errno. The exact failing operation is **unestablished**; absent Android
traces do not prove that the mount guard never ran. A diagnostic-only source
repair records fixed failure phases without changing any safeguard. No fourth
preflight or guest is authorized. No emulator guest or instrumented test has
executed, and no physical-audio result is implied.

`scripts/android-instrumentation.sh --run` exits **78** before sourcing the tool
environment or launching a service. There is deliberately no boot/test path.
CI runs only `scripts/tests/test_android_preflight.py`, not Stage 0 or its
separately opted-in tracer experiment. Upstream native gates remain unchanged.

## Actual attempts and proposed retry

The first attempt's `.work/android/runs/preflight-sohl5ot4/summary.json` records
`probesCompleted=false`, `callerNamespaceMountsAndAdbUnchanged=true` and an empty
`remainingPrivateNamespaceProcesses` list. Its namespace-launch log rejects a
non-loopback interface **before** the first `ip link set` or any Android command.
The lead's separate read-only inspection,
`.work/verification/android-stage0-netns-inspection.json`, found only DOWN `lo`
and the kernel-created DOWN `sit0`, both unaddressed, in the new namespace.
Its single empty route report is **not** treated as proof of both route families.
This is a safe rejection, not completed containment or Android-probe evidence.

The second attempt, `.work/android/runs/preflight-m19r6p1x/`, retained all eight
bounded before/after reports. They show only `lo` and unchanged inert `sit0`,
initially empty route lists in both families, then loopback addresses and kernel
routes after only private `lo up`. The IPv4 routes explicitly report
`table:"local"`; the only IPv6 route is exactly:

```json
{"type":"local","dst":"::1","dev":"lo","protocol":"kernel","metric":0,"flags":[],"pref":"medium"}
```

The previous validator rejected its missing `table` field. The second summary
again records incomplete probes and an unchanged caller snapshot. Neither run
produced `network.json`, `guarded-result.json` or Android traces. The existing
outer runner invokes owned-tree teardown, but without `network.json` its final
namespace-wide sweep is **not performed**: `remainingPrivateNamespaceProcesses`
is the initialized empty list, not independent proof of a completed sweep.
No Android endpoints or write attempts were available to review.

The third attempt, `.work/android/runs/preflight-kwm1mu9l/`, ran on 2026-11-06
10:27:09–10:27:11 UTC and exited 1. All nine frozen inputs were unchanged;
all eight raw network reports exactly match the complete `network.json`.
The network namespace differs from the caller's, so the final namespace-sweep
condition **was reached** this time and observed no remaining live process.
The summary also records the caller snapshot unchanged. This is not a complete
native preflight: `guarded-result.json` and Android traces are absent, and the
child log retains only the `PermissionError` class.

Read-only review found `linux-namespace.sh` mode **0700**, Git mode **100755**,
and a change time preceding the attempt; the nonexecutable-file hypothesis is
not supported. A silent guard can reach `assert_guard` after dropping
capabilities, where even a parent-namespace read may be refused. Neither this
alternative nor a failed direct `execv` is established by the old log. No chmod,
interpreter substitution, skipped parent check or capability change is justified
as a verified repair. The independent late namespace scan found 0 matches among
179 inspected processes with 0 read failures, but without a held namespace FD
that later observation is not contemporaneous teardown proof. Review records:
`.work/mobile-evidence/android-stage0-third-review/`.

### Failure-only diagnostics, not a containment bypass

After validating the owned run path, the runner can retain `failure-phase.json`
with **only** schema version, a fixed phase, a whitelisted error class and a
bounded numeric errno (or null) for an `OSError` or `ValueError` (including
`PreflightError`). Other exception classes remain unchanged and are not covered
by this diagnostic. Phases distinguish the direct guard exec,
guard input reads, self network/mount reads, parent mount read, mount-separation
check, protected mounts, service aliases, scratch identities, capabilities,
native environment and pre-probe listeners. They contain no exception message,
filename, stack, environment or key material. Unknown phase labels are rejected.

Every original error is rethrown. A receipt cannot overwrite an earlier one;
failure to retain it emits one fixed warning and preserves the original refusal.
A successful phase creates no diagnostic success receipt. Invalid run paths do
not authorize writing a diagnostic there. All namespace, mount, capability and
listener checks remain mandatory, in their original order, before the first
Android probe. The patch does not change the guard, interpreter, permissions,
network validators, native commands, process ownership or `--run` gate. Tests
mock all exec/process/socket/namespace/mount operations; no new preflight follows
automatically from a passing source test.

### Why an omitted IPv6 table is not table-255 proof

Upstream iproute2 **v6.1.0** [`print_route` and table filtering](https://github.com/iproute2/iproute2/blob/v6.1.0/ip/iproute.c)
omit the table when it is zero or the default main table without `-details`;
`table all` clears the table filter. Its IPv6 filter also handles single-table
kernels by route type. Linux **v6.1.72** [`ip6_fib.h`](https://github.com/gregkh/linux/blob/v6.1.72/include/net/ip6_fib.h)
aliases `RT6_TABLE_LOCAL` to `RT6_TABLE_MAIN` when IPv6 multiple tables are
disabled, and [`addrconf_f6i_alloc`](https://github.com/gregkh/linux/blob/v6.1.72/net/ipv6/route.c)
uses that table macro for local addresses. These sources explain a compatible
output shape; they do **not** attest this host's kernel build configuration or
the Ubuntu-patched binary's source equivalence. Read-only package metadata was
`iproute2 6.1.0-1ubuntu6.4`; the kernel release was `6.1.72`.

Accordingly, only the exact kernel **local-type `::1` route on `lo`** above may
omit `table`; explicit `table:"local"` remains accepted. A present `main`,
numeric table ID, null or any other value remains rejected. This is a narrow
observed-format allowance, not an inference or insertion of table 255. IPv4
still requires explicit `table:"local"` on every route. All address, route
count/type/destination/interface/protocol/metric/preference/flag and unknown-key
checks are unchanged. Raw reports and the final receipt are never normalized.
Source copies, hashes and the read-only receipt comparison are retained in
`.work/verification/android-stage0-ipv6-table-review/`.

Only after the lead approves the exact source hashes and execution slot:

```sh
scripts/android-instrumentation.sh --preflight
```

The wrapper enters the repository-owned environment. The Python runner requires
Linux, readable `/proc`, pidfd signal support, existing `unshare`/`ip`/`strace`,
and the reviewed read-only SDK: adb **37.0.1**, emulator **37.1.11**, API31
`default/x86_64` image revision **3**, with all required image files present.
It installs nothing. SDK variables must agree. Missing tools, permissions,
storage, or a changed revision fail closed; no sudo, sysctl, device, or SDK fixup.

Inherited ADB/display/audio/bus configuration is rejected. A host coredump
pipeline or unexpected core path, USB bus, or PCM device also stops the attempt.
This reviewed VPS has an unrelated host ADB server on **5037**: never query,
connect to, reuse, or kill it. Read-only `/proc` identity snapshots do not issue
an ADB command. Missing `/dev/kvm` is not acceleration or guest-boot evidence.

## Boundaries and probe order

1. Create a unique private `.work/android/runs/preflight-*/` with owned homes,
   keys, AVD/discovery paths, runtime/config/cache/temp directories and logs.
   Record caller network/mount identities, mountinfo SHA-256 and host ADB
   PID/start-time/UID/namespace identities without command lines or environments.
2. Create a private network namespace. Before **any** network change, compare
   it with both the recorded caller and actual parent's namespace. Read detailed
   links, addresses, and **separate IPv4/IPv6 route lists from all tables**.
   Require DOWN, unaddressed `lo`, optionally accompanied by the exact reviewed
   inert kernel `sit0`: index 2, SIT identity, only `NOARP`, DOWN, null underlying
   link, zero promiscuity/allmulti, unspecified local/remote tunnel endpoints,
   and no addresses. Both initial route lists must be empty. Reject missing,
   forged, attached, active, duplicate, additional or unknown configuration.
   Recheck the same new namespace immediately before bringing **only `lo`** up,
   then recheck namespace, all links/addresses and both route families afterward.
   Interface identities must remain unchanged; `sit0` stays untouched/inert.
   Only the normal `127.0.0.1/8` and optional `::1/128` addresses and their
   kernel local/broadcast `lo` routes may appear. No default, non-loopback,
   gateway or next-hop route is permitted. A missing IPv6 report is not an
   empty/disabled IPv6 state. No host interface/firewall or `sit0` change is made.
   Each read has a five-second deadline and child-only 64 KiB regular-file
   output limit, with bounded JSON/list validation. The exact reviewed schema
   intentionally fails closed on unknown metadata, including benign drift.
   Per-phase raw reports are retained under the owned logs; a successful
   `network.json` retains before/after snapshots and an `inertKernelSit0` marker.
   Its `loopbackOnly` key means only loopback is active/addressed/routed, not
   that the permitted inactive kernel interface is absent.
3. Enter `linux-namespace.sh --mask-run <owned-run>/service-runtime -- …`.
   The existing private mount guard first verifies namespace separation; then
   exposes the repository writable with protected host mounts read-only and
   repository-backed `/tmp`, `/var/lib/xkb`, and `/dev/shm` scratch aliases.
   This optional mode additionally bind-masks **only `/run`**, read-only, using
   an empty private owned directory. `/var/run` must be its actual alias and
   resolve to the same empty read-only backing. A different layout fails closed.
   No-mask/display callers retain the original effective-mount check.
4. Drop effective/bounding capabilities and set NoNewPrivs. Recheck namespace,
   protected mount and backing identities. Set final per-run Android/native
   environment **after** nested environment wrappers; the separate owned
   `XDG_RUNTIME_DIR` remains writable. Remove inherited service/loader settings.
5. Trace only these no-guest commands: `adb help`, `emulator -help-all`,
   `emulator -accel-check`, and `adb keygen <owned-key-path>`. The acceleration
   probe may return 0 or 1; neither proves a guest can boot. Help must confirm
   the required explicit server flags before proceeding.
6. Start only a foreground private ADB server with `-L tcp:127.0.0.1:5038`,
   a deliberately unmatched `--one-device`, and `server nodaemon`. Require
   one literal IPv4-loopback listener whose socket inode belongs to a tracked
   process. A bounded Python client sends only `host:version` to **5038**.
   Never use a default-port ADB client or `kill-server`.
7. Tear down exact owned processes and reject leftover private listeners or
   live processes in the unique private namespace. Recompare the caller
   namespace/mount/host-ADB snapshot. Retain the private logs and summary.

## Process lifetime and limits

Descendants are discovered from bounded `/proc/*/stat` parent maps, with fresh
PID/start-time/UID, parent lineage and chronology validation. Some Linux kernels
omit `/proc/<pid>/task/<tid>/children`; relying on that optional file caused a
real authored regression failure and is no longer the implementation.
Signals use revalidated **pidfds**, never reusable numeric-PID, process-group,
process-name, or global kill commands. A still-live owned process whose namespace
cannot be verified is not accepted as successfully torn down.

Polling alone cannot observe every immediate double-fork. Every Android probe
therefore runs under `strace -f --kill-on-exit --interruptible=2`. The kernel's
tracee-exit-kill behavior covers helpers reparented before a scan. The explicit
interruptibility matters: with `-o FILE PROG`, this installed strace otherwise
ignores TERM. Bounded pidfd TERM→KILL escalation remains in the tracker. No PID
namespace, cgroup or system-wide process setting is changed.

Tracing is limited to file paths and connect/bind/listen/socket-endpoint syscalls;
it excludes read/write/send/receive buffers and key contents. Traces still need
private manual review. Limits include core size 0, individual files 16 MiB,
aggregate log budget 64 MiB, 30/35 CPU seconds, 1536 MiB virtual address space,
at most two allowed CPUs, lowered priority, 20-second probe deadlines,
10-second server readiness, and a 120-second outer watchdog.

## Actual regression evidence (2026-11-06)

- `scripts/dev.sh python3 -B scripts/tests/test_android_preflight.py`: authored
  validation/protocol/lineage/pidfd/mask tests, plus real **Python-only** child
  teardown and preservation of an unrelated Python child. No Android tools,
  tracer, namespace creation, or mount operation are launched by this suite.
  The new network regressions use pure fixtures and mock **every** execution
  point: exact inert/absent `sit0`, missing/forged/up/addressed/attached state,
  nonempty initial IPv4/IPv6 routes, non-loopback post-activation routes,
  malformed/oversized/duplicate-key JSON, namespace changes, read/mutation
  timeouts, and refusal to enter the mount guard on any rejected state.
  The inert-`sit0` repair's first scoped run passed **44/44**, with no skips:
  `.work/verification/android-stage0-sit0-review/tests-first.log`. Frozen source
  copies, the scoped diff and input hashes are retained beside that log. CI
  independently reviewed those hashes and passed **17/17** pure/mocked network
  methods before the lead's second attempt.
- The IPv6-table repair passed **46/46 selected pure/mocked methods**, including
  **21 network methods**, with no failures/errors/skips. Four new methods cover
  the observed omission, exact optional values, unchanged IPv4 requirements and
  raw/final receipt preservation; rejected post-UP table/gateway cases still
  cannot enter the guard. All eight default fixture reports were mechanically
  compared with the second attempt and match. Absent-`sit0`, disabled-IPv6 and
  explicit-table variants remain authored. The old validator's rejection was
  reproduced purely against the retained reports; the repaired validator accepts
  those reports without alteration. Evidence:
  `.work/verification/android-stage0-ipv6-table-review/{pure-tests-frozen.log,actual-receipt-review.json}`.
  This was **not the full 48-method suite**: its actual Python-child teardown
  and shell `--run` gate methods were deliberately not selected in this
  source-only phase. Unexpected process launches, signals and connections were
  guarded by failing mocks. No new `ip`, namespace, Android, tracer or guest
  execution occurred. CI independently passed 21/21 network methods; root/CI
  source review approved that narrow change before the third actual attempt.
- The diagnostic-only patch passed **55/55 selected pure/mocked methods**,
  with 0 failures/errors/skips, in
  `.work/verification/android-stage0-diagnostic-review/pure-tests-first.log`.
  Nine new methods verify distinct exec/self/parent/pre-probe refusal phases,
  exact unchanged guard argv, sanitized class/errno fields, unknown-label and
  invalid-run rejection, no success receipt, and preservation of the original
  error when a diagnostic write fails or would overwrite a prior receipt.
  Unsafe namespace/mount/service/capability/listener fixtures still cannot
  reach even the mock Android probe. All nine inputs were unchanged during
  that test run. This is **not the full 57-method suite**: the actual Python
  child teardown and shell `--run` gate methods were again deliberately not
  selected. The harness additionally denies unexpected process, exec, signal,
  socket, namespace, resource-mutation and host-listener operations; none were
  attempted. No preflight or native service ran. Independent review is required
  before a separately authorized actual attempt.
- The lead separately authorized `VEYRA_RUN_AUTHORED_TRACE_TESTS=1
  scripts/dev.sh python3 -B scripts/tests/test_android_tracer_teardown.py`.
  Both exact-tracer TERM and abrupt KILL drained deliberately untracked,
  already-reparented Python double-fork descendants while an unrelated child
  survived. This is kernel/tracer containment evidence, not Android evidence.
  Log: `.work/verification/android-authored-tracer-tests.log`; private traces
  and identity-only receipts live under `android-authored-tracer-*/` beside it.
  The earlier default-interruptibility TERM timeout is retained separately,
  rather than represented as a pass.
- Existing scoped tooling tests and shell/Python syntax checks must remain
  green after changes. Latest logs are under `.work/verification/`.

## Remaining review and authorization gates

This is an accidental-write/service-isolation guard for reviewed tools, **not a
hostile-code sandbox**. A network namespace does not isolate arbitrary
filesystem Unix sockets; the reviewed masks cover known runtime aliases, not
every host IPC path. Read-only mounts do not generally restrict device I/O or
inherited descriptors. Environment settings such as disabled mDNS or offscreen
Qt are preferences, not isolation proof. The private namespace's final leftover
scan is an additional rejection check, not authority to kill unknown processes.

The lead must review the installed tools' actual no-guest traces, endpoints,
write attempts, namespace/process teardown and caller snapshot before proposing
any guest stage. Even a completed preflight writes
`manualTraceReviewRequired=true`, `guestExecutionAuthorized=false`,
`nativeInstrumentedTestsExecuted=0`, and `physicalAudioVerified=false`.
`--run` remains exit 78. Do not convert source compilation, these authored
regressions, or a help/private-server probe into emulator, instrumentation,
native-playback, or physical-device success.
