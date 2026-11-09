# kmpYT feasibility and integration audit

**Updated:** 2026-11-06 UTC. This document retains the original baseline research below, but the
**current implementation checkpoint supersedes its former progressive-only, no-code/no-tests,
design-only-resume and unresolved-license statements**. Sections 1–10 are historical observations
at `533562f`, not descriptions of the latest local patch stack or a release-verification claim.

## Current implementation checkpoint

### Revisions and ownership

- Authorized editable checkout: `.work/kmpYT`; Veyra consumes a separately pinned submodule.
  Local upstream stack:
  - `2aae01e997da74f9ea83ca36809ec05a3870b307`: complete search titles and leading ordered-result receipts.
  - `84afce07b7f996a316847212e094a507711f9141`: explicit scoped desktop runtime locations.
  - `acc8d1e3acd8e3633f5e5532e35e47218793eb49`: refreshed progressive prefix integrity and stable checkpoint locks.
  - `316e7498f5ed0a38b5734117837906f1737e33b2`: owned bounded native static-SABR playback resources.
  - `1b95f2efe3cbe8f619646c6d650ab81c32f813fe`: exact-generation privacy-safe SABR byte receipts and shared raw URL identity;
    scoped Linux JVM tests passed, Android/Apple execution and Windows path safety not established.
  - `05978e0a572cced3eaf8a6a98b49a22382c949dd`: imported desktop-reviewed no-follow helper
    (`73cd14f` in the isolated Windows worktree), using JVM reparse/ancestry checks and Android `Os.lstat`.
  - `4e7425b30e7a55681edda92479070d289989ed10`: coordinator/SABR path guards, linked-track
    regression and Android no-follow tests; scoped Linux JVM tests and Android source compilation passed.
  - `90ee330ab8ddbf128a73875818b4c3ab810e7f95`: source-supported Apple socket/clock binding repair
    for actual PR2 compiler errors; not yet recompiled on Apple.
  - `69f38e8cb533553eeeaac7595f26383335bcf081`: independently source-reviewed Apple HTTP/SABR
    receipts, digest and private ownership parity, including dependent-uncertainty lock retention;
    **uncompiled/unexecuted**, with 15 code/test paths preserved in
    `.work/kmpyt-evidence/apple-parity-source-reviewed/`.
- The code stack through `316e749` was tested locally. The lead merged it with the reviewed MIT/scoped-CI
  stack at `c0a08090fe7dbd76e20216559b8184ad2cccde63`; that is the current local Veyra gitlink and
  `gradle/kmpyt.commit`. The lead verified anonymous exact-SHA fetch of that public pin. Later
  privacy/integrity repairs are independent work, not yet in that pin.
- **No push from this branch:** its inherited workflows do not satisfy the project write boundary.
  Lead/CI ownership stacks patches on the reviewed licensed/scoped-CI publication base. Public
  behavioral [draft PR2](https://github.com/Apdelrahman1911/kmpYT/pull/2) now has lead-reported
  pushed head `1e05eaf63cfec0a52b8deca6bc62c3ea521ddbe5`, also verified as the local publication
  checkout HEAD. It contains the app pin, official MIT merge and reviewed repair/diagnostic
  follow-ups; the new CI outcome is pending, and it is not a release.
- Owner-authorized MIT adoption and baseline contribution/dependency review are complete.
  Official [PR1](https://github.com/Apdelrahman1911/kmpYT/pull/1) merged at
  `45f7077880c37c20216d767b262bb5757b3010bf`; the default-branch license endpoint confirms MIT.
  This resolves the former permission/source-license/publication gap, not final per-binary
  inventories, corresponding source/notices or clean-checkout delivery. See
  [licensing verification](../verification/licensing.md).

### Central acquisition and exact first-result behavior

D005 supersedes the old independent-source sketch: **all playable acquisition goes through
kmpYT**, including externally discovered music and podcast episodes. RSS, directory and Commons
adapters supply discovery metadata only. Complete, unchanged titles are submitted verbatim;
oversized/invalid input fails rather than being shortened. `YouTubeBrowsePage.query` records the
submitted query and `firstEntry` records ordinal zero before UI filtering. Unknown/private/
malformed cards and collection shelves do not promote a later video or a collection child.

Veyra's `YouTubeKmpMediaGateway` consumes that raw receipt, preserves a nonplayable first result
as an explicit outcome, validates immutable execution bindings, and leaves requested-versus-
resolved podcast/music provenance to `AppSourceResolver`. Browsing an album/playlist/channel
is supported metadata navigation; it is not a claim of typed music-album or podcast-catalogue
equivalence, nor permission to replace a collection result with one of its tracks.

### D005 entity-by-stage capability audit and missing coverage

**Read-only audit/design, 2026-11-06; no new live requests or test execution.** The reviewed
Veyra models, source contracts, resolver, gateway, adapters and their tests still match the
retained fifth-run input hashes, using app pin `c0a08090`. Newer upstream source is reviewed
separately above; it is not silently substituted for that tested pin.

The public kmpYT client exposes generic `search`, `channel`, `playlist`, `inspect`,
`expandFormats`, `playbackPlan`, `plan`, `refresh` and `download`. Its browse kinds are
`VIDEO`, `CHANNEL`, `PLAYLIST`; a raw unrecognized first entry has no known kind. These are
**not typed track/album/show/episode capabilities**. Veyra's broad `SourceCapabilities`
booleans are likewise insufficient to establish that entity matrix.

- **Track:** a `MediaItem(MUSIC)` is executable only after full-title search produces an
  acceptable ordinal-zero VIDEO binding; an existing YouTube ID does not skip fresh search.
- **Episode:** `MediaItem(EPISODE)` follows the same central path. The gateway builds an
  internal canonical YouTube `MUSIC` item solely to execute the selected VIDEO; the resolver
  returns the original episode/GUID/show context and the separate immutable binding. The
  low-level source's `MUSIC` check therefore does **not** mean episodes are unsupported.
- **Album/release:** `Album` is a separate metadata model; there is no album request or
  album execution binding in `KmpMediaGateway`/`AppSourceResolver`. A playlist renderer or
  shelf titled “album” proves only collection navigation/first-entry preservation. It does
  not establish release identity, complete track membership or album playback/download.
- **Show:** `PodcastShow` is likewise metadata, not an executable resolver input. YouTube
  `browse(SHOW)` explicitly fails `YOUTUBE_NOT_A_PODCAST_SOURCE`; a CHANNEL/PLAYLIST result
  is not declared podcast-show metadata. An individual discovered episode remains eligible
  for its own central full-title selection; that does not establish show-level execution.

In the table, **F** means existing executed authored-fixture evidence for the named seam,
**U** means the current typed contract cannot express that entity-level operation, and
**G** means missing required verification. F never means live success or a completed
cross-product of semantic entity, representation and native platform.

| Stage (audit independently) | Track | Album/release | Show | Episode |
|---|---|---|---|---|
| Complete unchanged title → actual kmpYT search | F: resolver and real-client gateway fixtures separately | F for a generic title string; U for typed album input | F for a generic title string; U for typed show input | F: parsed RSS title/resolver and real-client gateway fixtures separately |
| Actual raw ordinal-zero result/type, before display filtering | F: VIDEO/collection/unknown/unavailable distinctions | F: PLAYLIST/shelf retained, not a child; no declared ALBUM type | F: CHANNEL/PLAYLIST retained; no declared SHOW type | F: same raw receipt policy, never a later “matching” episode |
| Selected identity inspection + offered formats | F: selected VIDEO only, public access/identity checks | U: collection is not a selected media report | U: show/channel is not an episode report | F: bound VIDEO inspection via episode-context gateway; original episode is not overwritten |
| Playback plan | F: offered progressive or owned static-SABR bridge; codec/transport restrictions remain | U: no aggregate album plan | U: no aggregate show plan | F: progressive episode-context gateway; broader transport checks exercise shared selected-video machinery, not an episode-specific full matrix |
| Download plan | F: declared compatible progressive/SABR/HLS/DASH, materialized output checks | U: no aggregate album transfer plan | U: no aggregate show transfer plan | F: bound episode-context VIDEO plan/delegation; all-transport episode composition still G |
| Same-selection refresh | F: bounded progressive expiry/transfer renewal and independent resolver-binding reuse; all-transport/native parity still G | U: no typed collection refresh receipt | U: no typed show refresh receipt | F: binding reuse/title-correction semantics; combined real-client episode × transport renewal still G |
| Actual transfer + native validation | Upstream authored-byte/loopback/FFmpeg evidence exists; app/live/native target journey G | U for aggregate semantics; no live evidence | U for aggregate semantics; no live evidence | Fake downloader delegation is F, not transferred episode media; app/live/native target journey G |
| Process termination → Room restore → native-network-denied local play/seek | Generic local-file/Room seam is tested with an episode fixture; track-specific/native journey G | U for aggregate semantics; no substitute child proof | U for aggregate semantics; no substitute episode proof | F: historical binding/local-validation contracts with a test validator; required app-native journey G |

The fifth run contains **41 YouTube seam methods** (9 selection, 8 gateway, 7 purpose,
17 source), **31 resolver methods**, and **16 resolver/Room methods**, all passing. These
are subsets of the same 307-test run, not additional executions. The earlier upstream
first-result suite has **12/12** passing methods, including collection shelves, unavailable
first cards, unknown renderers, Unicode bounds and primary-versus-sidebar ordering.
`channelAndAlbumFirstResultsKeepCollectionIdentityAndNeverExecuteChildOrLaterVideo`
is specifically a **playlist/channel rejection test**, not an album capability test.
`downloadUsesOnlyTheImmutableBoundVideoAndActualKmpytPlan` uses a fake native downloader;
its returned byte count does not prove a file exists or decodes. Source/hash and retained
XML references are recorded in `.work/kmpyt-evidence/capability-audit-plan/`.

#### Exact remaining audit cases (planned, not authored/executed by this audit)

1. **CAP-CHAIN — compose the currently separate seams.** Drive the real
   `YouTubeClient` + production gateway + `AppSourceResolver` with authored transport
   responses and the real Room store, separately for MUSIC and RSS EPISODE. Test an
   unchanged Arabic/English/emoji title including significant surrounding spaces and a
   final distinguishing suffix, plus invalid/oversized input. Assert the exact submitted
   query, ordinal zero, canonical selected identity and durable binding **before** the
   first inspection. Exercise VIDEO, CHANNEL, PLAYLIST/shelf, unavailable, malformed,
   unknown and truly empty first outcomes for PLAYBACK and DOWNLOAD. Assert no second
   search, later-result inspection, collection-child request or enclosure/Commons request.
   Existing unit seams cover these invariants separately; a fake gateway is not the
   missing composed actual-parser/client check.
2. **CAP-FORMATS — retain entity, purpose and platform dimensions.** For MUSIC and
   EPISODE bindings, enumerate actual source-declared progressive AAC/Opus, static SABR,
   HLS and DASH. Record inspection formats separately from selected playback/download
   plans. Playback may use only its supported progressive/owned bridge; download must
   check the materialized native file codec/container without opening a playback producer.
   Explicitly reject unknown HLS rendition codec, unsupported default language/profile,
   encrypted/live manifests and changed/ambiguous identity rather than select an easier
   track. A fixture `platformName` branch is not native execution on that platform.
3. **CAP-RENEW — audit each admitted representation, not just progressive.** For each
   executable entity/purpose, simulate expiry, rejected media URLs, disappearance and
   ambiguity of the selected representation, changed language/content, and an explicit
   authorization refusal during renewal. Preserve the original full-query/VIDEO binding,
   apply the existing bounded refresh budget, and issue no new search. Distinguish a
   refreshed plan from safe byte reuse: retain bytes only when that transport's exact
   identity/validator/digest receipts prove continuity; otherwise report restart or an
   unsupported refresh, not resumable success. Follow with actual per-native transfer,
   pause/resume/restart/corruption coverage under D008; fixture plan renewal is not that proof.
4. **CAP-COLLECTION — specify the missing typed contract before claiming support.**
   Audit complete album/show titles and raw first kinds without casting either metadata
   model into a fake MUSIC item. Cover first VIDEO, PLAYLIST, CHANNEL, unknown/unavailable,
   malformed and empty outcomes. Until a reviewed type-aware contract exists, collection
   execution is explicitly unsupported and no child is selected. Test YouTube SHOW-route
   rejection before transport. Any future collection support must declare source-proven
   identity, membership/ordering/duplicate occurrences, continuation and requested-versus-
   resolved provenance; a VIDEO titled like an album is not proof of its tracks, and a
   channel is not proof of a show. Explicitly selected members get their **own** complete-
   title first-result bindings. A parent-level Play/Download policy still needs an explicit
   contract and tests; neither child traversal nor a single long recording silently
   satisfies the missing album/show operation. Truthful U is a current gap, not a scope cut.
5. **CAP-PROVENANCE — verify the composed episode/collection boundary.** Retain episode
   GUID, show link, original title/rights/duration independently from selected VIDEO title,
   actual duration/representation and binding. Title correction may update discovery but
   cannot change a historical resume/download binding. Test identical titles from different
   shows/releases and a differing first recording without relabelling source bytes or
   applying publisher rights to the selected recording. Reuse the existing Room/resolver
   and UI tests where applicable; do not infer new live rights or source identity from them.
6. **CAP-LIVE/NATIVE — separately approved, immutable run per supported entity/target.**
   After verified runtime/packaging and authorization gates, use genuine complete metadata,
   actual kmpYT search, its raw first result, permitted inspection, playback plan, native
   advance/pause/seek, download plan/transfer, native validation/hash and actual terminated-
   process offline restore with native egress denied. Bind all phases to the same recording
   and executed revisions. Follow the existing [central-proof runbook](../../composeApp/src/desktopMain/kotlin/dev/veyra/desktop/verification/README.md),
   including its narrower approved media bounds; it is not all-transport capability proof.
   No new live run is authorized by this design. The approved channel does not authorize
   arbitrary music/show/album candidates: if a suitable specimen or allowed raw first result
   is absent, record missing coverage and stop, not a new query, later result or wider scope.

#### Classification must not turn refusal or unexecuted work into capability proof

Record evidence origin (authored fixture, actual API, direct public-page probe), requested
entity, stage, exact revisions/input hashes, executed test/command, platform and fixed failure
code independently. Publish no signed endpoints, cookies, private titles or raw source logs.

| Outcome | What it establishes; downstream classification |
|---|---|
| Fixture passes an injected authorization response | Correct refusal handling only; **not** a real source-denied observation |
| Actual `LOGIN_REQUIRED` / `AUTHORIZATION_REQUIRED` | Source denial at that inspected identity/time; formats may be zero, dependent playback/transfer stages are **not reached**, not separately tested failures |
| Collection/unknown/unavailable raw first, missing typed album/show API | Unsupported result/contract (or explicit parse failure), **not** proof of login denial or permission to skip the result |
| Available source but incompatible/undeclared codec or transport | Representation/native capability restriction, not source authorization failure |
| Actual Windows `OFFLINE_BROWSER_STARTUP_TIMEOUT` | Offline Chromium startup failure before CDP/media execution; not YouTube refusal, not a failed music/episode source test |
| Native containment gate or Apple compiler failure | Pre-execution environment/build failure; **zero native executions** for that blocked stage, not source-denied or native-passing evidence |
| No attempted entity/stage or a prerequisite stopped it | Untested/not reached with that reason; never promote it to F or claim every entity is denied |

The retained 2026-11-06 live observation queried `oPiiLz` and directly read a known watch
page; the older upstream approved live run also reported authorization refusal. Neither
establishes the required **complete requested track/album/show/episode title → actual
library raw-first selection** matrix at the current pin. The integration owner confirms
there has been **no later live or real two-process Veyra proof**. These observations remain
important, accurately scoped denial evidence; the downstream and other-entity cells remain G.

### D005 typed acquisition packet — proposed, not implemented

**2026-11-06, finite design/cross-review packet for the lead's decision.** No source edits,
builds, browser/native launches or live requests were made for this packet. App pin remains
`c0a08090`; editable upstream is `69f38e8`; the lead's publication checkout is now
`1e05eaf63cfec0a52b8deca6bc62c3ea521ddbe5` (PR2 pushed per lead, new CI pending).
Source inputs are recorded in `.work/kmpyt-evidence/typed-acquisition-design/`.
The required album/release/show U cells above are **implementation gaps to close**, not
optional scope. Proposed APIs and passing source review do not change those cells to F.

**Cross-review converged:** architecture and integration report no blocking design correction.
Architecture requires the normalized executable-subject union/explicit controls mode and
immutable request/member/binding lineage, not nullable labels on fake media. Integration
confirms actual parent membership must drive collection execution and the present UI's null-
cursor completeness check is insufficient. Both accept parent Single without a second search,
and prohibit RSS GUID/progress relabelling. These are source/design reviews, not test runs.

#### Decision and API cases

Adopt one typed acquisition service, not an album/show disguised as `MediaItem(MUSIC)`:

```text
request = immutable { Media | Album/Release | PodcastShow | SourceCollection,
                      requested identity, complete title, metadata revision, listening mode }
acquire(request) -> Single(first receipt, executable subject)
                 | Collection(first receipt, source collection, explicit scope)
                 | Unplayable(first receipt if present, typed reason)
enumerate(Collection, ephemeral cursor?) -> ordered source membership page + extent/status
selectMember(immutable member occurrence) -> its own complete-title/raw-first outcome
```

Every new acquisition sends the unchanged full requested title to actual kmpYT search;
the exact query and **raw ordinal zero**, including unavailable/unknown cards, are authority.
Persist selection before inspection/execution. A known selection whose inspection/format/
runtime later fails remains that selection with a **stage-specific failure**, not a different
search result. Empty response, invalid title, unsupported first entry and source denial are
different outcomes. No second engine, later result, inferred matching title or hidden fallback.

| Actual first result | Required handling for a typed request |
|---|---|
| Valid VIDEO | `Single`: execute exactly this recording using the parent's full-title receipt. **Do not re-search the accepted video title.** For an album/show request label it “recording returned for …”, not automatically “the full album/show”. No fabricated tracks, episode boundaries or chapter slicing. The one receipt is both the parent selection and its one playable selection. |
| Valid PLAYLIST | `Collection`: enumerate only this selected playlist's actual ordered source members. Its title/type does not establish an official release, matching tracklist or publisher podcast. |
| Valid CHANNEL | `Collection`: explicit `CHANNEL_VIDEOS` scope, bound to the selected channel identity. This is a channel's returned videos, not an RSS episode catalogue or all channel tabs. No implicit switch to a later playlist, upload-ID derivation or “podcasts” scope. |
| Unknown/unavailable/malformed/empty | Preserve the receipt where available and fail truthfully at that stage. Do not descend into the first shelf or promote a later item. |

A collection-valued result is never auto-promoted to its first video. A member that itself
resolves to a collection remains a visible non-playable member of the current batch; there
is **no automatic recursive expansion**. Explicitly opening that returned collection is a
separate action, not fallback. User-curated local playlists remain explicit lists of leaf
requests, not purported source albums whose user-entered names are searched as catalogue titles.

#### Actual capability versus required implementation

| Seam | Current source/evidence | Required delta |
|---|---|---|
| MUSIC/EPISODE leaf title selection | Existing actual-client/raw-first and resolver fixtures; original episode retained | Reuse, with typed immutable request association; no loss of existing leaf semantics |
| Album/Show title selection or parent Single | No typed input/parent receipt; models are metadata only | Typed request/selection and neutral executable-subject support |
| Playlist/channel navigation | `YouTubeClient.playlist/channel`, generic filtered browse items; channel defaults to `/videos` | Authoritative selected-collection identity, scope and membership API, not `items.first()` |
| Ordered complete membership | Playlist duplicate videos retained, but unavailable positions removed; non-playlist pages deduplicate; `more` accumulates items; null continuation can mean truncation/error | Raw occurrence receipts/holes, page lineage, explicit terminal/incomplete state and observation consistency |
| Inspection/formats/plans/refresh/transfer | Existing selected-VIDEO machinery only; fixture/native evidence is scoped above | Reuse it per admitted Single/member, preserving exact binding and D008 full representations; no collection URL sent to video inspection |
| Genuine source album/show classification | No declared ALBUM/SHOW browse type; RSS show identity is metadata-only | Preserve requested metadata separately; never assert source equivalence from generic playlist/channel. This is not repaired by naming a class Album/Show. |
| Connected typed UI/offline/native journey | No typed aggregate journey or its tests | Required implementation, migrations and target-specific execution; no new live proof here |

Important actual source constraints: `YouTubeBrowsePage.channelId` comes from response
metadata, but no comparable playlist identity/title-origin/selected-scope proof is exposed.
Generic fallback page titles are not complete authoritative query titles. `more()` may
clear its cursor for limits/repetition/unsupported continuation, and a later page notice
can replace an earlier omission notice. `CollectionDetailScreen` currently treats
`nextPageToken == null` as complete and dispatches only lists of media; that cannot implement
the new contract. `Album` has normalized tables but no production discovery producer or
album-detail/library action; real typed album entrypoints must be wired, not just a resolver.

#### Enumeration, refresh and provenance invariants

1. Upstream owns a general-purpose source collection API, reusing its public transport and
   renderer decoding. Expose response-declared collection identity agreeing with the selected
   receipt, recognized list/scope and complete title origin. Missing/contradictory evidence is
   an explicit metadata failure, never a playlist ID inferred from a child. A channel handle
   cannot replace the selected stable channel ID. Keep public metadata apart from ephemeral
   continuation context, visitor/session fields, signed URLs and headers.
2. Capture each raw member position, duplicate occurrence, complete title or fixed issue;
   no filtering hides unavailable/unknown entries. Source-declared subcollections stay
   subcollections, not flattened child videos. If traversal limits prevent identifying all
   positions, record an incomplete gap, not invented missing rows. Pagination retries cannot
   append twice; accumulated legacy pages cannot be confused with deltas.
3. Seal an immutable app membership snapshot with parent receipt, scope, ordered occurrences,
   observation times and explicit `OBSERVED_TO_END` versus `INCOMPLETE(reason)`. Missing cursor
   alone is never completion. Even observed-to-end is **not a source-atomic snapshot** or proof
   of all historical/publisher members; store observation consistency separately. Unknown
   private members remain holes even when traversal reaches a genuine end.
4. Each selected VIDEO member gets a new leaf request using its **frozen complete member
   title**, not the parent's title, appended artist/show terms, current mutable media title,
   or its already-known YouTube ID. Its own raw-first VIDEO is the only executable recording.
   Identical repeated occurrences may share the same immutable leaf request/receipt; their
   queue/batch occurrences remain distinct. Different titles/identities cannot be deduplicated
   merely because they resolve to the same video.
5. Resume/renewal uses existing parent/member receipts, never new search. “Refresh listing”
   re-enumerates the same selected collection into a **new** snapshot; “Resolve again” performs
   a new title search. Neither edits an admitted queue/batch. Lost ephemeral cursor requires
   explicit new enumeration, never stitching a new listing onto old ordinals. RSS refresh is
   a third, metadata-only operation. All such network work obeys offline mode.

#### App/schema, queue and download delta

- **Normalized request/receipt graph:** immutable typed request and source first-result rows;
  typed foreign-key associations to Media/Album/Show/source-collection metadata; collection
  snapshot rows and ordered occurrence rows (`snapshotId, ordinal` unique), with raw issues
  and selected child request/receipt associations. Do not overload `album_tracks` or
  `podcast_episodes`: both presently prohibit duplicate member identities.
- **One executable-subject union:** existing catalogue Media **or neutral source recording**,
  not arbitrary videos copied to EPISODE. Parent Single and resolved collection recordings
  use neutral subjects; their originating parent/member request remains separately linked.
  Original RSS episode leaf requests retain their existing Media identity/kind. Add explicit
  listening-controls mode to selections/queue: show intent may use podcast controls, no
  shuffle/crossfade, global podcast speed/skips/timer, without declaring source podcast
  metadata or borrowing a requested show's speed profile. Album intent uses music controls.
- Queue/history/progress/download/asset references must retain executable subject **and exact
  binding**, with collection occurrence/batch ancestry where applicable. A neutral recording
  cannot mark a requested RSS GUID played, inherit its timeline, create a follow, or appear as
  a new publisher episode. Preserve old media IDs, kinds, binding IDs/queries and history in
  an explicit additive mapping migration; no destructive reset or serialized snapshot blob.
  The current binding-title validation must validate the immutable request/member title,
  not overwrite live catalogue metadata to make an old title pass.
- **Atomic intent, not atomic success:** replace/enqueue captures the confirmed snapshot and
  selected ordered occurrence IDs in one Room transaction, with revision/generation checks.
  Unknown/non-playable holes remain visible. “All” requires observed-to-end enumeration and
  no unacknowledged holes; a user may explicitly choose “selected/available N”, which is never
  reported as the complete album/show. A later member failure stops at that occurrence with
  retry/explicit skip; do not silently play a later member as its successful replacement.
- **Durable download batch:** parent snapshot + ordered member intents + per-member outcome
  and associated download record. Persist intent before slow selection/transfers; workers
  use the existing bounded, leased, resumable D008 coordinator and kmpYT, not a batch transfer
  engine. Duplicate occurrences may share an identical bound download while keeping all
  provenance rows. Preserve rejection of a different existing recording; never let the
  current `COMPLETE` no-op hide a requested-binding conflict or silently remove old bytes.
  Batch Ready means all admitted required members have validated assets for their pinned
  selections; failures/cancellation/exclusions and counts remain explicit. Parent has no
  synthetic audio file. Pause/cancel/retry/resume acts on durable member intents.
- Offline playback validates saved selection/assets locally without parent search, feed
  refresh, pagination or artwork traffic. Missing/corrupt/changed bindings require recovery;
  no silent network replacement. Progress and measured duration remain recording-specific.

#### UI journeys and implementation/test endpoint

Keep requested album/show metadata visible beside the actual selected Single/Collection and
its differing source title/type. Provide typed album navigation/save/detail and actual
catalogue intake where source metadata really declares an album/release. Never manufacture
one from a playlist label. Show Follow/Refresh and existing episode rows remain genuine RSS
metadata. “Play latest/selected RSS episodes” is an explicit batch of episode leaf requests,
**not** a claimed show-title aggregate match. Add a separate show-title resolution/results
journey with source-member/recording labels and explicit queue-replacement confirmation.
Navigation/follow/save never silently starts audio. Save/follow/download remain independent.

Implementation order/ownership after the lead approves and releases freezes:

1. **architecture + kmpyt_audit:** agree shared typed request/receipt/executable-subject contract;
   kmpyt_audit implements/tests upstream authoritative collection API and adapter; architecture
   owns normalized schema/migration/validation and coordinator selection integration. Root alone
   owns publication/pin changes after upstream review. Existing video execution stays central.
2. **root + architecture + podcast download owner:** wire durable batch/occurrence admission,
   playback and download pinning/recovery through existing owners. **integration** cross-reviews
   genuine RSS identities and actual-client composition. **music/podcast/design** own their
   typed detail/result/batch UI and English/Arabic labels; root wires composition/navigation.
3. **Required authored tests:** actual-client parent VIDEO/PLAYLIST/CHANNEL/unknown/empty/private
   cases; exact Unicode/full-title queries and first-result authority; Single performs exactly
   one search and no slicing; parent and every distinct member receipt before inspection;
   returned member B resolving to C preserves both without RSS identity pollution; no child
   collection recursion; duplicate/hidden rows; identity/scope mismatch; retry/repeated/expired
   cursors; truncation and non-atomic observations; title changes while resolving/admitting;
   new snapshot versus existing queue; partial batch/conflicting downloads; cancellation,
   killed-process recovery and real Room migration. UI tests cover typed album intake/detail,
   show metadata versus resolved recording/collection, disclosed mismatches, explicit partial
   actions, no-auto-play, recorded progress isolation and narrow/wide en/ar controls.
4. After source/fixture/migration tests pass, execute the separately approved real/native
   selection→play/seek→download→terminated-process/offline chain for admitted Single and
   collection members. Missing source-declared metadata, inaccessible selected content and
   unavailable native/physical evidence stay separately recorded gaps. No live permissions,
   source scope, representation requirements or release gates are relaxed by this packet.

### Native SABR resources and broad download planning

- Upstream normal native factories now install `YouTubePlaybackAdapter`; custom hosts can supply
  one explicitly. `YouTubeClient.openPlayback(request, authorization)` wraps the **existing**
  `YouTubeSabrStreamingSession`/transfer engine, not a competing media downloader.
- A native decoder receives an exact `127.0.0.1` GET/HEAD/range endpoint with a random 256-bit
  capability, never the signed SABR POST endpoint. Exact Host/path matching, no browser-origin
  surface, single ranges, no-store headers, bounded sockets/headers/chunks/timeouts and a bounded
  private disk cache protect the bridge. Cache directory names do not reveal URL capabilities.
  Verified byte coverage—not sparse-file length—controls native reads.
- Explicit caller authorization is rechecked on actual media requests. There is **no fabricated
  authorization or integrity issuer**, no live rolling cache, no server-time-seek invention and no
  fallback to another track/recording. Static seeks wait for the required source bytes.
- Client ownership includes opening-resource bounds, acquired-result cancellation compensation,
  malformed adapter metadata cleanup, resource-before-transport close ordering and sticky unknown
  release failures. A failed close remains owned and cannot become successful on duplicate close.
- JVM/Android use sockets and durable random-access files. The iOS adapter uses nonblocking BSD
  sockets, per-descriptor mutexes, `SO_NOSIGPIPE`, private `O_NOFOLLOW` files and secure capabilities.
  **Android production and instrumented-test sources now compile; no native execution is established.**
  Actual Apple PR2 framework builds failed missing socket/clock bindings; the isolated repair
  above is source-reviewed, not yet verified by another Apple build.
- Veyra source changes select actual progressive or adapted SABR for PLAYBACK. DOWNLOAD calls
  actual `expandFormats`/`plan` and supports offered progressive/SABR/HLS/DASH representations
  compatible with the target's materialized audio. It creates **no playback resource** while
  preparing a download. Its transport is the acquisition transport; MIME/codec describe the
  materialized file. Native decoder capability checks apply to that progressive local output,
  not whether a generic native HTTP player can execute SABR or a DASH manifest.
- Segmented/remux output is not checked against the sum of input segment byte sizes. Final app
  native-media validation, file SHA, atomic publication and Room Ready/recovery remain required.
  Full Room-backed orchestration is owned by the app download workstream (D006/D008), not JSON
  checkpoint files. The fourth pinned shared run exposed the HLS parsed-codec limitation below;
  the corrected purpose suite passed all 7 methods in the fifth run.
- **Audited HLS limitation:** upstream leaves an external `EXT-X-MEDIA` audio rendition's codec
  unknown; it does not infer an audio codec from a referencing video variant's `CODECS` attribute.
  Veyra correctly rejects unknown codecs rather than assuming AAC from `audio/mp4`. The successful
  fixture now uses an actually declared audio-only variant, and a seventh regression explicitly
  rejects the external unknown-codec rendition before fetching its media playlist. Both changes
  passed in the fifth shared Linux JVM run; production selection was not loosened. JVM/Android/iOS
  labels in that fixture exercise planner capability branches, not native target execution. Any
  future inference must prove an unambiguous declared codec, not invent one.

### Resume integrity and review findings

Schema-2 (retained in new schema-3) JVM/Android **standalone progressive** refresh reuse binds selected media/format metadata,
exact origin/port/raw path, order-sensitive raw content query, actual final-response scope, a strong
quoted ETag and committed-prefix SHA-256. Only exact documented signing keys on Googlevideo or
explicit authored loopback fixtures are elided. Non-signing duplicates/empty pairs/encoding/case
are not normalized away; no Googlevideo-subdomain equivalence is assumed. Uncommitted tails are
truncated, retained bytes are hashed, and even complete retained parts are response-revalidated.
Legacy schema 1 retains exact-plan-only behavior; multi-resource/SABR bindings are not relaxed.

Linux JVM private-tree regressions reject symlinks (including dangling links), special files and
excess depth/entry count before writing/resetting/deleting state. **Windows execution is unverified:**
the desktop audit of OpenJDK 21 established that `File.canonicalFile == absoluteFile` is not a
junction/reparse-point guard. The public Windows linked-entry failures are a real safety concern,
not permission to weaken those tests. The integrated repair adds full-ancestry no-follow attributes
(JVM NIO; Android API-21+ `Os.lstat`, not unguarded API-26 NIO), coordinator/SABR preflight and
pre-hash/read/write/publication checks. Only the caller-supplied parent is canonicalized; destination
and private leaves are never resolved through links. Windows `BasicFileAttributes.fileKey()` is
nullable, so lock-identity tests now use distinct test-owned hard-link witnesses with
`Files.isSameFile`, not invented inode evidence. The later **147-test run passed 146 and skipped
only the real Windows-junction method on Linux**; Android production and test sources compiled.
This supersedes source-only review but is not actual Windows or Android instrumentation evidence.
Evidence: `.work/desktop-audio/upstream-audit/`, `.work/kmpyt-evidence/private-paths-first/`.
Caller-private-directory assumptions remain explicit: these checks cannot eliminate every hostile
same-user TOCTOU mutation.
Independent review caught post-unlock completion cleanup and failed-resource-close forgetting;
both were fixed and re-reviewed. Completed checkpoint data is now purged **under the held lock**,
leaving the reusable lock inode/parent. Only a stronger external retired-generation lifetime fence
may remove that path; Veyra's download owner confirmed that fence for app cleanup.

The baseline SABR audit found sensitive transient protocol state in JVM/Android checkpoints:
source endpoint/configuration inside generation strings, redirected endpoints, cookies and contexts.
Both baseline native implementations also trusted coverage geometry/file length without hashing
retained SABR bytes. The new **schema-3 JVM/Android receipt repair** now persists only hashes and
bounded byte/segment geometry. It binds the exact serialized inspected plan (including source,
configuration, client, role, format, size and metadata), verifies every covered range via reads of
at most 64 KiB, and restores an anonymous request-1 session without cookies, contexts or redirects.
At most 2 tracks and 8,192 ranges/segments per track are accepted. Sparse holes are not progress;
uncommitted tails are truncated. Legacy SABR schema 1/2 is purged under the stable lock before
callbacks/requests; generic completed-part counters cannot replace missing range hashes. The
143-test Linux JVM result covers this repair, not a relaxed source identity or authorization grant.

**iOS parity remains uncompiled/unexecuted work:** the authored patch replaces sorted/decoded/
cross-host scope with the shared raw identity, hashes committed HTTP prefixes and SABR ranges,
binds actual final-response scope and strong validators, isolates caller HTTP plugins and follows
redirects explicitly. New writes explicitly encode schema 2; absent schema still decodes as legacy
1 and is reset, rather than accidentally adopting the old default. Files use no-follow bounded
private-tree checks, regular-file `lstat`/`fstat`, stable owner/lock inodes and atomic checkpoint
rename/fsync. Every media handle is registered before fallible hashing; canceled validation preserves
previous useful receipts. Cleanup attempts all independent closes while retaining the original and
suppressed failures. Unconfirmed descriptor release vetoes deletion/reopening. Read-review caught
and repaired an owner-marker unlink after uncertain close. A second review found that retaining
only an in-process destination fence while releasing `flock` let another cooperative process
acquire the directory. The authored follow-up retains the held lock and descriptor until process
exit on **earlier dependent** media/checkpoint/owner close uncertainty, in both normal close and
failed-open cleanup; independent final source review found no remaining blocker. An error from an already-attempted lock
unlock/close cannot establish continued cross-process exclusion, and numeric descriptors are
never retried after such an attempted release.

Authored Apple tests cover CoreCrypto vectors/snapshot continuation, corruption/truncation/tails,
two-track sparse coverage, cancellation, redirect/header confinement, symlink/FIFO rejection, exact
owner/lock residue and stable lock identity. Three new real-descriptor lock-helper tests cover
independent-open contention, normal-error release and failed-acquisition cleanup. They inject a
reported dependent uncertainty, **not a real POSIX close fault**; their known test-owned fd can
be released after the assertion without a production recovery escape hatch. These tests are unrun
and directly exercise the helper, not the store's deletion-veto or in-process-sticky integration;
the latter remains source-reviewed, not an executed fault regression.
Installed Kotlin/Native 2.4.21 metadata supports
the inspected signatures but is not Kotlin 2.2.21 compilation evidence. Real syscall faults,
mid-scan Apple cancellation and native execution remain unproven. Cross-signature resumption for
other transports is not covered by the standalone-progressive rule; safe restart is not full parity.

Actual PR2 `31300ef` Apple logs independently report unresolved `inet_pton`, `ntohs` and the
`NSDate.timeIntervalSince1970` extension in the native playback adapter. Commit `90ee330` adds
explicit Darwin/Foundation imports and uses exported, endian-aware
`CoreFoundation.CFSwapInt16BigToHost` rather than the absent C `ntohs` macro. Exact Kotlin 2.2.21
platform `.def` files and installed 2.4.21 metadata support that choice; **only a future actual
2.2.21 Apple build can verify it**. Evidence: `.work/verification/upstream-ci/pr2-31300ef-apple.log`,
`.work/mobile-api/`. Independent native containment exits still mean no simulator ran.

### Bounded Chromium diagnostics: reviewed candidate, combined execution pending

CI-owned commit `90bf1d37c6e56f91ff0eb82edf0a3c35b4711fa3` adds startup observations
without changing the primary failure code. Its exact nine-file patch
`b7d9f6e912dff5b4582489f1be9abec495b833c88767257978e6240ab709f957` passed independent
source review; committed blobs match the frozen review. It is based on the older `f0f7fab`
authoring branch and must be **cherry-picked**, never used to replace the newer licensed
publication tree. Evidence: `.work/kmpyt-evidence/startup-diagnostics-source-review/`.

- Only 4–8 distinct tokens from 16 fixed symbols describe observed spawn/exit state,
  none/some/invalid stderr announcement and bounded cumulative owned-port-file outcomes.
  Null child exit fields before a spawn event remain **unconfirmed**, not proof of liveness.
- Sender, JVM receiver and summary validate the whole receipt; unknown, duplicate or
  contradictory lists are dropped. One stackless, causeless suppressed JVM diagnostic is
  bound to the unchanged discovery-stage primary. No raw stderr, path, endpoint, UUID,
  source value or private error body is published.
- Independent comparison found profile/endpoint validation, browser arguments, cleanup,
  all 30s/15s/10s deadline lines and native/scoped/tool-selection gates unchanged. The
  deadline snapshots already observed state only, with no new I/O/probe or retry extension.
- Retained author-run lightweight receipts show **16/16 Python tooling tests** (including
  the Node suite) and a separate **30/30 Node** pass; these overlap, not 46 distinct tests.
  All 166 recorded inputs remain unchanged. **Seven JVM diagnostics methods are authored
  but uncompiled/unrun at this candidate**; the earlier four-method `f0` execution remains
  separate evidence. No browser/device/simulator/live request ran in this review.
- Additive workflow steps compile `:kmpyt:compileTestKotlinIosX64` and
  `:kmpyt:compileDebugAndroidTestKotlinAndroid` before the unchanged required native gates.
  Test-source compilation does not run native tests or bypass containment. The authoring
  base's license check failed because it predates the official MIT merge; that failure is
  retained, not reported green. Root's licensed publication `69743585` separately passed
  its license/tooling checks, and all 202 saved prepublication inputs remained intact.

The lead subsequently cherry-picked this patch onto licensed publication and reports
exact-head license/tooling16/Node30 checks plus 202 stable inputs before pushing `1e05eaf`.
This audit verified that local publication HEAD; no new CI result is inferred from the push.

The three actual Windows `OFFLINE_BROWSER_STARTUP_TIMEOUT` failures are still unexplained;
these additions make the next bounded run more informative, not a demonstrated repair.
Apple parity/test compilation and real native containment/execution remain separate gaps.

### Executed evidence and remaining release gates

| Check | Actual result / evidence |
|---|---|
| Full-title/first-result/runtime focused JVM suite | **82 tests, 0 failures/errors/skips**; `.work/kmpyt-first-result-final-tests.log`, `.work/kmpyt-evidence/first-result-final/` |
| Earlier resume safety suite | **57 tests, 0 failures/errors, 1 existing FFmpeg-gated skip**; `.work/kmpyt-resume-safety-tests.log` |
| Final resource/resume/transfer/manifest JVM suite, explicit `/usr/bin/ffmpeg` | **103 tests, 0 failures/errors/skips**; `.work/kmpyt-native-playback-final-tests.log`, `.work/kmpyt-evidence/native-playback-final/` |
| Included in those 103 (not additional totals) | 16 refreshed-resume, 9 client resource-lifecycle/bounds/cancellation, 7 HTTP protocol, 5 real loopback/native adapter tests |
| Privacy-receipt/raw-identity/transfer JVM suite, explicit `/usr/bin/ffmpeg` | **143 tests, 0 failures/errors/skips, 16 XML suites; exit 0**. `.work/kmpyt-privacy-receipts-tests-first.log`, `.work/kmpyt-evidence/privacy-receipts-first/` (XML, exact results, 127 tested-input hashes and source patch/new files). Sources match commit `1b95f2e`; the later Windows lock-test correction is excluded. |
| Included in those 143 (not additional totals) | 8 actual-file SABR receipt tests, 7 common-receipt/hash tests, 4 raw-identity tests; plus overlapping existing resume/transfer/resource suites. |
| Integrated privacy/no-follow/coordinator Linux JVM + Android compilation | **147 tests: 146 pass, 0 failures/errors, 1 Windows-only junction skip, 16 XML suites; exit 0, 2m26s**. `.work/kmpyt-evidence/private-paths-first/` preserves XML/log/patch/new source and **166 unchanged input hashes**. Source matches `4e7425b` (README updated after execution). Includes 9 actual-file SABR tests and 19 refreshed/path methods (18 pass/1 platform skip). Android production and instrumented-test source compilation passed, including 3 new no-follow methods; **0 instrumentation tests executed**. |
| Matching-helper CI diagnostics, separate clean worktree | At `f0f7fab218323ff3bc9a7728dc22b2cd1f329bd6`: **4 JVM diagnostics tests pass, 0 failures/errors/skips; exit 0, 1m17s**. Node endpoint fixtures **22/22 pass** on Node `v24.20.0`. `.work/kmpyt-evidence/diagnostics-f0-first/`, **125 unchanged input hashes**. No helper grafting or combined-public-revision claim; real Windows Chromium failures remain separate. |
| Fifth pinned Veyra shared run | **307 tests, 0 failures/errors/skips, 30 XML suites; exit 0**, including **41/41 YouTube adapter tests** (9 selection, 8 gateway, 7 purpose, 17 source). `.work/architecture/evidence/shared-fifth/`; scoped copies `.work/kmpyt-evidence/shared-source-fifth/`. Uses pin `c0a08090`, not the newer privacy/Windows/Apple changes. |
| Native authored fixture path | Actual SABR-shaped POST → private file → real local HEAD/GET/ranges, byte comparisons, resource cancellation/cleanup, FFmpeg AAC decode/seek to **null output** |
| Android/iOS native execution | Android production/test sources compile as above; no instrumentation executed. PR2 Apple framework compilation actually failed; `90ee330` repair and larger Apple parity work remain source-only pending actual target verification. No emulator/simulator/physical-audio inference. |
| Live approved YouTube source | Still classified `LOGIN_REQUIRED`/authorization refusal with zero formats. No compliant live playback/download/offline-restart proof |

The first native run exposed a real HTTP header-length receiver-shadowing bug (93 tests, 3
failures). The explicit response-length fix made that suite green; subsequent lifecycle/lock
regressions produced the final 103-test result above. Tests are authored redistributable fixtures,
not a replacement for denied central media. No source bearer URLs or audio captures are evidence
artifacts. The recorded counts overlap and must not be added as disjoint coverage.

The fourth shared run produced **299 records, 296 passes, 3 failures**; the HLS success fixture
failed `UNSUPPORTED_CODEC` for the parser limitation described above. The fifth run is now green:
**307/307**, including the corrected HLS fixture and explicit unknown-codec rejection, with its 101
input hashes unchanged during the run. The earlier gateway/oEmbed and other owners' Room/download
regressions also pass. These are pinned-fixture/shared results, not live-source or native-platform
media evidence; the two runs overlap and must not be added.

Remaining essential gates include revalidation after final upstream pin updates, Android/Apple execution,
Windows no-follow safety, complete iOS/all-transport resume parity, app-native listening/download/restart
with native egress denied, clean-machine Node/Chromium/FFmpeg packaging, reviewed upstream merge/pin updates,
and mandatory physical native MUSIC crossfade/audio verification. The task remains incomplete.

## Historical baseline research (sections 1–10)

**Initial audit scope:** public repository, checked-in implementation/tests, read-only GitHub
metadata and cross-team public-page probes. At that initial baseline no local library tests or
source edits had yet been performed. The sketches and limitations below preserve that history;
the current checkpoint above and D005/D006/D008/D009 supersede contrary recommendations.

## Initial decision in brief (historical)

- **Suitable as a YouTube source adapter, not as Veyra's player, podcast provider, music catalogue, persistent library, or download scheduler.** Its actual public API covers discovery, inspection, source/transfer plans, captions, and platform file transfers.
- **Do the real discovery → inspected audio source → native play/seek → download → process-restart/offline-play spike before promising YouTube playback.** Public discovery works from this environment; an approved video's watch response currently requires sign-in/bot verification and supplies no formats. A working browse page or green fixture suite does not establish a playable source.
- Prefer an **actually offered progressive AAC-in-MP4 audio track**, validated by the target native player. Do not treat SABR endpoints as media URLs, invent an itag, or relabel WebM as M4A. Other offered codecs can be supported only through a tested decoder path.
- Desktop source extraction requires separately supplied **Node.js 22 + Chromium/Chrome/Edge**; FFmpeg is additionally needed for combining/segmented output, **not for the simple progressive audio-only path**. Upstream supplies neither a clean-machine runtime bundle nor an application installer.
- **Public redistribution licensing is unresolved:** there is no root source license or POM license declaration. The owner has authorized work on their own upstream repository; that is not a license statement that downstream users can rely on. Obtain an explicit owner-approved license/permission before shipping public binaries/source copies; do not invent one.
- Preserve failures honestly. This audit did not establish a working live YouTube extraction/playback/download path, and no environmental authorization failure has been demonstrated to be a library defect.

## 1. Exact upstream baseline and evidence

| Item | Observed value |
|---|---|
| Repository | `https://github.com/Apdelrahman1911/kmpYT` |
| Default branch / audited commit | `main` / **`533562f781304338f72b27d32cfe5fb7a83dc3a8`** |
| Commit date / subject | 2026-11-05T16:20:25Z / `Execute every manually requested live check without test cache reuse` |
| Source version / coordinates | `0.1.0` / `io.github.apdelrahman1911.kmpyt:kmpyt:0.1.0` |
| Published distribution | README says not on Maven Central; public releases API returned `[]`; local clone has no tags |
| Build versions | Kotlin/serialization plugin 2.2.21; AGP 8.13.2; Gradle 8.13; JVM target 17 |
| Dependencies | Coroutines 1.10.2; serialization-json 1.9.0; Ktor 3.2.3 (Android/CIO/Darwin engines) |
| Targets | Android (`minSdk=23`, `compileSdk=36`); JVM; `iosArm64`, `iosSimulatorArm64`, `iosX64`; static `KmpYT.xcframework` |
| Unsupported targets | No Kotlin/JS, Wasm, browser app, native Linux, or native Windows target; desktop is JVM |
| Source size, not application size | 151 tracked files / 1,499,511 bytes; runtime source/resource files 650,209 bytes (measured from the pinned checkout) |

Sources: [commit](https://github.com/Apdelrahman1911/kmpYT/commit/533562f781304338f72b27d32cfe5fb7a83dc3a8), [pinned README](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/README.md), [module build](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/kmpyt/build.gradle.kts), [root build](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/build.gradle.kts), [wrapper checksum](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/gradle/wrapper/gradle-wrapper.properties), [GitHub metadata](https://api.github.com/repos/Apdelrahman1911/kmpYT), [releases](https://api.github.com/repos/Apdelrahman1911/kmpYT/releases).

All local source references below are relative to `.work/kmpYT/` at this commit. Read its `AGENTS.md`: live tests are opt-in and restricted to the approved channel in `ci/approved-channel.json`; offline tests must not contact YouTube. Library functionality itself is not hardcoded to that channel.

## 2. Public API actually present

Factories:

```kotlin
dev.kmpyt.jvm.createJvmYouTubeClient(ffmpegPath: String? = System.getenv("KMPYT_FFMPEG"))
dev.kmpyt.android.createAndroidYouTubeClient(context: android.content.Context)
dev.kmpyt.createIosYouTubeClient()
```

They return `dev.kmpyt.YouTubeClient`, own anonymous HTTP clients, and must be closed. The public constructor also permits explicit `ResearchTransport`, runtime, and downloader injection. It is not necessary to fork extraction for normal application integration.

| API (`YouTubeClient.kt` line) | Actual contract |
|---|---|
| `suspend inspect(url): ExtractionReport` (54) | Fresh watch/source metadata; no media bytes; report includes diagnostics, inspection time, media, and real representation identities |
| `suspend search(query): YouTubeBrowsePage` (59) | Public YouTube search, not YouTube Music search; trimmed 1–256-character query |
| `suspend home()`, `open(url)`, `more(page)` (61–65) | Public home/browse; `more` returns an **accumulated** page, not just the next page's items |
| `suspend channel(urlOrHandle)` (69) | Accepts `@handle` or public channel URL; defaults to `/videos`; actual `channelId` comes from response metadata |
| `suspend playlist(url)` (80) | Public playlist URL; ordered entries and duplicate occurrences preserved |
| `suspend expandFormats(report, mediaId=...)` (94) | Fetches up to eight HLS/DASH catalogue manifests; at most 1,024 resulting formats; metadata only |
| `select(report, maxHeight=1080, audioOnly=false, mediaId=...)` (101) | Nullable `YouTubeFormatSelection(primaryId, audioId?)`; IDs must come from this report |
| `playbackPlan(report, selection, mediaId=...)` (110) | `SourcePlaybackRequest`, **not playback**; only progressive/static-SABR contracts |
| `suspend plan(report, selection, mediaId=...)` (121) | `PlannedDownload`; resolves only selected manifests; does not transfer media |
| `suspend captions(report, sourceIndex, translationLanguage?, mediaId=...)` (127) | Caption document/cues; `captionState` also exposed |
| `suspend download(plan, destinationPath, onProgress)` (134) | Explicit save; progress has byte count, nullable total, speed; result has destination, bytes written, MIME |
| `suspend refresh(plan): PlannedDownload` (145) | Explicit fresh inspection of the **same selected track(s)**; rejects missing/ambiguous/changed identity |
| `suspend close()` (169) | Waits for active operations, releases cached player programs/runtimes and owned transport; cannot be called from an active progress callback |

Source: [YouTubeClient.kt](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/kmpyt/src/commonMain/kotlin/dev/kmpyt/YouTubeClient.kt), `YouTubeDownloader.kt`, platform factory files under `jvmMain`, `androidMain`, and `iosMain`.

### Discovery and catalogue boundaries

`PublicYouTubeBrowseService.search("oPiiLz")` performs an anonymous GET of `https://www.youtube.com/results?search_query=oPiiLz`, parsing `ytInitialData`. There is no developer API-key/account setup. Pagination uses only the actual page-declared WEB context/endpoint; it does not invent continuation tokens. Supported renderers include legacy video/channel/playlist renderers, shorts, and modern `lockupViewModel`.

`YouTubeBrowseItem` contains `id`, `title`, canonical `url`, `kind` (`VIDEO`, `CHANNEL`, `PLAYLIST`), optional thumbnail/subtitle/duration text. There are **no typed albums, songs, artists, podcast shows/episodes, charts, saved subscriptions, recommendations service, or RSS models**. `ExtractedMedia.artist` is sourced from uploader/author metadata, not proof of a musical artist credit. `YouTubeIncomingLinks` accepts some `music.youtube.com` links by canonicalizing them to normal YouTube; this does not implement YouTube Music's catalogue/API.

Browse limits: 8,000,000-character page, 500 items per parsed page, 5,000 accumulated items, 100 pages, cycle detection. Non-playlist pages deduplicate by kind/id. Consent, login, network, rate-limit, or unreadable-result outcomes often become `page.notice` plus empty/retained items rather than throwing. Veyra must display notices, avoid treating a blocked result as a successful empty catalogue, debounce search, preserve playlist duplicates, and stop requesting continuations when absent.

Sources: `youtube/YouTubeBrowseService.kt`; `youtube/PublicYouTubeBrowseService.kt:16–149,391–490`; `extraction/YouTubeInspectionResolver.kt:75–104`; `youtube/YouTubeIncomingLinks.kt`.

## 3. Audio, playback, expiration, and renewal

### Audio format selection

`Representation` records actual `mimeType`, `codecs`, `bitrate`, `sizeBytes?`, `durationMs?`, `expiresAtEpochMs?`, audio sample rate/channels/language/default flag/track ID/display name, DRC/loudness, transport and availability. It does not promise MP3, a fixed bitrate, an audio-only format, or any usable format for every video.

`select(audioOnly=true)` considers audio-role representations; sorting prefers explicit preferred audio ID (via lower-level selector), default audio, non-DRC, progressive transport, then bitrate. **It checks download compatibility, not device decoder support.** A directly copied WebM/Opus file can pass this selection on iOS/JVM but fail native playback. The application should select a compatible *offered* representation or expose a truthful unsupported-source state. Do not extract fake audio URLs or assume that `select` always yields AAC.

`YouTubeFormatCapabilities.playbackProblem` deliberately does not validate native codecs. Android/iOS combining rejects known WebM/incompatible MP4 codec combinations; no transcoding occurs. JVM combining uses FFmpeg stream-copy to MP4; that is not a universal codec conversion path either.

Sources: `extraction/ExtractionModels.kt:9–62`; `youtube/YouTubeFormatCapabilities.kt:33–61,95–118`; `jvm/JvmMediaProcessor.kt:47–69`.

### Playback contracts are not a player

`SourcePlaybackRequest.plan` contains selected representations and canonical inspection identity. `requireSupportedSource()` rejects arbitrary request headers, credentials, external SABR checkpoints, segments/init resources, and mixed transports. Plans are all progressive or all static SABR. The normal `playbackPlan` creates **empty headers/no credentials**, so current public progressive plans do not require an undocumented iOS custom-header API.

- Progressive audio: pass the actual expiring source URL to a native decoder/player adapter; the app owns prepare/play/pause/seek, queue, focus/interruptions, background audio, media sessions, lock-screen metadata, and error recovery.
- HLS/DASH: library parsers/download plans exist, but portable `playbackPlan` rejects them. Native HLS support would be an additional tested app adapter, not an already delivered kmpYT capability.
- SABR: endpoint is a protocol POST endpoint, **not an MP4/M4A URL**. `YouTubeSabrStreamingSession` needs caller-owned random-access caches and an explicit `SourcePlaybackAuthorization`; absent authorization fails with `SABR_PLAYBACK_AUTHORIZATION_REQUIRED`. It downloads whole static tracks, exposes verified byte coverage/read/awaitRange, and owns cancellation. Its seek waits for ingestion of an offset; it does **not** implement server-time seek or live rolling windows. A Media3/AVPlayer/desktop data-source bridge is substantial additional work.

Source: [YouTubeSabrStreamingSession.kt](https://github.com/Apdelrahman1911/kmpYT/blob/533562f781304338f72b27d32cfe5fb7a83dc3a8/kmpyt/src/commonMain/kotlin/dev/kmpyt/extraction/YouTubeSabrStreamingSession.kt), especially 16–46, 62–79, 96–107, 122–135, 163–178, 213–249.

### Expiry and refusal

The provider reads `expire` from resource URLs or `streamingData.expiresInSeconds`; expired-at-inspection formats become unavailable. Null expiry remains unknown, not infinite. Only a bounded player-program cache is reused (client sets one cached program entry); metadata/signed resource URLs are refreshed on inspection.

`refresh(plan)` re-inspects and preserves representation identity, transport, codecs, dimensions, language/track, sample rate/channels, DRC/HDR and related characteristics. It does not silently select a different quality/language/transport. There is no automatic player URL-recovery loop. Persist canonical content identity + chosen format identity/characteristics + position, **not signed media URLs or raw plan/report objects**, then resolve again when needed.

JVM/Android download code maps HTTP 401/403/410 to an **internal** `ExpiredResourceException`; this does not prove expiry caused the response and is not a public typed recovery API. Other public errors are largely exceptions/messages or extraction diagnostics. Veyra should use its own typed source errors, at most a bounded explicit same-track refresh when appropriate, and never retry a reported account/age denial through alternate identities. If login/bot/rate/region rejection is returned, show it and retain an external canonical watch-page option instead of claiming playback.

Sources: `extraction/YouTubeProvider.kt:87–119,197–214,339–340`; `YouTubeClient.kt:145–165`; `extraction/YouTubeRepresentationRefresh.kt`; `internal/HttpFileTransfer.kt:18–21,110–118`; `extraction/YouTubeResponseExtractor.kt:58–61,153`; `youtube/YouTubePlaybackRecovery.kt`.

## 4. Downloads, integrity, resume, and offline state

### What the library provides

- Explicit, cancellable suspend download into a caller-owned path; final destination must not already exist. Same-destination calls are excluded/locked. The application owns jobs, database state, user actions, notifications, retries across app lifetime, and background scheduling.
- Private checkpoint/staging files; completed output is published only after transfer/materialization success. JVM/Android uses sibling `.<destination-name>.kmpyt`; iOS uses an owned checkpoint directory.
- HTTP status, content encoding/media-type, Content-Length, Content-Range/offset/size, EOF, validator, and overflow checks; local HTTP is opt-in for tests. HTTP redirects are revalidated, including HTTPS downgrade prevention on JVM/Android.
- Same-plan interruption resume using validated ranges/validators; changed or unverifiable resources restart safely. SABR has durable byte coverage/segment receipts and resumes a fresh protocol session, not a reused stale session.
- Nullable known-size reporting; unknown does not become zero. No fixed 512 MiB file cap: whole-file totals use `Long`. This does **not** mean unbounded support: planning has a 3,000-part cap, HLS/DASH parsers 4,096 segments, HLS duration bounds, and Android native processing has sample limits. Free-space failures remain possible.

### Important platform asymmetries and limitations

1. **Signed-URL renewal does not retain JVM/Android partials at this revision.** `FileDownloadCoordinator.kt:46–52` hashes the serialized *entire* plan (except SABR checkpoint/diagnostics), including URLs and expiry. A refreshed plan changes the digest and clears prior partials. Normal same-plan resume works; do not advertise refresh-preserving cross-restart resume on these platforms. iOS intentionally strips URLs/expiry from selection identity and validates resource scope plus strong response validators (`IosDownloadResumeStore.kt:39–90`), with a signed-URL-refresh test. This qualifies README's broad wording.
2. **HTTP integrity is not proof of decodable audio.** JVM progressive single-resource processing copies bytes without decoder/container probing (`JvmMediaProcessor.kt:47–49`); iOS's equivalent path publishes nonempty verified transfer bytes without AVFoundation mux/probe (`IosYouTubeDownloader.kt:103–119`). Android performs MediaExtractor/sample validation. Veyra must probe/prepare the final file and check actual audio/duration before showing it as verified offline media. Wrongly labelled server bytes must not receive a successful playable-download claim.
3. Native muxers only accept compatible inputs. iOS segmented downloads require static fragmented MP4; MPEG-TS/WebM segments are unsupported. Live/encrypted/nested multivariant downloads are unsupported. A fixed `.m4a` filename cannot make another container AAC/MP4.
4. Staging/materialization can hold multiple full-track copies; peak disk usage is materially above final output size. Budget and measure it. Do not remove checkpoint files owned by an active job, and do not mistake a partial/cache file for a ready download.
5. Checkpoints are transfer receipts, **not Veyra's persistent library**. The app still needs stable media/download IDs, selected track identity, durable state transitions, safe app-private paths, final byte count/digest/container metadata, missing-file reconciliation, rename/delete behavior, storage quota, and offline restart restoration. Library defaults do not prove cryptographic integrity of a previously completed local file after later corruption.
6. Raw serializable representations/plans contain signed URLs and potentially opaque source state. Do not log/publish them; redact diagnostic evidence and store only the minimal application metadata. iOS checkpoint code explicitly hashes rather than stores signed URLs/headers/session context.

Sources: `internal/FileDownloadCoordinator.kt:26–85,94–159`; `internal/HttpFileTransfer.kt`; `internal/RangedFileTransfer.kt`; `downloads/DownloadProtocol.kt`; `extraction/TransferPlan.kt`; `IosDownloadResumeStore.kt`; `IosDownloadResumeTest.kt:19–44`; `IosYouTubeDownloader.kt:29–35,103–119`; `android/AndroidMediaProcessor.kt:99–139,220–271`.

## 5. Desktop runtimes, clean machines, licensing, and reproducibility

The JVM factory explicitly says no executables are bundled. `DesktopYouTubeJavaScriptRuntime` locates Node and a browser using `KMPYT_NODE`, `KMPYT_CHROMIUM`, or a short hardcoded path list—not general PATH discovery. Its Node helper uses built-in modules/native WebSocket, not an npm/Puppeteer dependency tree. Node 22 is the documented requirement. A fresh isolated Chromium profile executes player programs offline; requests are blocked, no user's browser profile is imported, and owned processes/files are cleaned up on close. Cold preparation may allow 60 seconds; ordinary transforms are bounded.

**Clean-machine plan:** either ship vetted per-OS/per-architecture Node/Chromium artifacts with licenses/notices and verified checksums, or make source unavailability an explicit diagnosed dependency/setup requirement. Merely having Chrome/Node on this development host is not packaging. A self-contained JVM app also needs an appropriate Java runtime and its chosen audio decoder/runtime; those are separate from kmpYT.

For repository-only execution, align **both** JVM `-Djava.io.tmpdir=<Veyra>/.work/tmp` and `TMPDIR` (plus `TMP`/`TEMP` where relevant) to that same real directory. Java creates the helper/profile; Node's `ownedProfile()` requires its parent to equal `os.tmpdir()`. Mismatching them causes `OFFLINE_PROFILE_INVALID`. Scope Gradle/Kotlin Native caches, temporary files, app data, and any browser HOME/XDG state under `.work`. Do not alter global SDK files, PATH, browser installs, kernel settings, or sandbox policy.

The helper launches Chromium with its sandbox, without `--no-sandbox`. A root-only/container/unsupported-user-namespace launch can fail before extraction; record that as a runtime/environment problem instead of patching away sandboxing or labeling it a YouTube parser failure. Upstream Linux CI changes a kernel AppArmor setting on its disposable runner; that is **not** a safe deployment prerequisite to copy onto a user's machine.

FFmpeg is caller-supplied through the factory/path or `KMPYT_FFMPEG`, and used only on verified local files for stream-copy combining. Simple progressive audio-only download avoids it. Distribution requirements depend on the exact FFmpeg build, enabled components, and chosen media runtime; retain applicable LGPL/GPL notices/source obligations rather than assuming all FFmpeg binaries have one license. Chromium/Node/JRE and all transitive dependencies also need a collected license/SBOM inventory.

No release/application size was established. The small source checkout excludes Node, browser, FFmpeg, decoder, JRE, Compose, and other dependencies. **Do not quote repository size as installer size.** Report compressed artifact bytes, installed bytes, runtime memory/startup, and exact bundled versions/checksums per target after a clean-machine package test.

Recommended integration: pin this immutable Git SHA and validate it in a bootstrap/verification script; use Gradle `includeBuild` with explicit substitution for the coordinates, or a versioned checksummed internal artifact. Avoid mutable `main`, opportunistic `mavenLocal()` artifacts, or a system-wide preinstalled checkout. Keep upstream unmodified for the first spike; submit only evidence-driven, separately reviewed fixes later. Exact plugin/dependency versions and wrapper SHA exist, but upstream has no dependency lockfiles/verification metadata and CI actions use moving major tags; reproducible application release inputs need tightening separately.

License finding: GitHub `license=null`; complete tree has no root `LICENSE`; Maven POM omits license metadata. The **CC0 fixture license only covers authored test media**, not the library. The README's ClearMedia extraction/provenance statement and yt-dlp profile attribution are not a redistribution license grant. `YouTubePublicPlayerClients.kt` pins profile facts to yt-dlp 2026.10.26; no yt-dlp executable is bundled. Resolve the owner's intended library license and third-party provenance before public distribution.

Sources: `jvm/JvmYouTubeClient.kt:15–37`; `jvm/DesktopYouTubeJavaScriptRuntime.kt:19–67,110–141`; `jvmMain/resources/youtube/chromium-runtime.cjs:1–63`; `jvm/JvmMediaProcessor.kt`; `.github/workflows/ci.yml`; `extraction/YouTubePublicPlayerClients.kt:6–42`; `jvmTest/resources/research/LICENSE.txt`.

## 6. Validation evidence: controlled versus live

### Independently checked public CI metadata

The latest pinned-commit [Cross-platform CI run 42063400390](https://github.com/Apdelrahman1911/kmpYT/actions/runs/42063400390) completed **success**. [Jobs API](https://api.github.com/repos/Apdelrahman1911/kmpYT/actions/runs/42063400390/jobs?per_page=100) reports six successful jobs: JVM Linux/Windows/macOS, Android API 29/35, iOS simulator/XCFramework. This is upstream CI evidence, not a local Veyra build or a real-media playback assertion.

The checkout contains 340 `@Test` annotations in common tests, 51 JVM, 48 Android instrumented, and 25 iOS. These are **source annotation counts, not an executed-test total**; some live checks are opt-in, common tests repeat across targets, and hosted Swift XCTest cases are separate. Tests cover browse parsing/continuation, inspection freshness, player program/cache lifecycle, cancellation, manifest expansion, source identity, SABR framing/coverage/read/seek waits, range/EOF/integrity, checkpoints/resume, and native media processing with authored fixtures/local servers.

### Live evidence must stay separate

- README records [approved live run 41980615183](https://github.com/Apdelrahman1911/kmpYT/actions/runs/41980615183) on 2026-11-05 at older commit `19d92663795a3400e60a7892ce487b45be3aeb2a`: channel reached, video extraction yielded `AUTHORIZATION_REQUIRED`, zero formats, no media download. API confirms run failure. Subsequent changes do not turn this into a successful live run.
- README records local Android 15 `TRANSPORT_DNS_FAILURE`, before media transfer. It explicitly says emulator live playback remains unverified.
- Veyra integration-feasibility agent's **2026-11-06T05:05:49Z** public-page probe: search `oPiiLz` HTTP 200/`ytInitialData`; channel `/@oPiiLz/videos` HTTP 200 and actual `UCHVDgaiLbpj5Leazw8vsAmg`; selected public search video `J_UH5zb-kf4` watch HTTP 200 but `playabilityStatus=LOGIN_REQUIRED`, reason `Sign in to confirm you're not a bot`, no `videoDetails`, no progressive/adaptive/HLS/DASH formats. No media, cookies, profile variation, or bypass was attempted. This is a cross-team direct-page probe, **not yet the kmpYT JVM API execution**; consult that agent's evidence/report for the raw redacted record.

A watch page returning HTTP 200 can still deny playback. The provider maps `LOGIN_REQUIRED`/`AGE_CHECK_REQUIRED` to `AUTHORIZATION_REQUIRED`; the extractor stops alternate-client/transform attempts on that denial. The library's use of pinned public profiles in other permitted cases does not authorize bypassing explicit refusal. Do not replace a denied YouTube result with a sample MP3/podcast and claim the YouTube pipeline passed.

## 7. Earliest real vertical spike and acceptance gates

Run these gates serially on the first available real target; maintain target-specific evidence instead of assuming KMP compilation proves native behavior.

1. **Resolve/build:** verify pinned SHA; compile the actual library and Veyra adapter with scoped caches. Run the offline library tests. Capture exact environment/runtime versions and redacted results. Missing browser/runtime and transport refusal must be distinct.
2. **Real discovery:** call `youtube.search("oPiiLz")`, show actual results and notices. Call `youtube.channel("@oPiiLz")`; use the actual channel ID from its response. Choose a result from that approved channel, not an invented/sample item. Test `more` where offered; do not append its already-accumulated items twice.
3. **Real source:** call `inspect(result.url)`; check diagnostic codes and actual uploader ID before any media transfer. If an authorization/bot/rate/DNS error occurs, record an honest blocked source outcome and stop that path. If permitted, find a real progressive AAC/MP4 audio candidate by role/MIME/codec; do not assume itag 140 or manipulate signatures. If only unsupported transport/codecs exist, report the exact unsupported capability, not general library failure.
4. **Play then seek:** obtain `playbackPlan`; native prepare the actual source, decode/output audio, prove position advances, pause/resume, and seek forward/backward. Verify read position/decoded audio beyond the seek target, not just a moved UI slider. Repeat with rapid item switching/cancellation. Do not call a plan or downloaded byte range playback evidence.
5. **Download:** `plan(report, selection)` then `download(plan, appPrivateDestination)` with visible progress and cancellation. Probe the completed container/audio track and duration before marking verified ready, including native prepare/read/seek near the beginning, middle and end. Verify exact length when source supplies it; compute a local digest. For the first live smoke use an explicit approved small resource budget—this is test policy, not a hidden product size cap.
6. **Restart truly offline:** persist content/selection metadata, local artifact identity, queue and position; close client/player; terminate/restart the app; deny network; open the saved artifact, decode audio and seek; ensure no reinspection or remote artwork fetch is needed for playback. Network denial must cover the native player's own networking, not only a mocked/denying Ktor transport. Assert the resolved local file/cache handle and independently check egress. A surviving in-memory player/cache does not pass.
7. **Recovery:** authored servers separately exercise missing/weak/changed validators, ignored/wrong ranges, truncated bodies, mislabeled media, interrupted transfer, same-path exclusion, storage full, app kill, missing/corrupt final file, and expiry refresh. Confirm JVM/Android renewed-signature restart versus iOS validated continuation, rather than promising platform parity not implemented.
8. **Clean-machine package:** install and run on a machine without development Node/Chrome/FFmpeg/JDK; verify bundled dependencies or explicit diagnosed unavailable features, license notices, data paths, uninstall behavior and target architecture. On Apple/Android run actual background/lock-screen/interruption tests; desktop build success cannot substitute.

Minimal **nonexecuted** audio selection sketch (use platform-specific factory and a real discovered approved URL):

```kotlin
val report = youtube.inspect(discoveredItem.url)
check(report.diagnostics.none { it.code == "AUTHORIZATION_REQUIRED" })
val media = report.media.single()
check(media.uploaderChannelId == actualApprovedChannelId)
val audio = media.representations
    .filter { it.selectable && it.role == TrackRole.AUDIO &&
        it.transport == TransportKind.PROGRESSIVE &&
        it.mimeType.substringBefore(';').equals("audio/mp4", ignoreCase = true) &&
        it.codecs.orEmpty().split(',').any { codec -> codec.trim().equals("mp4a.40.2", ignoreCase = true) } }
    .sortedWith(compareByDescending<Representation> { it.audioIsDefault == true }
        .thenBy { it.isDrc == true }.thenByDescending { it.bitrate ?: 0L })
    .firstOrNull() ?: error("No compatible progressive audio source offered")
val selection = YouTubeFormatSelection(primaryId = audio.id)
val playback = youtube.playbackPlan(report, selection)
// Veyra's native adapter must now prepare/play/seek playback.plan.primary.url.
// Only after actual playback evidence: plan/download/probe/persist/restart offline.
```

Imports for the sketch: `dev.kmpyt.extraction.Representation`, `TrackRole`, `TransportKind`, and `dev.kmpyt.youtube.YouTubeFormatSelection`. This conservative first spike selects declared **AAC-LC (`mp4a.40.2`)**; merely matching the `mp4a` prefix is not an AAC-LC/decoder guarantee. Other AAC profiles/codecs require their own tested capability path, and native preparation remains decisive even for AAC-LC. For production replace generic `check/error` with typed, user-visible capability/refusal states. Background lifetime, coroutine ownership, audio focus, file permissions and persistence remain Veyra responsibilities.

## 8. Proposed narrow upstream follow-up: refreshed HTTP checkpoint identity

**Status: design only; no library edit or executed regression.** The lead is awaiting the M0 research freeze and the owner's license decision. Existing pinned CI is green; this does not justify toolchain drift or edits intended to bypass source refusal.

The concrete issue is an unnecessarily lost resume opportunity, **not demonstrated byte corruption**: `FileDownloadCoordinator.kt:46–52` digests full `PlannedDownload` JSON and discards the checkpoint if any signed URL/expiry changes. `YouTubeClient.refresh()` legitimately changes those values while preserving selected track identity. The iOS implementation/tests already separate selected media identity from refreshed resource addressing. Current JVM/Android fallback is safe but potentially downloads a large interrupted file again.

Suggested first PR scope: preserve validated **single-resource progressive HTTP** partials across equivalent signed-URL refresh, covering the shared JVM/Android file coordinator; keep current conservative behavior for SABR/segmented cases unless independently proven. Do not broadly remove URL fields from the existing digest and call it fixed.

Required design:

1. Introduce a versioned checkpoint schema with a stable **selection digest** plus a per-part **resource-scope digest**, retaining exact role/format/source video, codec/language/DRC, transport, declared byte size and range identity. Changes to tracks/content scope must still invalidate old bytes. Prefer explicit reviewed identity fields over accidental dependence on every future serialized property.
2. Permit only documented ephemeral signing changes within the same resource scope, and require a strong matching response validator before appending any refreshed response bytes. Non-YouTube origins/path/query changes remain conservative. Treat cross-CDN/host equivalence explicitly; identical ETags across unrelated origins alone do not prove identity. Existing `iosSelectionDigest`, `iosResourceScope` and signed-refresh test are useful references, not an instruction to copy them without review.
3. For a previously complete part under a refreshed resource, require safe response revalidation before reusing it; do not skip the network solely because local length equals old length. Reject/clear malformed, duplicate-key, out-of-range or incompatible checkpoints; truncate uncommitted tails to proven receipts. Keep raw signed URLs/tokens out of the new persisted schema/logs.
4. Migrate legacy checkpoints conservatively: reuse only when the original full-plan digest matches; otherwise restart rather than inventing resource identity the old schema did not preserve. Do not relax SABR generation binding (`YouTubeSabrTransfer.kt:588–594`) or playability authorization stop conditions.

Regression sequence using only authored media and loopback `MockWebServer`:

- First request returns a throttled real authored AAC/MP4 fixture with stable strong ETag. Cancel after a committed nonzero prefix; assert destination absent and private partial present.
- Create a **new** downloader/coordinator instance and a copied plan with only `sig`/`expire` URL parameters and `expiresAtEpochMs` refreshed. Server answers only the exact remaining range with `206`, matching strong ETag and exact `Content-Range`/size. Assert request `Range: bytes=<committed-prefix>-`, no full-file refetch, byte-exact final fixture, valid decoded audio and successful cleanup.
- **Expected old-code failure:** changed digest discards prefix, so second request lacks Range/starts at zero. This is a deterministic local failure independent of YouTube availability. Implement this failing test before the fix.
- Negative controls: changed/missing/weak validator, same ETag but changed media/codec/language/range/content scope, expiry change with identity change, HTTP 200 despite Range, changed object total, short/overlong response, corrupt/full-but-uncommitted part, cancellation while checkpointing, and concurrent same-destination call. Every case must safely restart/reject, never concatenate different generations.
- Keep existing `FileCheckpointIntegrityTest`, `HttpDownloadTransferTest`, `HttpResponseIntegrityTest`, `RangedFileTransferTest` and all common/JVM tests green; mirror the regression on Android instrumented tests because production file code is shared. Report executed commands/results separately. No live YouTube request is needed to prove this fix.

## 9. Cross-review actions

The integration-feasibility agent accepted these three challenges to the podcast/source architecture:

1. Episode identity is `(canonical feed identity, GUID)` with feed alias handling; enclosure/download **generation** is separate. RSS length, mirror URL, or reused ETag alone cannot certify byte equivalence. Test replaced enclosures and rotating mirrors.
2. Convert safe fetch/XML policy into tests: bounded parsing, DTD/external entities disabled, per-hop URL/credential/scheme validation, private/link-local/loopback IPv4/IPv6/DNS protections, and no credential leakage across origins. A HEAD response is not proof about later GET bytes.
3. Offline denial must cover native player networking; a denied application HTTP client alone is insufficient. Terminate the process, resolve an actual local artifact, deny/check native egress, and verify playback/seek on restart.

Mobile-audio review additionally confirmed that generic kmpYT suspend transfer is not an OS-durable background executor, that Media3 cache spans and native HLS packages require opaque offline handles rather than fabricated `.m4a` paths, and that `mp4a.40.2` is a narrower AAC-LC selection than a generic `mp4a` prefix. Those constraints are now reflected in the spike above; their platform implementation belongs to the app rather than this source-audit PR.

Architecture review sent three implementation challenges: (a) if DataV1 uses atomic JSON snapshots, one serialized read-modify-durable-write owner must prevent progress/library/queue/download updates from overwriting each other; rename alone is not a transaction, and the final architecture document must resolve its earlier SQLDelight proposal; (b) freeze one durable download specification/owner across kmpYT plans versus Media3/background URLSession—native executors do not automatically inherit kmpYT's identity/validator protections; (c) preserve typed local file/cache/package handles and prove native-player offline behavior independently of application HTTP mocks. These are acceptance requirements, not claims that implementation already satisfies them.

## 10. Model/configuration attestation boundary

The user requested `gpt-6-astra` with `max` reasoning for research. This audit agent cannot independently inspect/verify the backend model actually serving inference. Any harness-reported spawn/model configuration should be recorded by the lead as configuration evidence, not claimed as cryptographic/runtime attestation. No additional subagents were spawned by this audit agent.
