# Music UX research — Veyra (ڤيرا)

**Phase:** research and proposed decisions; no implementation. **Reviewed:** 2026-11-06.

**Evidence scope:** read-only review of the current official Spotify, Apple, and Google support pages linked below. These substantiate documented workflows, not a hands-on usability study or verified pixel-level appearance. Features vary by platform, region, subscription, and release. No competitor screenshots, artwork, or other assets were copied. Recommendations are original Veyra design proposals, not claims that every competitor already implements them.

## 1. Findings that should shape Veyra

| Area | Documented real experiences | Veyra recommendation |
|---|---|---|
| Discovery and search | Spotify searches songs, albums, artists, playlists, podcasts, genres, and moods; lyric matching accepts at least three remembered words [S1]. Apple explicitly separates catalog and library search [A1]. YouTube Music distinguishes personalized Home from Explore and supports result-type filters [Y1]. | Make search a primary destination, with a clearly named **Everywhere / Your library** scope and Music/Podcasts/type filters. Let people browse without typing, but do not pretend that unimplemented personalization, lyric search, or audio recognition works. Show local results even when a catalog request fails. |
| Library semantics | Spotify explicitly says saving an album/artist does not save all its songs [S2]. YouTube Music says saving an album adds its individual songs to Songs; unliking does not automatically remove a song from the library [Y2]. Apple distinguishes adding music from downloading it [A2]. | There is no single universal convention to inherit. Specify a non-destructive local model: **save/favorite, playlist membership, and offline bytes are distinct**. Never let removing a download delete a favorite or playlist entry. |
| Mini/full player and queue | Spotify puts mobile Now Playing above navigation and opens a larger view; desktop exposes a different Now Playing surface [S3]. Apple documents MiniPlayer → Now Playing, seek, artist navigation, favorite, and queue controls [A3]. Spotify permits queue reordering/removal on mobile and desktop [S4]. Apple explicitly distinguishes Play Next from Add to Queue [A4]. | One persistent transport, a compact player for browsing, and a focused full player. Queue must be inspectable in one action from the full player and desktop transport. Keep **Play next** and **Add to end** distinct; do not conflate starting playback with queue insertion. |
| Repeat and shuffle | Spotify distinguishes ordinary shuffle from Smart Shuffle, which inserts recommendations [S5]. Apple documents shuffle and repeat-all/repeat-one [A5]. YouTube Music exposes repeat-one and repeat-playlist/album, with different availability for other contexts [Y3]. | Conventional shuffle **Off/On** only; repeat **Off → All → One → Off**, visibly and accessibly labeled. Recommendations are separate opt-in content, never hidden inside ordinary shuffle. |
| Offline | Spotify marks not-downloaded content unavailable in Offline Mode and documents storage/network/device problems [S6]. Apple has separate add and download actions on phone and desktop [A2]. YouTube Music supports explicit downloads and optional history-based Smart Downloads, with connection/storage settings [Y4]. | Keep the whole library usable for organization offline; only unavailable playback actions are constrained. Show per-item download state and failure reasons. Smart Downloads should not be an implicit default. Subscription restrictions in competitor products are **not** Veyra requirements or evidence of Veyra download rights. |
| Desktop productivity | Spotify desktop supports column sorting, remembered view options, right-click, drag/drop, and playlist editing [S7, S8]. Apple Mac has a compact MiniPlayer, optional always-on-top, queue persistence, and keyboard commands [A6, A7, A8]. YouTube Music playlists also appear in a computer sidebar [Y5]. | Build a desktop layout, not a stretched phone: navigation rail, main collection/results pane, optional docked queue, persistent bottom transport. Include keyboard/context-menu alternatives for every gesture. A detached mini-window is an optional later enhancement, not a prerequisite for usable desktop playback. |

### Important divergence: account-free means genuinely local

Spotify's local-file workflow requires platform permissions and different import mechanisms [S9]. YouTube Music uploads are account-bound, performed on computer, and searched in a distinct Uploads scope; other people cannot play the uploaded items in shared playlists [Y6]. Veyra should **not** copy that cloud-account mental model: importing, favoriting, searching, playlist editing, and playback of usable local files must work before any remote source is configured. Explain whether import copies a file or references it. Never imply cross-device sync or portable remote downloads merely because the app has five platform builds.

## 2. Original screen concepts and information architecture

**Identity: “Quiet currents.”** A calm listening room rather than a storefront: warm ink/paper surfaces, restrained sea-glass actions, sand accent, and content artwork as the principal color. This aligns with the design researcher's proposed petrol/paper system. Avoid imitating Spotify green, Apple promotional layouts, YouTube's video feed, giant promotional carousels, or ornamental audio waveforms everywhere. Use text labels and subtle state marks, not color alone. Font selection and measured contrast belong to the design/a11y specification.

**Initial phone-shell candidate:** three stable destinations — **Listen / استمع**, **Search / بحث**, **Library / مكتبتي**. The integrated review subsequently selected **four destinations including Podcasts** for its dedicated hub; see §8. Content filters still select media rather than separate applications. These Arabic labels are provisional and require native-language review. The mini-player occupies a stable row above navigation and respects system insets.

1. **Listen — “Your listening room.”** A single Continue card for the active context, then recently played collections and a small discovery section. Fresh install offers **Import audio**, **Find music**, and **Find a podcast**; it does not invent personal recommendations. Offline removes the network dependency, not the Continue/local sections. Never start audio solely because the screen opened.
2. **Search — “Find in your world.”** Persistent query, scope, type chips, and grouped results with explicit headings. A library result can show an on-device indicator without looking like a separate duplicate recording. Empty-query browsing, no matches, loading, offline catalog, and catalog failure are separate states. Preserve the query, filter, scroll, and local results on retry/back navigation.
3. **Library — “Your shelves.”** Favorites, Playlists, Albums, Artists, Podcasts, and **On this device** are immediately understandable entry points. Downloads lives here as a visible destination, not only under Settings. Local search, recent/name/creator sort, and list/grid preference survive navigation. A sparse library shows helpful actions rather than empty decorative shelves.
4. **Album/artist detail — “A readable collection.”** Clear creator/title metadata, play and shuffle as distinct actions, save and download eligibility, and an ordered track list. Track menus expose Play next, Add to end, Favorite, Add to playlist, and available download actions. Do not silently substitute a different recording when a result is unavailable.
5. **Playlist detail/editor — “A list you own.”** Editable title, count, duration, and availability summary; search within; visible add/remove/reorder controls; batch selection on roomy layouts. Duplicates require an explicit choice rather than silent accumulation. The displayed sort order is not silently committed as the saved playlist order.
6. **Full player — “One thing playing.”** Artwork, title/creator, context name, favorite, elapsed/duration, seek, previous/play/next, shuffle/repeat, and queue. Secondary actions belong in an overflow menu. Show buffering/error in place without losing the track. A stable artwork fallback handles missing covers. Optional lyrics require real availability and rights; no fake synchronized text.
7. **Queue — “What actually comes next.”** Active context name, current item, ordered upcoming items, origin labels where useful, duration/count, reorder/removal, clear-upcoming, and undo. Prefer origin annotations over hard-coded segments whose meaning conflicts with “Add to end.” Keep a selected item distinguishable from the currently audible one.
8. **Downloads — “Ready to go.”** Ready, queued, downloading, paused, and needs-attention groups; bytes/progress where known; cancel/retry/remove. Explain Wi-Fi policy, storage exhaustion, missing external files, and expired/unavailable remote content without exposing raw exceptions.

**Desktop adaptation:** retain the same destinations and semantics, but use density and parallel visibility intelligently. Show columns only when space permits; keep target sizes usable; right-click and keyboard selection complement touch. The queue panel can collapse without changing playback. Resizing or switching compact/expanded layouts must retain query, selection, queue, position, and focus. Do not assume every large window has a mouse.

## 3. Proposed interaction contract

- **Local ownership:** favorites and playlists persist in the local database, not a login session. The integrated decision treats save as favorite/reference, rather than inventing a second saved-song membership. Unfavorite removes that membership only; it does not delete playlist memberships or downloaded bytes. Deleting a playlist does not delete original files. Saved collection references must represent real source entities; a generic source playlist must not be relabelled as a fabricated music album.
- **Playback continuity:** navigating, editing metadata, opening a menu, changing language, or collapsing the player never restarts audio. Opening an item detail is not equivalent to pressing Play. A new collection's Play action makes queue replacement explicit when it would discard intentional additions; the confirmation/undo design must preserve user work.
- **Queue meanings:** Play next inserts immediately after the current item; Add to end appends after the last visibly pending item. Adding a multi-track selection preserves its selected order. Queue occurrences need distinct identity so removing one duplicate does not remove them all. Clear upcoming preserves the current item. Queue/history and playlist contents are different objects.
- **Shuffle/repeat:** toggling shuffle does not restart or replace the current item; it randomizes future eligible items without hidden suggestions. Repeat-one has a visible “1” and spoken state; a deliberate Next still advances. Repeat-all applies to the displayed music context, not every favorite in the database. Integrated resolution: unshuffle restores insertion ranks while preserving manual additions; a manual reorder explicitly turns shuffle off, disclosed in the action label. Preserve occurrence identity and provide undo of the edit.
- **Two contexts, one audible transport:** agreed with podcast research: maintain persistent **Music Queue** and **Podcast Queue** contexts; never auto-mix them. Pressing **Play/Resume** on an episode saves outgoing music state and switches deliberately; merely selecting a row, reading its details, or inspecting its queue never switches transport. Returning offers resume, not surprise restoration. Podcasts remain sequential and do not inherit music shuffle/repeat. A truly mixed queue is a more complex alternative, not the default. A duration-based sleep timer is global active-session state, visible and cancellable in both music and podcast players; an episode-bound timer cancels visibly when that episode is deliberately replaced.
- **Source honesty:** show library/download controls independently of whether remote playback is currently possible. Distinguish network loss, source failure, unavailable content, missing file, access denied, unsupported format, and storage exhaustion. Only expose downloads that the adapter and content rights actually permit. A support article describing a competitor download feature establishes UX precedent, not a license to implement that feature against its catalog.
- **RTL and accessibility:** mirror navigation/reading flow, not media time/progression indiscriminately. The design/a11y report owns the exact media-direction exception. Every swipe/drag has a labeled menu/button/keyboard alternative; expose playback state, repeat value, progress, and favorite state to assistive technology. Artwork text must not be the only source of metadata.

## 4. Four end-to-end journeys

1. **Fresh, account-free, offline:** launch → import permitted audio → see import result and actionable file errors → play → favorite → create playlist → relaunch offline → same library and recoverable paused transport. No login or remote call is necessary.
2. **Discover, then own the next action:** Search → online result → album detail → play → favorite → Play next another track → inspect queue → optionally download eligible content → lose connection → continue from available media without clearing the queue.
3. **Build a desktop collection:** select multiple tracks with keyboard → add to new playlist → rename → reorder with keyboard or pointer → undo mistaken removal → resize narrow → collection and playback remain consistent. Tab order and focus restoration still work.
4. **Music to speech and back:** while music is shuffled, inspect a saved episode without interrupting music → press Resume to activate its recorded position in Podcast Queue → finish/pause → deliberately return to Music Queue and press Resume → retain its order and mode without replaying the episode or losing music additions.

## 5. Acceptance tests for the implementation plan

These are proposed tests, **not executed results**. Use deterministic local fixtures for core behavior; test live sources separately.

| ID | Concrete acceptance |
|---|---|
| M01 | Fresh install with network disabled: import two supported files, play one, favorite it, create/edit a playlist, terminate/relaunch; all local records remain usable without authentication. |
| M02 | Query a known local title while the catalog adapter returns timeout; local results remain interactive, query/filter remain visible, and only the catalog section reports failure with Retry. |
| M03 | Search English, Arabic with optional diacritics, and mixed-script creator/title fixtures; no bidi corruption, clipped Arabic marks, or mismatched accessible labels. Matching normalization must be explicitly documented, not silently equate different recordings. |
| M04 | From mini-player open full player and queue, navigate away and back, resize desktop, and change app language; current occurrence and position remain continuous, with no second audible stream. |
| M05 | For queue A(current), B, C: Play next D yields A,D,B,C; Add to end E yields A,D,B,C,E. Remove a single duplicated occurrence and verify the other survives. |
| M06 | Clear upcoming while A plays; A continues. Undo removal/clear within the offered recovery window restores the intended occurrences and order. |
| M07 | Shuffle a ten-track fixture: current track is unchanged; one traversal contains every pending eligible occurrence exactly once, no foreign suggestions; disabling shuffle loses no manual additions. |
| M08 | Exercise Off/All/One including the final track and explicit Next. UI and accessibility state agree; repeat-one loops only natural completion, and manual Next advances. |
| M09 | Save album, favorite/unfavorite song, remove download, and delete playlist independently; none unexpectedly deletes original audio or unrelated library records. |
| M10 | Interrupt an eligible download, fill storage, deny file access, and remove an external file; each yields the correct recoverable state, never a false Ready badge or lost favorite. |
| M11 | With mixed downloaded/online-only playlist, disconnect during playback: preserve all entries and show exactly which are usable. No infinite spinner, repeated failure loop, or silent queue deletion. |
| M12 | Desktop-only keyboard and screen-reader passes: search, play/pause, queue open, reorder/remove, create playlist, and close dialogs without pointer use. Space typed into a field never toggles playback. |
| M13 | Large text and RTL phone at narrow width: title/artist remain identifiable, primary controls are reachable, menus respect direction, and no gesture is the sole route to an operation. |
| M14 | Start shuffled/repeat-one music, switch to podcast, then return deliberately: one stream only; saved episode position retained; podcast never inherits those music modes; both pending queues survive. |
| M15 | Every empty/error state has a correct next action: empty library→Import/Find; empty playlist→Add; no matches→Clear filters; offline catalog→Library/Retry; storage full→Manage storage. No simulated success or fabricated content. |

## 6. Alternatives, risks, and decisions to close

- **Discovery-heavy home vs library-first:** discovery is attractive with a reliable catalog, but punishes offline/first-run users and creates personalization expectations. Recommend local Continue/recent content first, then clearly labeled online discovery.
- **Favorites-only vs saved-library-plus-favorites:** favorites-only is simpler but cannot distinguish “keep this album” from “love this song.” Recommend the richer model only with the non-destructive rules above and comprehensible labels.
- **One mixed queue vs two contexts:** mixed queues are flexible but make music repeat/shuffle hazardous for long-form listening. Recommend two persistent contexts and a deliberate switch; final lead approval required.
- **Always-visible vs optional desktop queue:** permanent three-pane layout improves inspection but overwhelms narrow windows. Recommend optional docked queue with accessible one-action entry.
- **Smart Shuffle/Downloads, audio recognition, rich lyrics, floating player:** valuable later enhancements, but each has policy, infrastructure, storage, accessibility, or platform costs. Do not label them working until the relevant adapter/capability and tests exist.
- **Before implementation:** finalize album-save membership, unshuffle after manual edits, new-collection replacement UX, local copy/reference import semantics, and remote capability/rights matrix. Native Arabic review and real phone/desktop usability testing remain required; this desk research cannot substitute for them.

### Supplementary option requested during cross-review: explicit open music

The lead requested consideration of an optional legal, no-key music source after the approved YouTube fixture encountered a source-side challenge. **Wikimedia Commons is a candidate, not an implemented fallback or a verified playback result.** An unauthenticated MediaWiki search/imageinfo request succeeded. For the specific [Beethoven — 32 Variations in C minor, WoO 80 recording](https://commons.wikimedia.org/wiki/File:Beethoven_-_32_Variations_in_C_Minor,_WoO_80.ogg), the source wikitext explicitly separates the public-domain composition from the **CC0 performance**, credits own-work performer La Pianista, and dates the recording 2010-11-27. Metadata reports an approximately 12.27 MB, 728-second Ogg file. Codec support, range behavior, playback, and offline operation have **not** been verified by this research agent.

If approved, label this source **Open music**, retain creator/source/license provenance, and verify each selected recording independently. Do not silently substitute it for a selected YouTube recording or report Commons playback as YouTube success. A curated rights-reviewed fixture is lower-risk than promising a complete unmoderated Commons music catalog. [Commons' reuse guidance](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia) explicitly requires per-file license checks and disclaims any warranty of copyright information; a public-domain composition alone does not establish rights to a recording.

## 7. Cross-review: three concrete challenges and proposed resolutions

Reviewed [podcast-ux.md](podcast-ux.md) and [design-accessibility.md](design-accessibility.md). These are contract issues for the lead to resolve, not a vote about preferred screens.

1. **Navigation must identify distinct jobs, not duplicate filters.** The original three-destination proposal is simpler, but podcast research identifies a genuine Continue/New/Following/Add RSS hub. If the lead chooses four labeled destinations (**Listen / Search / Podcasts / Library**), Podcasts should own those show/episode tasks, Library should own unified saved/downloaded content, and Search should remain global. Define whether Listen is cross-media recency plus music discovery or music-only; the latter warrants a Music label. Evidence: podcast report §2/§4 and Apple’s distinct show/saved/downloaded library model; current YouTube Music uses content filters but does not prove three destinations are universally better [Y1, Y2]. **Test:** fresh English/Arabic users can locate Add RSS, resume a followed episode, and return to music with no more than two destination selections per task; 320dp-equivalent width/200% text retains labels and reachable actions. If capacity fails, use an accessible adaptive navigation fallback rather than shrinking text.
2. **Viewed context, audible context, and timer scope are three different states.** Both reports preserve two queues, but inspecting Podcast Queue while music plays must not change the mini-player to the inspected episode. Podcast duration-timer wording also needed a cross-mode rule: a timer following the listener into Music must not become invisible. Resolution agreed with podcast research: queue header names the viewed mode and marks the active one; mini-player always represents the audible transport; only Play/Resume switches it. Duration timer follows the active session and is visible/cancellable in both player types; end-of-episode timer remains episode-bound and cancels visibly on deliberate replacement. **Test:** set 60 real seconds at 2× podcast speed; inspect Music Queue without transport change; press Play on music at 30 seconds; timer remains visible and pauses exactly one active item at expiry without queue advance. Inject a context switch at the deadline with a fake monotonic clock to expose stale callbacks.
3. **Metadata success is not playability, and source restriction is not an empty library.** Both state matrices already separate empty/error; strengthen their combined case using the integration team's actual YouTube observation: discovery returned metadata while the selected watch request returned `LOGIN_REQUIRED` and zero formats. Use typed, localized source/item capability errors distinct from device connectivity, no results, malformed feed, missing file, and storage failure. Retain local results and cached records; never manufacture a successful Play action or replace a selected recording. If Open music is approved, label it and retain its own stable source identity and license provenance. Commons' flattened metadata saying “Public domain” does not replace the inspected composition/performance license distinction. **Test:** inject successful metadata + restricted playback for one source, a usable local file, and a rights-reviewed Open-music item. Only the restricted item reports the restriction; no source is silently substituted; favorites remain; each playback/download claim is verified against that actual source. Source request/decode success remains separate from native five-platform playback evidence.

## 8. Integrated lead decisions after cross-review

The lead explicitly resolved the following before implementation. These supersede earlier alternatives, not the source observations:

- **Four destinations:** Listen / Search / Podcasts / Library, preserving a distinct podcast hub.
- **Two persistent queues, one player:** explicit active context, inspection separate from activation.
- **Save = favorite/reference:** no deletion of audio bytes as a side effect of membership changes.
- **Honest source entities:** album/channel surfaces require real source support; do not synthesize album entities merely to fill a conventional music layout.
- **Unshuffle:** restore insertion ranks and retain manual additions. **Manual reorder turns shuffle off**, made explicit before the action.
- **New collection playback:** replacing pending work requires a clearly named action and Undo; merely opening/selecting a collection never replaces it.

The timer visibility and source-restriction challenges above are additional implementation checks. No native application behavior has been verified by this report.

## 9. Later owner scope revision and implemented presentation contract

The lead relayed a subsequent explicit owner revision during implementation. It supersedes the
earlier execution-source recommendation above; it is a product requirement, **not evidence that
title matching is reliable**:

- All playback/download execution respects the **complete selected title → first kmpYT
  result**. External music/podcast sources supply discovery metadata only. No selection of a later
  result to improve a match, no hidden direct-enclosure/Commons fallback, and no fabricated audio
  when the first result is restricted or unusable.
  Local references/import do not create an independent execution bypass; offline reopening still
  uses the validated owned file and exact acquisition receipt rather than new network resolution.
- Requested discovery identity, resolved audio identity, and file identity are distinct. The full
  player receives its immutable receipt from `PlaybackState.executionBinding`; a Ready download
  receives its receipt through `OfflineAsset.executionBindingId`. An arbitrary newest receipt is
  never evidence about the current session or an older downloaded file. Different titles trigger
  a textual-mismatch warning; equal titles still do not verify that recordings/episodes match.
  Original listing licences do not establish the rights of a different resolved recording.
- The normalized persistent implementation is now **Room**, not the earlier JSON proposal.
  Presentational screens retain `AppSnapshot` only as the root-supplied read model. Actual album
  entities/collections must retain source identity; UI does not invent albums from song titles.
- Music crossfade is mandatory across target engines. Settings belong to the root; the player
  presents typed PREPARING/READY/OVERLAPPING state. Only confirmed OVERLAPPING is labeled active
  and shown with the engine/coordinator's fraction. Outgoing identity stays current until atomic
  promotion; podcast controls never inherit music crossfade/shuffle/repeat.

Owned presentation sources now cover Listen, Search, Library/playlists/downloads, nonplaying track
details, real source collection details, mini/full players, two-context queue inspection, and receipt
disclosure. Track details keep complete requested metadata and listing duration separate from the
native current duration and validated file duration. Current, downloaded, and previous-search
receipts are labeled independently; opening details does not dispatch playback. Public source and
licence links dispatch root-validated intents and remain disabled under offline policy. Incoming
crossfade receipts now use the supplied `incomingExecutionBinding` and an explicit incoming-audio
label, never a guessed current recording.
Whole-collection actions do not silently operate on just the first page: pagination must complete
before a whole-list action is enabled, with an explicit reason and per-item actions still exposed.
Queue actions identify occurrences, while `Clear upcoming` requires confirmation and a distinct
coordinator command preserving the current occurrence/history.

## 10. Executed fixture evidence and pending UI repair — 2026-11-06

The **second** combined guarded invocation compiled production/tests and recorded **249 JUnit
records: 240 passes, 9 failures, zero errors/skips**. It used `scripts/with-display.sh
scripts/gradle.sh`, a command-local 1280MiB heap/512MiB metaspace/SerialGC/two processors, and
filters `dev.veyra.app.*`, `dev.veyra.ui.*`, and `dev.veyra.desktop.*`. The original log is
`.work/music-ui-tests-second.log`; preserved log, XML, invocation and input-hash records are under
`.work/music-ui-evidence/second/`. This was not a green batch or a final-revision claim.

Owned results from that exact run:

| Fixture group | Passed / executed | Scope |
|---|---:|---|
| Music pure presentation | 20 / 20 | Intent/status/occurrence helpers, including existing-asset recheck predicate |
| Root-authorized navigation | 16 / 16 | Pure Back/restoration/route/overlay invariants |
| Music desktop surfaces | 28 / 29 | Authored UI interactions, not native audio |
| Connected CoreShell | 5 / 9 | Actual VeyraApp/AppContainer/controller/Room with simulated engine/gateway |
| **Owned total** | **69 / 74** | **Five remaining failures below** |

The existing-asset recheck correction passed its additional pure and four GUI cases. Music rows,
track details and overflow menus show `VERIFYING`/`NEEDS_FOREGROUND` plus **confirmed Remove**, not
ineffective transfer Cancel/Resume, when an asset exists and cleanup is not pending. This never
validates an asset or grants Ready/offline playback. Asset-null active finalization remains
cancellable; a foreground-restricted asset-null transfer retains Resume/Cancel.

### Failure diagnosis, without weakened accessibility assertions

1. **Typed Space:** timeout waiting for `fixture ` after a focused Search field received a
   synthetic keydown/up. Exact Compose 1.9.3 desktop Foundation source requires a native AWT
   `KEY_TYPED` to recognize printable text; a code point alone is insufficient. The candidate test
   now sends down → native-backed synthetic typed event → up through the scene. It still checks
   the space reaches the actual Search state and does not pause/toggle audio.
2. **Dialog Escape:** the semantic-node injector calls `RootNodeOwner` directly, while Compose's
   default Dialog dismissal listener belongs to its focusable **scene layer**. The candidate
   test now uses `DesktopComposeTestRule.scene.sendKeyEvent`, retaining dialog → queue → player
   dismissal and unchanged audio-owner checks. No extra production Escape handler was added to
   compensate for a bypassed test-driver layer. These are synthetic scene events, **not evidence
   of an actual desktop OS keyboard**.
3. **Short-landscape Library item:** direct `performScrollTo` queried a virtualized item that
   was not yet composed after filtering. The candidate scrolls the LazyColumn to the tagged
   descendant first, then checks display. Original unclipped input/rail/mini-player assertions
   remain, including the runtime-error state.
4. **Arabic selected navigation label:** genuine `TextLayoutResult.hasVisualOverflow` at
   320dp/175% text. Material3 1.9.0 unconditionally gives its item `weight(1f)`; simply supplying a
   different item weight is not a reliable fix. The candidate uses separate Row slots: inactive
   compact icons retain 48dp targets and the selected full label gets the remaining width. It
   neither shrinks nor ellipsizes that label. New coverage selects all four destinations in both
   English and Arabic at 200%, preserving min-target, full-bounds and no-text-overflow assertions.
5. **Incoming crossfade receipt:** the test scrolled to the incoming section heading and then
   required a title below it to be visible without further scrolling. The candidate scrolls to
   the title itself. Requested/resolved distinction, exact supplied incoming receipt, non-first
   result diagnostics and confirmed-only overlap/progress assertions remain intact.

The exact reviewed API source jars are kept in `.work/music-api-review/`: Compose UI/Test/
Foundation 1.9.3 and Material3 desktop 1.9.0. In particular see Foundation's
`TextFieldKeyInput.desktop.kt`, UI Test's `KeyInputHelpers.kt`/`InputDispatcher.skiko.kt`, UI's
`RootNodeOwner.skiko.kt`/`CanvasLayersComposeScene.skiko.kt`/`Dialog.skiko.kt`, and Material3's
`NavigationBar.kt`. These source facts explain the repair; **the repaired tests have not run yet**.

### Rendered evidence and transport-priority correction

UI2 produced seven music PNGs plus four design PNGs. All eleven PNGs and seven sidecars were
preserved under `.work/music-ui-evidence/second/screenshots/`, with `screenshots.sha256`. The
music captures are authored metadata/engine fixtures, not downloaded copyrighted media, real
source, native decoding or physical audible evidence. Lead and independent podcast review
corroborated the full-player issue below.

- `music/core-shell/fixture-shell-en-light-occurrence-queue.png`: current/upcoming occurrence
  grouping and reference-only actions are visible; the mini-player still describes the active
  music recording rather than a viewed queue. The list is captured at its test scroll position.
- `music/core-shell/fixture-shell-en-light-episode-player.png`: actual English light full player
  shows explicit provenance, but the verbose card pushes transport below the 720px viewport;
  pause is visibly cropped. This is a **real usability defect**, not excused by scrollability.
- `music/core-shell/fixture-label-clipping-nav-library.png`: corroborates the narrow Arabic
  selected-label overflow, despite correctly mirrored navigation and sized click targets.
- `music/player-ar-dark-large-top.png` and `player-ar-dark-large-controls.png`: correct dark RTL
  presentation and LTR media controls/time, but top-state artwork/metadata hides transport until
  scrolling. The control capture includes a keyboard-focus tooltip over time text and is not
  evidence of a clean resting layout. Pinned Material3 keeps that tooltip until focus loss, not
  merely until pointer movement; the candidate asserts keyboard behavior before clearing focus
  only for its resting-layout capture.
- `music/track-ar-dark-large-{top,actions}.png`: these standalone fixtures omitted the app's
  hosting Surface, leaving transparent background and default black implicit text. They are
  **defective visual fixtures**, not valid title/contrast review. The real VeyraApp supplies its
  Surface; the candidate capture fixture now does too, and clears mouse hover before capture.

The full-player candidate keeps **seek/pause/skip fixed below the scrollable metadata area**.
Complete existing requested/resolved/source/mismatch disclosure is unchanged and remains readable
by scrolling/expansion. Decorative portrait artwork becomes compact for short/large-text panes.
A new connected fixture resizes long requested/resolved titles and a long creator credit from
960×720 to 720×360 to Arabic/dark/200% at 320×760. These are app-content sizes inside the default
1024×768 synthetic Compose scene, not native OS-window or independent-modal scene resizing.
It requires all essential transport targets to
be fully in-bounds **before scrolling** and while reading the complete expanded receipt. Existing
podcast transport checks also now require initial full visibility. This adds two CoreShell methods
(11 authored, versus 9 in UI2); the next owned total is 76 authored cases, **not 76 passes**.
Capture helpers move the pointer away and allow tooltip dismissal before saving new evidence.
The design owner's runtime indicator was extracted to `app/MediaRuntimeDetails.kt`; the old
private implementation was removed from the shell, keeping the same production call.

### Input/evidence limitations and prior run

The post-run hash check matched **244 of 247** recorded inputs, including all compiled common/
desktop/shared JVM inputs. Two Android-only files changed after the run; `linux-namespace.sh`
changed during it for the CI owner's Stage0 `/run` mask work. Its before SHA was
`7c2f4113f7b994c4237d70146ce312433be7a9ebcd65c99277ec4a58128b37df`, after SHA
`1a00dc42b88f4988f25c492f46e9063f77f118161e60c96a71a778384dab9f3f`.
The wrapper flow consumes namespace setup before executing the guarded display/Gradle child and
would not reread that edited script in this already-created namespace. Nevertheless an exact
pre-edit wrapper copy was not retained; **do not call all 247 inputs unchanged**. See
`inputs-after-check.json`. Current candidate source edits require a new frozen-input run.

The **first** guarded batch is historical: 196 records, 127 passes, 69 failures, no skips
(`.work/music-ui-tests-first.log`, `.work/music-ui-evidence/first/`). Of those failures, 67 were
X11 authentication/AWT initialization failures before GUI bodies ran, and two were native JUnit
class-validation failures. It produced no PNGs; only 35 owned pure/helper navigation methods
passed then. The guard authentication and class-signature fixes allowed UI2 to execute, without
an unguarded display fallback. Main-surface fixture sizes do not validate independently layered
modal sizes; the design owner's true-scene-sized dialog fixtures cover that separate boundary.

Remaining gates include the candidate rerun and fresh visual review, actual keyboard/focus on
platform windows, real compliant central source→play→download→offline restart, native transport,
and mandatory physical-device audible crossfade. None is inferred from these authored fixtures.

## Primary references

- [S1] Spotify — Search: <https://support.spotify.com/us/article/search/>
- [S2] Spotify — Your Library: <https://support.spotify.com/us/article/your-library/>
- [S3] Spotify — Now Playing view: <https://support.spotify.com/us/article/now-playing/>
- [S4] Spotify — Play Queue: <https://support.spotify.com/us/article/play-queue/>
- [S5] Spotify — Shuffle play: <https://support.spotify.com/us/article/shuffle-play/>
- [S6] Spotify — Listen offline: <https://support.spotify.com/us/article/listen-offline/>
- [S7] Spotify — Sort and filter: <https://support.spotify.com/us/article/sort-and-filter/>
- [S8] Spotify — Create and edit playlists: <https://support.spotify.com/us/article/create-playlists/>
- [S9] Spotify — Local files: <https://support.spotify.com/us/article/local-files/>
- [A1] Apple — Search for music on iPhone: <https://support.apple.com/guide/iphone/search-for-music-iph4b506b24d/ios>
- [A2] Apple — Add and download music from Apple Music: <https://support.apple.com/en-us/118288>
- [A3] Apple — Use the music player controls on iPhone: <https://support.apple.com/guide/iphone/use-the-music-player-controls-iph676daac9b/ios>
- [A4] Apple — Add music to your queue on iPhone, iPad, or Android: <https://support.apple.com/en-us/109336>
- [A5] Apple — Shuffle or repeat songs on iPhone: <https://support.apple.com/guide/iphone/shuffle-or-repeat-songs-iphe22408524/ios>
- [A6] Apple — Use Music MiniPlayer on Mac: <https://support.apple.com/guide/music/use-music-miniplayer-mus71d7dcfce/mac>
- [A7] Apple — Queue your songs in Music on Mac: <https://support.apple.com/guide/music/queue-your-songs-musb1e6d1c76/mac>
- [A8] Apple — Keyboard shortcuts in Music on Mac: <https://support.apple.com/guide/music/keyboard-shortcuts-mus1019/mac>
- [Y1] Google — Find music and podcasts in YouTube Music: <https://support.google.com/youtubemusic/answer/13401025?hl=en>
- [Y2] Google — Customize your music and podcasts: <https://support.google.com/youtubemusic/answer/6313542?hl=en>
- [Y3] Google — Shuffle or repeat songs: <https://support.google.com/youtubemusic/answer/9205192?hl=en>
- [Y4] Google — Download music and podcasts to listen offline: <https://support.google.com/youtubemusic/answer/6313535?hl=en>
- [Y5] Google — Make or edit a playlist: <https://support.google.com/youtubemusic/answer/7205933?hl=en>
- [Y6] Google — Upload music to your YouTube Music library: <https://support.google.com/youtubemusic/answer/9716522?hl=en>
