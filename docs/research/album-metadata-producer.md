# D011 proposal — MusicBrainz release metadata

**2026-11-06; finite research recommendation, not implemented.** Prefer MusicBrainz WS2
**release** records as Veyra's required genuine album/release discovery producer. A release
is a particular issuing/edition; a release group is only a grouping, never a substitute edition.
Root owns acceptance and implementation allocation. This work used official documentation and
one explicitly authorized, bounded, public metadata lookup. No catalogue search, kmpYT/source
search, media/artwork request, player, build or test was run.

## Comparison and licensing boundary

| Concern | MusicBrainz release — recommended | Apple iTunes album — concrete free alternative |
|---|---|---|
| Real release identity | Stable release MBID; source-declared date/countries/labels/catalogue number/barcode/media/tracks. Standard/deluxe and regional issuings remain separate releases. Release-group primary/secondary types classify the grouping without replacing the selected release. [1][2] | `entity=album`, collection ID and `collectionName`; lookup can return songs with disc/track positions. Store country is explicit, defaults to US, and may change availability; identity/observation must retain storefront. [7] |
| Data reuse | Core release, medium/track, recording, artist-credit and label fields are CC0. Supplementary annotations, tags/genre associations, ratings, statistics, search indexes and edit history are **CC-BY-NC-SA3.0**, not CC0. Persist a core-field whitelist, not raw response/index/score/supplementary payloads. Artwork has a separate source/rights boundary. [3] | Official documentation permits specified promotional artwork/previews subject to promotion/link/usage restrictions; it does not give the selected MusicBrainz-style blanket CC0 grant. Metadata-only usage is not asserted unlawful, but open reuse/edition provenance is less clear for this non-store playback application. No preview/artwork fallback. [7] |
| Free access | Public reads need no key/account. Hosted API use is free **for noncommercial use**; commercial use is separately offered. CC0 data rights do not promise free hosted service to commercial downstream distributors. No commercial plan, replication subscription or paid resource is proposed. [4] | Public search/lookup URLs are documented without an API-key parameter. Approximately20calls/minute, subject to change. No commercial feed or affiliate account is proposed. [7] |
| Fair use | At most1call/second per application; IP/application/global throttles can return503. Meaningful versioned User-Agent with maintainer URL/email required. Documentation discourages polling for metadata changes. [4][5] | Approximately20calls/minute and caching guidance; search limits1–200. Documentation language parameter lists English/Japanese, not an Arabic localization guarantee. [7] |
| Privacy | MetaBrainz explicitly logs IP, User-Agent, endpoint and request parameters. No account is necessary to browse. Send no listening history, identifiers, cookies or credentials. [6] | Album searches/lookups necessarily disclose query/ID and network metadata to Apple. We did not inspect or claim a no-logging policy. No Apple API sample was made. |

Veyra's proposed use is its free, noncommercial metadata-discovery path. Do not describe hosted
MusicBrainz service as unconditionally free for every future commercial deployment. Neither
metadata licence nor a release's `Official` status grants rights to an audio recording selected
later by kmpYT. MusicBrainz coverage/correctness is community-supplied, not guaranteed.

## Exact producer/query contract

Use HTTPS public GETs to `musicbrainz.org/ws/2`, JSON only, no authentication or user resources.
Do not reuse the generic playable-source `resolve` operation for this metadata adapter.

- **Search specification (not live-tested here):**
  `GET /ws/2/release?query=<URL-encoded literal release query>&fmt=json&limit=25&offset=N`.
  Build `release:"<Lucene-escaped user text>"`; escape Lucene syntax separately from URL encoding,
  never execute arbitrary query operators from plain input. An explicit Albums filter adds
  `AND primarytype:album`; a visible Official filter may add `AND status:official`. These are
  catalogue filters, not filters over kmpYT's raw first execution result. Do not silently substitute
  an edition, translated pseudo-release or release-group hit. Page in response order, preserve
  offset/count distinctions, and never call an incomplete result set the whole catalogue. [4][8]
- **Selected-release detail:**
  `GET /ws/2/release/{releaseMBID}?inc=artist-credits%2Blabels%2Brecordings%2Brelease-groups&fmt=json`.
  Require a valid UUID and returned release ID agreement. Redirect/merge handling must preserve
  requested and observed IDs explicitly, not silently retarget an existing saved request.
- Capture immutable core metadata and an app-computed SHA256 revision over canonical selected
  core fields; retain fetch time and optional response ETag separately. An ETag/local digest is
  an HTTP/cache/content observation, not a MusicBrainz editing revision or cross-request snapshot.
- Keep the complete supplied `release.title` as the parent acquisition query, including spaces,
  Unicode and punctuation. No artist, date, country, edition comment, translation or group-title
  substitution. Reject invalid UTF-16 or more than16,384UTF-16 units rather than truncate.

Minimum metadata DTOs (names provisional; normalized Room implementation belongs to architecture):

```text
ReleaseObservation {
  provider=MUSICBRAINZ, requestedReleaseMbid, observedReleaseMbid, completeTitle,
  releaseGroup? { mbid, title, primaryType?, secondaryTypes[] },
  artistCredits[] { artistMbid, creditedName, joinPhrase },
  status?, date?, country?, releaseEvents[] { date?, areaMbid?, countryCodes[] },
  labels[] { labelMbid?, name?, catalogueNumber? }, barcode?, disambiguation?,
  textLanguage?, textScript?, media[], sourceUrl, fetchedAt, coreDigest, etag?, problems[]
}
MediumObservation {
  sourceArrayOrdinal, declaredPosition, title?, format?, declaredTrackCount?, trackOffset?,
  tracks[] { sourceArrayOrdinal, declaredPosition, printedNumber?, trackMbid?,
             completeTrackTitle?, artistCredits[], lengthMs?, recordingMbid?,
             recordingTitle?, issue? }, membershipStatus
}
```

Dates remain possibly partial strings; printed track numbers and barcodes are strings, not
invented integers. Preserve credited names/join phrases separately from artist canonical names.
Track occurrence identity is release/medium/track, **not recording MBID**: a recording may occur
multiple times. Preserve original array ordinals and declared positions; validate rather than
silently reorder/deduplicate malformed observations. JSON track title is the captured leaf title;
do not replace it with the recording's possibly different title. The docs' XML title-elision rule
does not justify silently repairing missing JSON titles. [4]

An empty/unknown tracklist is legal MusicBrainz metadata. Counts, offsets, every required title,
position validity and response limits must be checked before describing the *observed catalogue
tracklist* as complete. Linked-entity lookup results can be limited to25; missing data is not an
empty complete set. Partial/malformed rows remain explicit metadata gaps. This catalogue state is
separate from the D010 selected YouTube collection's traversal and completeness.

## UI and central-execution semantics

- Search → choose an exact edition → detail/save. Show artist credit, source, release type,
  date/country/format/label/catalogue number and edition comment; `Album` only when actually
  source-declared, otherwise its genuine release type. A supplied EP must not become an Album.
  Preserve Arabic/other-script titles; localize interface labels, not source titles. No autoplay.
- Parent **Resolve this release** captures its complete title and executes D010's raw-first
  Single/Collection/Unplayable workflow. A selected YouTube collection supplies its own members,
  never a ceremonial lookup followed by these MusicBrainz tracks.
- Catalogue track browsing/explicit leaf actions remain separate requests using each frozen
  track title. Label an explicitly chosen catalogue-track batch accordingly; do not imply it is
  the parent resolution result. Keep requested MB identity, observed member B and executed C.
- Display MusicBrainz source links/CC0 provenance. Save is independent of downloading. Use bundled
  placeholder artwork until a separately reviewed artwork source/rights policy exists; do not
  fetch Cover Art Archive, Apple previews or audio from metadata links.

## Bounded networking/cache policy — proposed, not measured

- One app-lifetime provider scheduler: one in-flight request, starts at least1100ms apart using
  monotonic time; at most8queued requests; cancel superseded searches/coalesce identical reads.
  This cannot guarantee a shared NAT's aggregate traffic stays below the provider's IP throttle.
- User-Agent: `Veyra/<version> (https://github.com/Apdelrahman1911/veyra)`. No per-user/device ID,
  account, cookies, authorization, referrer or listening-state uploads. Fixed public metadata
  endpoints only; normal verified TLS/public-URL/redirect/timeouts apply. No mirror/proxy bypass.
- At most2MiB per JSON response,100mediums/5000track occurrences per release and200search rows
  per explicit search session. Reaching a limit is visible incomplete metadata, never complete.
  These are safety ceilings, not promises that every large box set fits. Reject title overflow.
- On429/503, honor a valid Retry-After and present a retryable provider failure; without one,
  apply a shared minimum30s cooldown. No automatic retry loop. A manual refresh is still gated
  by cooldown; never circumvent rate limits by another endpoint/provider.
- Cache normalized **core fields only**. Transient search/detail LRU: at most32MiB and200release
  observations; search freshness24h, detail freshness7days are UI cache policy, not polling
  timers. Stale data remains labeled; network refresh is explicit. User-saved releases and
  immutable queued/request observations are durable library records, not evictable transient cache.
  Offline mode blocks searches/detail refresh/artwork as well as media; saved metadata opens locally.
- No scheduled metadata polling, per-track eager lookups or whole-database/background crawling.
  No public telemetry/raw-response logging. Explain that explicit requests expose query/ID and IP
  to MusicBrainz, whose policy records them. Implementation needs fake-clock/MockEngine rate,
  cache-limit, cancellation, title, pagination, malformed/oversize and offline tests.

## Actual bounded observation

At **10:24:59UTC**, the documentation-linked release
`59211ea4-ffd2-4ad9-9a4e-941d3148024a` detail request above returned HTTP200,
**5,285bytes**, JSON, unchanged requested/returned MBID, and an ETag. Complete title
`æ³o & h³æ`; source-declared `Official`, **EP**, two CD media, one track in each, distinct
track/recording MBIDs and matching per-medium counts/offset0. It was not an Album sample and
does not establish search, Arabic catalogue coverage, implementation, audio rights or playback.
No additional API sample/retry was made. Raw public metadata/document captures, hashes and the
core projection are under `.work/feasibility/album-producer-research/`; they are research evidence,
not authored redistributable test fixtures. Write original fixtures for production tests.

## Sources (official documents fetched 2026-11-06)

1. [MusicBrainz Release](https://musicbrainz.org/doc/Release).
2. [MusicBrainz Release Group](https://musicbrainz.org/doc/Release_Group).
3. [Data License](https://musicbrainz.org/doc/About/Data_License) and [Database core/supplementary breakdown](https://musicbrainz.org/doc/MusicBrainz_Database).
4. [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API).
5. [Rate limiting/User-Agent/polling](https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting).
6. [MetaBrainz privacy policy](https://metabrainz.org/privacy).
7. [Apple Search API, including search/lookup/legal sections](https://performance-partners.apple.com/search-api), reached by Apple's archived developer documentation redirect.
8. [MusicBrainz API Search](https://musicbrainz.org/doc/MusicBrainz_API/Search) and [Examples](https://musicbrainz.org/doc/MusicBrainz_API/Examples).
