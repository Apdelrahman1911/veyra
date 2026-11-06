# Integration feasibility: live sources, rights, and the first listening proof

Research date: **2026-11-06 UTC**. Scope: Android, iOS, Windows, macOS, Linux; English/Arabic; real source discovery and offline listening. This document separates measured source behavior from implementation plans and platform claims.

## Decision summary

1. **Keep a real kmpYT adapter, but do not promise live YouTube audio on this network.** Public discovery works; the approved video returns a sign-in/bot restriction and no playable formats. That is not evidence of a Veyra parser, network, or player defect. Do not bypass it, manufacture media, or substitute another recording silently.
2. **The earliest independent full-audio fixture is an explicitly CC-licensed public podcast.** Apple Search API → Hacker Public Radio → publisher RSS → selected MP3 is reachable without credentials. Its episode page explicitly licenses the selected content. A separately vetted Commons **music** recording also exposes a real MP3 derivative; its rate limits need handling. Neither path proves YouTube playback.
3. **Test the application player early on every available host.** FFmpeg inspection/decoding, HTTP ranges, downloaded hashes, and a fresh offline helper process are supporting evidence only. They cannot establish actual application playback, native background controls, or five-platform completion.
4. **Source rights and software rights are separate gates.** kmpYT has no project license at the pinned revision according to the source audit; public GitHub visibility is not a redistribution license. Public podcast access also does not imply unrestricted redistribution. Preserve attribution and distinguish personal-use feeds from CC fixtures.

## Evidence: public network probes

All probes ran from this VPS with Python standard-library HTTPS requests, no login, cookies, developer keys, proxy workarounds, or invented YouTube client profiles. YouTube media was not requested. Responses were parsed in memory; stored reports retain public metadata/statuses, not watch HTML, signed media URLs, session headers, or credentials.

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

Real adapter flow from the audited API:

```kotlin
val results = youtube.search("oPiiLz")
val channel = youtube.channel("@oPiiLz")
val report = youtube.inspect(selected.url)
// In an authorized live test, verify uploaderChannelId against channel.channelId
// before requesting any media bytes. Restriction reports stop the flow here.
val selection = youtube.select(report, audioOnly = true)
// An absent selection is unavailable/unsupported, never a fabricated URL.
// Mandatory: verify the exact selected MIME/container/codec against the actual
// native player. audioOnly selection checks downloadability, NOT decoder support.
val request = youtube.playbackPlan(report, requireNotNull(selection))
// Pass request to the actual platform player. playbackPlan() is not a player.
val transfer = youtube.plan(report, selection)
youtube.download(transfer, destinationPath)
```

Use the current inspected report's selection IDs, preserve required headers/transport, and distinguish unsupported transport from authorization/network failure. Do not log or persist signed source URLs. Preserve stable video identity and re-inspect only with an explicit expiry/retry policy. See `kmpyt-audit.md` for exact capability constraints and software-license findings.

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

Preserve creator/title/source/license attribution wherever a fixture is distributed. Do not commit downloaded media or scraped publisher HTML to the repository. Tests should opt into downloading to private ignored workspace storage. The current RSS exposes only recent episodes, so a durable live test must either choose a newly eligible small CC episode and record its real GUID or explicitly resolve this known archive item; it must not pretend an archived episode was returned by a search/feed when it was not.

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

Thus this is a **genuine compatible music candidate**, not yet proof of complete streaming, seek, download, or app playback. Respect rate limits/Retry-After, avoid repeated metadata/HEAD/range opens on one user action, and expose `RateLimited` honestly. A later deliberate native-player test is separate evidence. Keep “Open music · Wikimedia Commons” visibly distinct from YouTube; never silently replace a selected YouTube song with this recording.

For an initial implementation, prefer a small explicitly vetted catalog/allowlist of recordings and preserve per-file creator, source, license URL, and the declaration inspected. Generic Commons search results are not automatically vetted music. The flattened `extmetadata` for this file says only “Public domain” and omits the crucial composition/performance distinction; it cannot be treated as a complete rights parser. Commons' reuse policy warns that users must verify each file's licensing: <https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia>. CC0 primary terms: <https://creativecommons.org/publicdomain/zero/1.0/>. Still show provenance/creator even when attribution is not a condition.

An Internet Archive no-key search also returned HTTP 200 and 259 results for `creator:"Kevin MacLeod" AND mediatype:audio AND licenseurl:*`. However, top uploader-supplied results asserted divergent licenses, including public-domain/CC0 claims. That metadata alone is **not reliable evidence of the performer's rights grant**. Do not whitelist every `licenseurl` match or adopt those recordings without verifying authoritative provenance and both composition/recording rights. This finding favors the specifically reviewed Commons recording for the first music proof.

## First end-to-end smoke plan

The gate is a **user-observable application path**, not just a collection of unit tests:

1. Start a fresh app with a workspace-scoped data directory. Search the real directory; select the specific show by visible title/publisher; open its live RSS episodes.
2. Select an eligible licensed MP3, retain feed/GUID identity and source attribution, resolve the real enclosure and platform-compatible source. Display an honest source error on failure.
3. Start the actual native/desktop player. Observe `Ready → Playing`, position advancing for at least 2 seconds, nonzero duration where known, and no placeholder/test-signal substitution. Check audible output on a host with an audio device; mute/sink decoding is explicitly weaker evidence.
4. Pause/resume. Seek to a nontrivial position (e.g. 60 seconds) and observe position converging within a documented tolerance, then advancing. A successful range request alone is not a playback seek.
5. Download that selected episode with visible real progress and cancellation. Write `.part`, validate the actual transfer/file, atomically rename, and persist completion plus stable identity. No partial resource may appear as playable offline completion.
6. Save position, stop the app/player, and **terminate the process**. Disable network for the second run or use an explicit denying transport and independently inspect socket activity, including the native player's own sockets: denying Ktor alone does not stop AVPlayer/Media3/desktop networking. Assert the player receives the intended local artifact URI/path. Restart with the same persisted data. Open Library/Downloads, choose the completed item and prove local playback plus seek/position restoration. No transient stream URL may be required.
7. Repeat negative cases: authorization required, missing file, cancellation mid-transfer, stale RSS length, no validator, changed ETag/resource, disallowed codec/transport, network failure, malformed feed. Expose a reason; do not silently substitute a different recording or delete unrelated state.

Supporting ffprobe/FFmpeg and an offline fresh helper process can establish format/bytes but must be reported separately from this app gate. Android/iOS background service/audio-session/lockscreen tests and each desktop OS remain platform-specific verification work.

### Minimal implementation contracts needed before UI breadth

- Domain: source identity, media kind, title/publisher, language, duration, rights/attribution, playable source with headers and explicit transport/codec capabilities, and typed unavailable reason.
- Podcasts: bounded XML parsing with entities disabled, direct-RSS entry point, GUID-based identity/deduplication, namespace-aware duration/artwork/description handling, and escaped/sanitized rendered HTML.
- Networking: bounded redirects/timeouts/body sizes; HTTPS by default; no global TLS/security weakening. User-provided feeds and enclosures must not become an unrestricted private-network/file proxy. Recheck each redirect and keep credentials out of cross-host requests/logs.
- Storage: atomic completed download, genuine response-byte accounting, strong version checks for resume, local file existence/integrity reconciliation, and schema-backed queue/position/download state. RSS metadata is not a transfer checkpoint.
- Identity versus generation: stable episode identity is canonical feed identity + GUID, with explicit aliases for migrated feeds. A resource generation is separate: the same GUID, CDN URL, length, or ETag on a different host does not prove identical audio. Test replaced enclosures, rotating mirrors, changed bytes, reused cross-host ETags, and stale feed metadata before enabling resume.
- Offline: local resolver has priority for completed items; ordinary UI, persistence, and player start do not secretly require a directory lookup or source refresh.
- Availability taxonomy: `NetworkUnavailable`, `SourceAccessRequired`, `SourceRemoved`, `RateLimited`, `UnsupportedCodecOrTransport`, `CorruptOrMissingLocalFile`. A “zero results” success must not mask an access/network error.

## Cross-review outcomes

kmpYT reviewer confirmed the independent podcast path and challenged three enforceable gates: separate resource generation from episode GUID; turn safe-fetch prose into executable hostile-XML/redirect/DNS/private-origin tests; and deny/observe native-player networking, not only the shared HTTP client. Accepted above. DNS rebinding, IPv4/IPv6 loopback/private/link-local resolution, credential-bearing URLs, and each redirected target need explicit checks; a URL string-prefix allowlist is not sufficient.

Integrator's kmpYT listening-proof challenges: (1) downloaded-format selection is not native codec selection; (2) a 401/403 must not automatically trigger endless signed-URL refresh or an identity bypass; (3) JVM/iOS successful transfer must not mark `OfflineReady` without testing the saved artifact through the real player. The upstream audit already flags each limitation; implementation tests must enforce them.

Mobile cross-review (`mobile-audio.md`): (1) native session defaults must enter the same ordered coordinator, not create a second queue; late native callbacks and duplicate media IDs need generation/entry tests; (2) test offered codec/container plus real ready/advancing/seek on the target, not declared support, and do not pass SABR as a URL or rely on undocumented iOS headers; (3) completed Media3 cache handles, iOS packages, and raw MP3 files have different restoration/validation paths. True app termination plus native local-only playback is required. Reviewer agrees; Android hosts/devices and macOS/Xcode remain external test-environment dependencies.

Desktop cross-review (`desktop-runtime.md`, in preparation): (1) ctypes/libmpv null-output decoding is useful native-engine evidence, not audible or Compose-app listening; the real package needs usable audio output and honest device errors; (2) seek success is observed position convergence followed by advancement, not an accepted command, and offline denial must include libmpv sockets; (3) prove actual bundled ABI/native dependencies and runtime licensing on each clean OS/architecture, not the presence of host-installed FFmpeg/mpv. The desktop experiment also found a truncated WebM accepted as ordinary EOF: successful native preparation cannot replace transfer completeness checks. The HPR source was passed to the desktop researcher for a separately labelled real native-engine attempt; no result is assumed here.

## Primary references

- kmpYT pinned source: <https://github.com/Apdelrahman1911/kmpYT/tree/533562f781304338f72b27d32cfe5fb7a83dc3a8> (README, `AGENTS.md`, `ci/approved-channel.json`, `YouTubeClient.kt`, `PublicYouTubeBrowseService.kt`).
- Apple Search API: <https://performance-partners.apple.com/search-api>.
- Apple publisher RSS requirements: <https://podcasters.apple.com/support/823-podcast-requirements> (GUID, enclosure URL/length/type, public RSS, media HEAD/range).
- HPR publisher licensing and specific episode links above.

These findings establish a viable real podcast path and a real YouTube discovery path with an upstream playback restriction. They do **not** establish completed Veyra playback, authorized unrestricted YouTube downloading, a production distribution license for kmpYT, or successful builds/tests on all five platforms.

Model disclosure: this workstream was requested as `gpt-6-astra` / `max`; no independent effective-runtime model/reasoning attestation is exposed to this subagent. No stronger verification is claimed.
