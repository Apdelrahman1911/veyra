# Integration feasibility: live sources, rights, and the first listening proof

Research date: **2026-11-06 UTC**. Scope: Android, iOS, Windows, macOS, Linux; English/Arabic; real source discovery and offline listening. This document separates measured source behavior from implementation plans and platform claims.

**Authoritative update after the initial probes:** [D005/D006/D008/D009](../DECISIONS.md) supersede the earlier independent-feed media proposal. RSS/Apple/Commons are **discovery metadata only**. Every fresh media selection, including YouTube-discovered items, submits its complete accurate title to kmpYT and binds the **first raw result**; collections/unavailable/unknown cards must not be skipped for a later video. The complete-title bound is 16,384 UTF-16 code units: reject overflow, never truncate. Only kmpYT acquires media. Historical direct-enclosure/Commons probes below are not the current app listening gate and must not be repeated as a fallback. Original-source rights do not establish rights to the selected YouTube recording. Room persistence and physical-device music crossfade verification remain mandatory.

## Decision summary

1. **Keep a real kmpYT adapter, but do not promise live YouTube audio on this network.** Public discovery works; the approved video returns a sign-in/bot restriction and no playable formats. That is not evidence of a Veyra parser, network, or player defect. Do not bypass it, manufacture media, or substitute another recording silently.
2. **Genuine metadata discovery works independently of media authorization.** Apple Search API and real English/Arabic feeds are reachable. Earlier licensed HPR/Commons direct-media experiments remain supporting historical evidence only; fresh app selections must execute their full-title first kmpYT result, without an independent enclosure/Commons media path or fallback.
3. **Test the application player early on every available host.** FFmpeg inspection/decoding, HTTP ranges, downloaded hashes, and a fresh offline helper process are supporting evidence only. They cannot establish actual application playback, native background controls, or five-platform completion.
4. **Source rights and software rights are separate gates.** MIT adoption is owner-authorized and the lead's baseline licensing review is complete; the previous permission/audit blocker is resolved. Safe upstream publication, final reproducible pin, and per-binary notices/source compliance are separate delivery work. Public podcast access still does not imply unrestricted redistribution. See [licensing verification](../verification/licensing.md).

## Evidence: public network probes

These **historical, pre-D005** probes ran from this VPS with Python standard-library HTTPS requests, no login, cookies, developer keys, proxy workarounds, or invented YouTube client profiles. YouTube media was not requested. Responses were parsed in memory; stored reports retain public metadata/statuses, not watch HTML, signed media URLs, session headers, or credentials.

Untracked supporting artifacts under `.work/feasibility/`:

- `initial-public-probes.json`: directory and YouTube discovery observations.
- `feed-watch-probes.json`: selected watch restriction and English/Arabic feed metadata (enclosure queries redacted).
- `media-head-range-probes.json`: bounded media probes and episode licensing text. The initial Python HEAD requests changed to GET while following redirects; that field is explicitly labelled and **is not a preserved-HEAD result**.
- `preserved-head-probes.json`: corrected HEAD requests with method preserved across HTTPS redirects.
- `music-license-probes.json` and `commons-music-search-media.json`: no-key open-music search, explicit source/license inspection, and bounded source probes.

### YouTube: discovery is available; source access is not

kmpYT revision: `533562f781304338f72b27d32cfe5fb7a83dc3a8` (v0.1.0). Its `AGENTS.md` and `ci/approved-channel.json` restrict live testing to `@oPiiLz`; only that approved channel was used. The configured authorization does not authorize circumventing YouTube access controls.

| Time (UTC) | Request | Measured outcome |
|---|---|---|
| 05:05:08 | GET `https://www.youtube.com/results?search_query=oPiiLz` | HTTP 200; readable `ytInitialData`; search returned the approved channel plus video metadata. |
| 05:05:10 | GET `https://www.youtube.com/@oPiiLz/videos` | HTTP 200; channel ID `UCHVDgaiLbpj5Leazw8vsAmg`. |
| 05:05:49 | GET `https://www.youtube.com/watch?v=J_UH5zb-kf4` | HTTP 200; `ytInitialPlayerResponse.playabilityStatus.status=LOGIN_REQUIRED`; reason “Sign in to confirm you’re not a bot”; zero progressive/adaptive formats and no HLS/DASH. |

The minimal independent Python channel parser only counted legacy renderers. Its zero count on the channel page is **not** a kmpYT defect; the library supports newer lockup renderers. Actual-library search/inspection must be verified in a JVM integration test once its build is available. kmpYT's README independently reports `AUTHORIZATION_REQUIRED` on its latest published live attempt and distinguishes it from an Android emulator DNS failure.

**Stop condition:** when inspection returns authorization required, no compatible source exists to play/download. Present an actionable source-specific message and an explicit “Open in YouTube” action. Do not retry with spoofed clients, scraped accounts, cookies, unauthorized relays, or invented formats. A successful offline fixture must not turn this restriction into a reported live YouTube success.

The current production path is `AppContainer → PlaybackController → AppSourceResolver → YouTubeKmpMediaGateway → kmpYT`. The resolver persists the full-query/raw-first receipt **before** execution or a known first-entry failure. It never resolves the original publisher enclosure or chooses the first surviving video in a filtered display page. An approved live run must additionally verify the selected video's uploader against the authorized channel before requesting media bytes; if the raw first result is outside the live-test authorization, stop rather than choose another result.

The selected report/representation must remain bound to that receipt. Distinguish unsupported transport from authorization/network failure. Download previews describe central acquisition transport while advertising the materialized output MIME/codec; native local-file compatibility is checked as progressive output, not native SABR support. A central-owned streaming bridge is remote even when its exact typed endpoint is IPv4 loopback; no arbitrary local URL becomes trusted or offline-ready. Do not log/persist signed URLs. See `kmpyt-audit.md` for current capability and source restrictions.

### No-key podcast discovery and genuine Arabic content

Apple Search API is a practical no-key directory, not a media host. Its documentation lists podcast search; approximately 20 calls/minute is guidance, not an entitlement. Debounce, cancel obsolete queries, cache reasonable directory responses, and support direct RSS import independently.

Observed requests used `media=podcast&entity=podcast&country=US|SA&limit=3` and a UTF-8 encoded query:

| Query | Selected result | RSS | Feed observation |
|---|---|---|---|
| `Planet Money`, US | ID `290783428`, NPR | `https://feeds.npr.org/510289/podcast.xml` | HTTP 200; 355 items; language `en`; copyright explicitly “For Personal Use Only”. |
| `فنجان`, SA | ID `985515827`, `فنجان مع عبدالرحمن أبومالح`, ثمانية | `https://files.hosting.thmanyah.com/podcasts/89/1713955813943-768/rss-feed.rss` | HTTP 200; 364 items; language `ar`; publisher copyright. |
| `Hacker Public Radio`, US | ID `281699640`, Hacker Public Radio | `https://hackerpublicradio.org/hpr_rss.php` | HTTP 200; 10 recent items; language `en-us`. |

The Arabic query's first two hits were other similarly named podcasts. Never auto-select the first result as the intended show. Display title, publisher and feed host. Planet Money appeared under two Apple IDs with the same feed: canonical feed identity and episode GUID matter more than directory IDs.

English episode probe: `The history of light (classic)`, GUID `d7ae601b-8465-47f2-835c-21fba48f631d`, duration 1,266 seconds. Arabic episode probe: `لماذا تخلّى علماء الإسلام عن عروبتهم`, GUID `37c66119-fcef-4eba-8000-91ecae9ec5a6`, duration `02:13:05`. Both source-declared MP3 enclosures returned **HTTP 206** for `Range: bytes=0-4095`; these were bounded 4 KiB probes, not complete downloads or listening tests. Normal publisher redirects were honored; no trackers or restrictions were bypassed.

Corrected HEAD probes (05:08 UTC), preserving HEAD through redirects: HPR **HTTP 200** with 3,284,906-byte length and a strong ETag on the selected mirror; NPR **HTTP 200** with 20,264,410-byte length and no ETag; Finjan **HTTP 403**, despite the earlier ordinary range GET succeeding. **HEAD must not be a prerequisite for playing/downloading a source that supports a bounded GET**. The initial HEAD-to-GET probing mistake was corrected rather than counted as proof.

**Important observed metadata mismatch:**

| Feed | RSS enclosure length | Ranged-response total |
|---|---:|---:|
| Planet Money | 20,264,002 | 20,264,410 |
| Finjan | 191,262,162 | 127,773,758 |

RSS lengths are estimates and can be stale, including dynamically inserted advertising or replacement audio. Download completion must use the selected HTTP response/version, actual bytes and validated file, not blind equality with feed metadata. Do not show an authoritative size/progress percentage until there is a reliable transfer total.

### Explicitly licensed real podcast fixture

Selected from the live HPR directory result and RSS:

- Title: **HPR4764: EV_Update_20260306**.
- Creator: **Ahuka**, via Hacker Public Radio.
- Publisher episode page: <https://hackerpublicradio.org/eps/hpr4764/index.html>.
- License: **Creative Commons Attribution-ShareAlike 4.0 International**, explicitly linked by that episode page: <https://creativecommons.org/licenses/by-sa/4.0/>.
- Public MP3 enclosure/GUID: `https://hub.hackerpublicradio.org/ccdn.php?filename=/eps/hpr4764/hpr4764.mp3`.
- Declared and ranged-response length: **3,284,906 bytes**; duration listed by publisher **5:29**.
- Bounded media probe: HTTP 206, `audio/mpeg`, `Content-Range: bytes 0-4095/3284906`.
- HPR's general licensing explanation: <https://hackerpublicradio.org/about.html>; specific episode license takes precedence over general defaults.

Preserve creator/title/source/license attribution wherever a fixture is distributed. Do not commit downloaded media or scraped publisher HTML. Historical source bytes remain in ignored workspace storage; this section does not authorize new direct-media requests. The observed RSS exposed only recent episodes, so do not pretend an archived item was returned by a fresh search/feed. Current live tests use genuine discovered metadata and the central full-title/raw-first policy, with separate authorization/rights checks for the selected recording.

The enclosure redirects among several real mirrors; validators differ. During the probe one mirror supplied ETag/Last-Modified and another did not. A rotating CDN URL is not stable episode identity and is not sufficient evidence for safe ranged resume. Validate the same source version/strong validator; otherwise restart safely, without falsely promoting partial files to complete.

### Supplemental open music: a vetted Commons recording, not a YouTube fallback

The lead explicitly requested investigation of a genuinely licensed supplemental music source. Wikimedia Commons provides a no-key search/metadata path. An actual search at 05:13 UTC returned five files:

```text
GET https://commons.wikimedia.org/w/api.php
  ?action=query&list=search&srsearch=Beethoven%2032%20Variations%20filetype%3Aaudio
  &srnamespace=6&srlimit=5&format=json
```

Selected result: page ID **13393751**, `File:Beethoven - 32 Variations in C Minor, WoO 80.ogg`. The file page is <https://commons.wikimedia.org/wiki/File:Beethoven_-_32_Variations_in_C_Minor,_WoO_80.ogg>. Independently inspected wikitext explicitly states:

- Source: `{{Own}}`; composer Ludwig van Beethoven; performer **La Pianista**, recording date 2010-11-27.
- **Composition**: `{{PD-old-100}}`.
- **Performance**: `{{self|Cc-zero}}` — a separate CC0 dedication, not merely an assumption that an old composition makes a modern recording free.

The live API `prop=revisions|videoinfo`, `rvprop=content`, `rvslots=main`, `viprop=url|size|mime|derivatives` returned a **source-declared `audio/mpeg` derivative**. Do not construct transcode URLs from a naming convention:

```text
https://upload.wikimedia.org/wikipedia/commons/transcoded/b/b2/Beethoven_-_32_Variations_in_C_Minor%2C_WoO_80.ogg/Beethoven_-_32_Variations_in_C_Minor%2C_WoO_80.ogg.mp3
```

| Probe | Measured result |
|---|---|
| Metadata | HTTP 200; duration 728.367 seconds; original is Ogg, but MP3 derivative is explicitly offered. |
| MP3 HEAD | HTTP 200; `audio/mpeg`; 17,797,506 bytes; ranges and ETag advertised. |
| `Range: bytes=0-4095` | HTTP 206; 4,096 bytes; `Content-Range: bytes 0-4095/17797506`. |
| Immediate `Range: bytes=1048576-1052671` | **HTTP 429 Too Many Requests**. Probe stopped; no automatic alternate URL/client/proxy attempt. |

This was a genuine historical direct-media candidate, **not an authorized current app acquisition path** and not proof of app listening. Respect rate limits/Retry-After and expose `RateLimited` honestly. Commons remains visibly attributed discovery metadata; display its requested identity separately from the bound YouTube recording, and never transfer the Commons rights claim to that other recording.

For discovery, preserve per-file creator, source, license URL, and the declaration inspected. Generic Commons results are not a rights audit. The flattened `extmetadata` for this file says only “Public domain” and omits the crucial composition/performance distinction; it cannot be treated as a complete rights parser. Commons' reuse policy warns that users must verify each file's licensing: <https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia>. CC0 primary terms: <https://creativecommons.org/publicdomain/zero/1.0/>. Still show provenance/creator even when attribution is not a condition.

An Internet Archive no-key search also returned HTTP 200 and 259 results for `creator:"Kevin MacLeod" AND mediatype:audio AND licenseurl:*`. However, top uploader-supplied results asserted divergent licenses, including public-domain/CC0 claims. That metadata alone is **not reliable evidence of the performer's rights grant**. Do not whitelist every `licenseurl` match or adopt those recordings without authoritative composition/recording provenance. This historical rights comparison does not authorize an alternate first-listening proof.

## First end-to-end smoke plan

The gate is a **user-observable application path**, not just a collection of unit tests:

1. Start a fresh app with a workspace-scoped data directory. Search the real directory; select the specific show by visible title/publisher; open its live RSS episodes.
2. Retain full discovery metadata and feed/GUID identity. Submit the complete title through the production kmpYT gateway and persist its first raw result. Do not skip a nonplayable/unauthorized/unavailable first result; stop with a truthful classified outcome. Inspect and execute only that selected identity, within live-test authorization, and verify native compatibility. Never probe/play/download an RSS enclosure or Commons audio as fallback.
3. Start the actual native/desktop player. Observe `Ready → Playing`, position advancing for at least 2 seconds, nonzero duration where known, and no placeholder/test-signal substitution. Check audible output on a host with an audio device; mute/sink decoding is explicitly weaker evidence.
4. Pause/resume. Seek to a nontrivial position (e.g. 60 seconds) and observe position converging within a documented tolerance, then advancing. A successful range request alone is not a playback seek.
5. Download via the production persistent coordinator and **kmpYT transfer only**, reusing the explicit playback receipt for Play → Download. Exercise visible progress, pause/resume/cancel/retry and bounded parallelism. Validate actual bytes, file/hash and native decoder, atomically publish with durable finalization intent, and commit normalized Room records. No partial resource may appear as offline-ready.
6. Save position, stop the app/player, and **terminate the process**. Disable network for the second run or use an explicit denying transport and independently inspect socket activity, including the native player's own sockets: denying Ktor alone does not stop AVPlayer/Media3/desktop networking. Assert the player receives the intended local artifact URI/path. Restart with the same persisted data. Open Library/Downloads, choose the completed item and prove local playback plus seek/position restoration. No transient stream URL may be required.
7. Repeat negative cases: non-video/unavailable first result, authorization required, missing/corrupt file, cancellation at handoff and mid-transfer, offline toggles, changed selected resource/validator, disallowed codec/transport, network failure, malformed feed, and cleanup/reopen races. Publisher duration/length/rights are metadata, not proof about selected YouTube bytes. Expose a reason; do not silently substitute a recording or delete unrelated state.

Supporting ffprobe/FFmpeg and an offline fresh helper process can establish format/bytes but must be reported separately from this app gate. Android/iOS background service/audio-session/lockscreen tests and each desktop OS remain platform-specific verification work.

### Minimal implementation contracts needed before UI breadth

- Domain: source identity, media kind, title/publisher, language, duration, rights/attribution, playable source with headers and explicit transport/codec capabilities, and typed unavailable reason.
- Podcasts: bounded XML parsing with entities disabled, direct-RSS entry point, GUID-based identity/deduplication, namespace-aware duration/artwork/description handling, and escaped/sanitized rendered HTML.
- Networking: bounded redirects/timeouts/body sizes; HTTPS by default; no global TLS/security weakening. User-provided metadata/image URLs must not become an unrestricted private-network/file proxy; enclosures are not execution inputs. Recheck redirects and keep credentials out of requests/logs. Native/public-DNS preflight is **not atomic DNS pinning** and does not establish rebinding immunity.
- Storage: normalized Room, atomic completed download, genuine response-byte accounting, strong scoped version checks for kmpYT resume, and local file/hash/native reconciliation. RSS metadata is not a transfer checkpoint. Owned image-only artwork is stored separately; no audio downloader is hidden inside it.
- Identity versus generation: stable episode identity is canonical feed identity + GUID, with explicit aliases for migrated feeds. A resource generation is separate: the same GUID, CDN URL, length, or ETag on a different host does not prove identical audio. Test replaced enclosures, rotating mirrors, changed bytes, reused cross-host ETags, and stale feed metadata before enabling resume.
- Offline: local resolver has priority for completed items; ordinary UI, persistence, and player start do not secretly require a directory lookup or source refresh.
- Availability taxonomy: `NetworkUnavailable`, `SourceAccessRequired`, `SourceRemoved`, `RateLimited`, `UnsupportedCodecOrTransport`, `CorruptOrMissingLocalFile`. A “zero results” success must not mask an access/network error.

## Cross-review outcomes

The initial kmpYT cross-review predated D005; its independent-podcast playback recommendation is superseded. Its enforceable challenges remain: separate resource generation from episode GUID; execute hostile-XML/redirect/DNS/private-origin tests; and deny/observe native-player networking, not only shared HTTP. A URL prefix allowlist is insufficient. Public-DNS preflight reduces unsafe-origin exposure but is not atomic DNS pinning.

Integrator's kmpYT listening-proof challenges: (1) downloaded-format selection is not native codec selection; (2) a 401/403 must not automatically trigger endless signed-URL refresh or an identity bypass; (3) JVM/iOS successful transfer must not mark `OfflineReady` without testing the saved artifact through the real player. The upstream audit already flags each limitation; implementation tests must enforce them.

Mobile cross-review (`mobile-audio.md`): (1) native session defaults must enter the same ordered coordinator, not create a second queue; late native callbacks and duplicate media IDs need generation/entry tests; (2) test offered codec/container plus real ready/advancing/seek on the target, not declared support, and do not pass SABR as a URL or rely on undocumented iOS headers; (3) completed Media3 cache handles, iOS packages, and raw MP3 files have different restoration/validation paths. True app termination plus native local-only playback is required. Reviewer agrees; Android hosts/devices and macOS/Xcode remain external test-environment dependencies.

Desktop cross-review (`desktop-runtime.md`, in preparation): (1) ctypes/libmpv null-output decoding is useful native-engine evidence, not audible or Compose-app listening; the real package needs usable audio output and honest device errors; (2) seek success is observed position convergence followed by advancement, not an accepted command, and offline denial must include libmpv sockets; (3) prove actual bundled ABI/native dependencies and runtime licensing on each clean OS/architecture, not the presence of host-installed FFmpeg/mpv. The desktop experiment also found a truncated WebM accepted as ordinary EOF: successful native preparation cannot replace transfer completeness checks. The HPR source was passed to the desktop researcher for a separately labelled real native-engine attempt; no result is assumed here.

## Implementation checkpoint: exact executed scope

The first shared run's actual JUnit XML at 06:11 UTC independently shows **68/68 passing integration-owned tests**: Commons5, resolver-common21, resolver-Room9, public-URL4, SafeHttp6, podcast-source9, RSS-parser14. Room/file tests use actual SQLite, owned files and SHA, but fake native validation and authored **NON-AUDIO** bytes. Same-process close/reopen is not process-death or native playback evidence. The whole shared run was 91 tests/90 passed/one separately owned failure; this is not an all-green full suite claim.

The first desktop compilation log (`.work/desktop-first-compile.log`) shows shared Room KSP, production Kotlin and JVM jar succeeding for its input snapshot, then the app compilation failing at two Compose receiver references and one desktop validator smart cast. The later shared runs compiled the newest source/artwork/resolver production code and its test code. They do not establish that the desktop fixes or AppContainer tests compile.

The third shared run's actual XML at **07:16 UTC independently verifies 117/117 integration-owned tests passing, with zero errors/skips**: Commons5, resolver-common31, resolver-Room12, public-URL4, SafeHttp8, podcast-source9, RSS-parser14, artwork-common8 and artwork-Room26. Source hashes still match the frozen pre-run inventory. The **whole run is not green**: 247 JUnit records include six failures outside these suites, including a Room test-class initialization failure. Two gateway failures are watch-only fixture assertions: the authored watch response omitted uploader/thumbnail, so the real upstream inspector requested public oEmbed metadata for the **same canonical bound video**, not another search or selected recording. The adapter owner corrected exact stage/URL fixture checks without changing production binding; rerun is pending.

**13 AppContainer integration tests are authored but still uncompiled/unrun.** They cover full-title provenance, Play → Download receipt capture across later plays, offline cancellation, slow-delete UI responsiveness and native file-release barriers, settings/artwork restoration and single teardown. App-level media/native success is explicitly synthetic; the first-card case runs the real kmpYT parser on authored HTML only. No case is a substitute for current central real-source listening, terminated-process native-egress denial, or physical audible crossfade.

Independent read-only review found no confirmed blocker in the current SABR resource wrapper/authorization or DOWNLOAD materialized-output contract. Valid resolved HLS/DASH plans have nonempty segments and the central native processors output MP4; whole-resource DASH entries become progressive. DOWNLOAD preview retains acquisition transport while native compatibility is checked against the progressive materialized file. The source authorization callback does not itself observe Room offline state: post-handoff enforcement depends on controller/native resource closure and still needs actual app/native verification. Existing upstream XML separately shows **103/103 scoped JVM resource/manifest/transfer tests passing**, not the full upstream test corpus or a Veyra playback proof.

The audit inventory and hashes are `.work/feasibility/implementation-verification-checkpoint.json`; copied owned XML is under `.work/feasibility/shared-third-owned-results/`, and the independent upstream observation is `.work/feasibility/upstream-jvm-resource-test-observation.json`. The artwork cache validates size/signature/header/dimensions/hash, not complete native image decoding. Public-DNS checks are preflight, not atomic DNS pinning. No new live requests or Gradle builds were started by this audit to produce the checkpoint.

## Primary references

- kmpYT pinned source: <https://github.com/Apdelrahman1911/kmpYT/tree/533562f781304338f72b27d32cfe5fb7a83dc3a8> (README, `AGENTS.md`, `ci/approved-channel.json`, `YouTubeClient.kt`, `PublicYouTubeBrowseService.kt`).
- Apple Search API: <https://performance-partners.apple.com/search-api>.
- Apple publisher RSS requirements: <https://podcasters.apple.com/support/823-podcast-requirements> (GUID, enclosure URL/length/type, public RSS, media HEAD/range).
- HPR publisher licensing and specific episode links above.

These findings establish real podcast metadata discovery and YouTube discovery with an upstream playback restriction. They do **not** establish completed compliant Veyra listening, unrestricted YouTube acquisition, terminated-process native offline proof, physical-device crossfade, or successful builds/tests on all five platforms. MIT authorization/baseline review is separately settled; final release closure remains with the lead.

Model disclosure: this workstream was requested as `gpt-6-astra` / `max`; no independent effective-runtime model/reasoning attestation is exposed to this subagent. No stronger verification is claimed.
