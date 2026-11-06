# Podcast UX research — Veyra / ڤيرا

Research date: **2026-11-06**. Status: **research and proposed acceptance contract, not implementation or platform verification**.

Official product/support pages were read over HTTPS. Their descriptions establish documented behavior, not hands-on usability or performance measurements. Source URLs below were reachable when read. No competitor account was created and no installed competitor app was tested. Live directory/feed observations were coordinated with the integration researcher; see [integration-feasibility.md](integration-feasibility.md) for probe methodology and artifacts. Model selection was requested as Astra/max by the lead; effective runtime identity is not introspectable in this agent context.

## 1. Recommended direction

1. **A podcast is a show containing episodes, not an album of unusually long songs.** Following, episode progress/completion, saving for later, queue membership, and possession of an offline file are separate states.
2. **Follow without an account.** Following records the feed locally and enables refresh. It is not a purchase, a download, permission for notifications, or consent to automatic queue insertion. Use “Follow / متابعة”, not a paid-subscription implication.
3. **Resume comes before discovery.** A podcast surface starts with unfinished listening and new episodes of followed shows. Search and paste-feed remain available without sign-in or a populated library.
4. **One audible player; two preserved queue contexts.** Music and Podcasts keep their own current item, position and pending order. Music shuffle/repeat never affects episodes. Switching the visible queue is not a playback action.
5. **Downloaded means locally playable, not merely saved or cached.** Show explicit transfer states, actual errors, local availability and storage actions. Never replace a failed real feed with invented “podcast” examples.
6. **Phone and desktop share behavior, not identical geometry.** Desktop needs a persistent queue, episode details and keyboard access; phone needs a compact resumable player and touch-sized direct actions. Speed, time skips and sleep timer are useful on both.
7. **RSS is the media authority; an Apple directory is an optional discovery aid.** Both no-key access and genuine content were observed, but legal terms, resilience, platform networking, native playback and sustained offline behavior remain separate release gates.

## 2. Competitor findings and what they imply

### Pocket Casts: deliberate listening queues and per-show policy

- [Up Next documentation](https://support.pocketcasts.com/knowledge-base/up-next-continuous-playback/) explicitly distinguishes the manually ordered **Up Next queue** from **Autoplay**. It offers Play next/last, bulk actions, per-show auto-add settings, queue limits and queue-history recovery. It also documents a surprising distinction: playing a different episode returns the old episode to Up Next only when the queue already contains entries. **Veyra should use one predictable replacement rule regardless of whether a queue happens to be empty.**
- The same source documents a silent auto-add-limit failure and automatic removal at the queue tail under one limit policy. **Do not adopt silent queue truncation.** A full queue must expose the reason new items were not added, and an explicit user-added item must not disappear to make room for automation.
- [Playback effects](https://support.pocketcasts.com/knowledge-base/playback-effects/) separates speed, silence trimming and volume boost, with global versus show-specific settings. It explicitly limits desktop/web to speed; mobile has the other effects. **An enabled control is a promise about the active platform and media type.** Veyra should provide verified variable speed first, not advertise unimplemented “Smart Speed” or normalize all audio silently.
- [Sleep timer](https://support.pocketcasts.com/knowledge-base/sleep-timer/) documents duration/end-of-episode controls and Android chapter options, but explicitly says the timer is unavailable in its web/Windows/macOS apps. **This is a comparator limitation, not a reason for Veyra to omit a desktop timer.**
- [Smart Playlists](https://support.pocketcasts.com/knowledge-base/episode-filters/) filter episode status, date, duration and downloads, but are mobile-only. [Archive](https://support.pocketcasts.com/knowledge-base/archiving-episodes/) preserves played state/position while also deleting a download. **Veyra should retain that distinction in its model without making a vaguely labelled archive action unexpectedly delete bytes.**
- [Downloads](https://support.pocketcasts.com/knowledge-base/how-to-download-episodes/) explicitly describe desktop/web download as obtaining a file for a **separate player**, whose progress does not sync back. [Error guidance](https://support.pocketcasts.com/knowledge-base/download-and-playback-errors/) distinguishes unavailable files, forbidden sources, storage and unsupported formats. **Veyra's promised desktop offline playback needs its own integrated local-file/resume proof; an external Download File button is insufficient.** Do not copy advice to weaken transport security as a routine recovery step.

### Overcast: speech-focused controls and a useful privacy distinction

- Its [official product page](https://overcast.fm/) emphasizes natural speech, silence shortening and volume normalization. Its [publisher-authored App Store description](https://apps.apple.com/us/app/overcast-podcast-app/id888422857) documents offline downloads, playlists with smart filters and per-podcast priorities, adjustable sleep timer, and the ability to add one episode without following the show. **Sampling one episode must not implicitly follow its series.**
- [Podcaster information](https://overcast.fm/podcasterinfo) documents public-RSS support, direct downloads from publisher servers, add-by-URL, and MP3/M4A chapter support. It uses Apple’s directory for discovery but allows a public feed not listed there. **Keep source discovery and source playback decoupled.**
- [Privacy policy](https://overcast.fm/privacy) says an anonymous account is automatically created and listening activity can be stored/synced by its service. **No email/password is not the same as local account-free data.** Veyra should not imitate an undisclosed anonymous backend account while advertising local-only storage.
- Official product descriptions foreground iPhone/iPad/Apple Watch. No Windows/Linux desktop feature parity was established. Exact current gestures, default skip amounts and completion thresholds were not independently verified; they are not asserted here.

### Apple Podcasts: clean separation of shows, episodes and availability

- The [iPhone library guide](https://support.apple.com/guide/iphone/organize-your-podcast-library-iph4003ecb12/ios) distinguishes followed shows, saved episodes, downloaded episodes, latest episodes and stations. It documents show-level ordering, download limits, and mark played/unplayed including bulk actions. **Use show containers and episode-level states, not just one giant “Liked” list.**
- [Mac show settings](https://support.apple.com/guide/podcasts/choose-show-and-episode-settings-pod48cadb787/mac) include oldest/newest order, hiding played items, per-show download policy and per-show speed. **Serial and topical listening need different stable ordering.** Store the user’s choice rather than repeatedly deriving it from the current screen.
- [Mac Playing Next](https://support.apple.com/guide/podcasts/choose-which-episodes-play-next-pod64adbf9aa/mac) distinguishes new, unfinished and recently played episodes, plus Play Next versus Add to Queue. **An unfinished two-hour episode deserves a Continue action with its saved position, not a generic Play symbol alone.**
- [Mac playback](https://support.apple.com/guide/podcasts/play-podcasts-pod970198c2/mac) documents 15-second back/30-second forward defaults, customizable skip settings, speed, chapters, a sleep timer, Space to pause/play and visible playback controls. These are useful desktop patterns without copying Apple's visual treatment.
- [iPhone playback](https://support.apple.com/guide/iphone/play-podcasts-on-iphone-iph3a22707a5/ios) documents MiniPlayer-to-Now-Playing expansion, time skips, speed, timer and queue. Explicitly queued episodes precede its Continue Playing section. **Veyra should likewise distinguish deliberate queue additions from any separately enabled continuation.**
- [Saving/downloading on Mac](https://support.apple.com/guide/podcasts/save-download-or-remove-episodes-poda4f6be01/mac) and [iPhone](https://support.apple.com/guide/iphone/download-save-and-remove-podcasts-iph8615288a4/ios) distinguish saving from downloading, although saving triggers download by default. Removing a saved episode and removing its bytes are different actions. **Veyra should keep the distinction, but default to no unexpected transfer.**
- [Following on Mac](https://support.apple.com/guide/podcasts/follow-or-unfollow-shows-podf17151dc/mac) distinguishes free following from paid subscription and supports following an RSS URL. Unfollowing offers a choice about downloaded episodes. **Never couple Unfollow to destructive file/history removal.**

### Spotify: integrated music/podcast discovery, with constraints not to copy

- [Podcasts and shows](https://support.spotify.com/us/article/podcasts-and-shows/) describes a show page, following, a Your Episodes collection, adding an episode to playlists/queue, speed and 15-second skips on mobile and desktop/web. **A shared app can retain podcast-specific player controls and still feel integrated.**
- [Queue documentation](https://support.spotify.com/us/article/play-queue/) describes order editing, removal and clear actions on desktop/web; its mobile section is labelled Premium. The podcast article also mentions adding episodes to the queue. **Do not infer an unrestricted free-tier queue contract from these pages. Veyra’s local queue should not have a subscription gate.**
- [Offline documentation](https://support.spotify.com/us/article/listen-offline/) says the free tier can download podcasts, while the podcast page warns some shows may need Premium. It describes Wi-Fi defaults, storage failures, offline filtering and a 30-day online check to retain downloads. **Do not copy periodic account validation into local public-RSS downloads, or claim all Spotify podcasts are unrestricted.**

### Synthesis: borrow principles, not skins

| Pattern | Keep | Avoid |
|---|---|---|
| Show organization | Following, ordered episodes, per-show settings, saved episodes | Treating a series as a playlist whose contents are always interchangeable |
| Discovery | Show/publisher identity, language, add RSS, play one episode | Automatically following a same-name result or bundling invented shows |
| Queue | Explicit next/end, visible order, reversible edits | Hidden recommendation insertion, queue-dependent replacement rules |
| Speech controls | Speed, semantic time skips, duration/end timer | Music shuffle/repeat on episodes; unsupported speech effects |
| Offline | Separate save/download, visible bytes and failures | Checkmark before local availability; destructive cleanup by default |
| Local data | Persistent progress and explainable export/import | Calling anonymous server sync “no account” or promising automatic cross-device handoff |

## 3. Proposed state and interaction contract

These are **Veyra design recommendations**, not claims about competitor internals.

### Shows and episodes

- **Show:** local ID, current/canonical feed URL plus redirect history, publisher/title/artwork/language, follow state, last successful refresh, order/filter preference, optional download/speed policy. Show details exist even when not followed.
- **Episode:** show identity plus RSS GUID where available, title/description/date/duration, optional season/number/type/explicit flag, enclosure information and publisher page. A directory ID locates a show; it is not the episode’s audio identity. Do not use title or changing enclosure URL alone as the identity key.
- **Independent local dimensions:** saved-for-later; completion flag; last real media position and history; queue membership/order; downloaded-resource state. Deleting bytes never resets progress. Unfollowing never deletes history or files implicitly. Removing a queue item never marks it played.
- If a feed drops an old item, keep locally referenced metadata/progress/download as an identifiable retained episode, with “No longer listed in this feed” when relevant. Do not silently delete it.
- Use `itunes:type`/episode numbering as hints, not infallible facts. Suggest oldest-first for clearly serial shows; otherwise newest-first. Always expose and persist a user override; missing season/number is not “Episode 0.”

### Played, unfinished, new and replay

- Display **New** for never-started items, **In progress** for unfinished listened items, and **Played** for natural completion or explicit Mark played. A simple “Unplayed” filter can include New + In progress, but the row must tell them apart.
- **Natural completion** means a confirmed media end event. Merely dragging near the end, a buffering failure, sleep-timer pause, or loss of connection is not completion. A “nearly finished” threshold is not silently assumed.
- **Mark played** changes the completion flag without manufacturing listening time or erasing the last real position. **Mark unplayed** clears that flag without erasing history. If a retained position is unfinished, offer Resume; if actually at the end, offer Replay. **Start over** explicitly resets the resume point with Undo. Explain these distinctions in the action sheet.
- Explicit queued items remain under user control even when marked played; automatic “new/unplayed” selections may filter them out. A played item deliberately started or reached in an explicit queue starts a replay session from zero. The completion override/history remains reversible; do not get trapped resuming at EOF.
- Persist a progress checkpoint during listening and at pause/seek/item/context switches. Proposed crash-resume target: loss no more than five seconds of acknowledged progress; normal pause/relaunch should restore the committed point within the native player’s verified seek tolerance. No playback starts just because the app reopened.
- Remaining **media duration** and speed-adjusted **estimated listening time** must be labelled differently. Do not silently call 60 media minutes “30 minutes left” at 2× without context. Unknown duration remains unknown until measured.

### Podcast queue and music coexistence

1. Each mode has a persistent **current item + pending list**. There is only one active audio session. Opening another mode’s library or queue never starts/stops audio.
2. Pressing **Play/Resume** in the other mode commits and pauses the outgoing item, activates the requested mode and restores/starts the selected item. The other mode’s pending order and settings remain intact. Queue headers name the mode being viewed and mark which mode is playing.
3. Pressing Play on a *different episode in the same podcast context* saves the old episode to Continue listening but does **not** insert it into the pending queue. This rule is the same for empty/nonempty queues. The pending list is not cleared. If the newly playing item was pending, consume that pending occurrence rather than accidentally playing it twice.
4. **Play next** inserts immediately after the current item; **Add to end** appends. No duplicate pending copies by default: an already queued row says “In queue” and offers Move next/Remove instead. Reorder/remove have Undo; Clear affects pending items only, confirms a nonempty clear and never stops the current item.
5. Podcast sequencing uses no shuffle/repeat. Finishing the current episode advances through explicitly queued episodes unless a timer or stop-after-current setting applies. When empty, stop by default. “Continue this show automatically” is a separately labelled opt-in that follows the saved show order; it must not cross into Music or recommendations.
6. Per-show auto-add is off by default. If enabled, new releases go after explicit additions; queue-origin labels explain automation. A cap stops adding with a visible explanation rather than evicting another item.

**Alternative:** a single mixed queue with typed entries could support an intentionally curated music-and-talk sequence, but would need per-item repeat/shuffle suppression, cross-type source ordering and explicit mixed-mode UI. It is a higher-risk alternative, not a shortcut to implementing the above. Do not implement both semantics without a product decision.

### Speed, skip and timer

- Default podcast speed 1×. Offer a validated useful range (candidate 0.5×–2× initially; broader values only after engine tests), reset-to-1×, and “This show / All podcasts” scope. No changes to music speed. Silence trimming/normalization are separate enhancements requiring measured audio quality and platform support.
- Main transport: **Back 15 seconds / Play-Pause / Forward 30 seconds**, with configurable podcast defaults. These are not previous/next-episode buttons. Next episode, if exposed, is separately labelled. Announce changes and provide a keyboard/screen-reader equivalent for scrubbing.
- Timer: preset/custom elapsed duration and **End of this episode**. Duration counts real elapsed time, independent of playback speed, and does not start playback if it expires while paused. It is global active-session state: show its countdown and Cancel/Extend access in **both Music and Podcast mini/full players**, not only on a podcast screen.
- An end-of-episode timer fires **before** queue advance. It targets the episode selected when set; manually playing a different item or changing playback context cancels this episode-bound timer with visible feedback. A time-based timer remains visibly active across deliberate item/context changes and pauses whichever item is active at expiry. Neither mode silently restarts a timer or playback after expiry; expiry and context-switch events must be serialized to avoid stale callbacks pausing a later session.
- OS interruption, headphone disconnect and loss of audio focus are pause/interruption states, not completion. Preserve position and obey native return-from-interruption policy; do not indiscriminately resume after every route change.

## 4. Original journeys and adaptive screen concepts

These concepts intentionally use original information hierarchy, not a recreation of any competitor’s branded screen.

### Journey A — Arabic commuter, first use to reliable offline listening

1. Open the Podcasts surface: explain “Your listening stays on this device”; offer Search podcasts and Add RSS feed. An empty library is an intentional state, not a request to register.
2. Search **فنجان** with an explicit directory/storefront label. Results show **show + publisher + language + source**: the verified ثمانية result is not necessarily first among similarly named shows.
3. On the show page, inspect title/publisher and episode list. Follow is an independent reversible action; sampling an episode does not require it. Auto-download remains off until deliberately configured.
4. Open the genuine long episode, start listening, choose a show-specific speed, then download. Initially display estimated size; show waiting/transfer/progress states and the real response size when available.
5. After completed local verification, show “Downloaded — available offline”. In airplane mode, Resume opens the local file at the saved position. A failure leaves the saved position intact and explains recovery.

### Journey B — desktop listener catching up on a serial show

1. In Following, select the show. A center episode list offers oldest/newest order, New/In progress/Played and downloaded filters, with a visible result count.
2. Shift-select or keyboard-select episodes; choose Add to end without starting playback. A side queue explains current episode versus pending episodes and origin.
3. Open an episode’s notes without replacing the active player. A direct Resume action returns to an unfinished episode; bulk Mark played states exactly how many episodes it affects and offers Undo.
4. Change to Music and press Play on a song. The podcast position and pending list survive. Returning to the podcast queue is inspection only; Resume is the explicit playback action.

### Journey C — bedtime without losing a two-hour conversation

1. Resume from Continue listening; choose End of this episode or 15 minutes.
2. The player displays timer status while other screens are visited. On expiry audio pauses and the queue does not advance.
3. Next launch shows the saved position without autoplay. Replay/Start over never replaces Resume merely because the application process restarted.

### Proposed screens

| Surface | Phone | Wide desktop |
|---|---|---|
| Podcast hub | Continue listening first; New episodes list; Following/Saved shortcuts; source-aware search | Same hierarchy with a navigation rail/list and useful list density, not an enlarged phone poster |
| Show details | Compact cover/title/publisher, Follow, optional “Start from first episode”; episode filter/order and list | Show summary beside sortable episode list; notes pane on demand; keyboard multi-select |
| Episode details | Title, show/date/duration, Resume/Play, Save, Download, Play next/Add to end, readable notes | Details drawer/pane that does not disturb list scroll or active player |
| Now playing | Persistent mini-player → focused full player; speed/timer/queue clearly accessible | Persistent bottom transport; optional notes/queue panes; no hover-only essential control |
| Queue | Current + pending, mode label, accessible reorder menu, visible automation origin | Optional docked panel when width/type scale permits, overlay otherwise; drag plus Move up/down/top keyboard/menu controls; always one-action access |
| Downloads | Ready / In progress / Needs attention; pause/resume/cancel; storage summary | Same states with size and source columns, bulk explicit deletion and path/access recovery |

Episode rows should prioritize title and listening status over oversized artwork. An unfinished item might read `Resume · 47:20` plus `1h 25m media remaining`; these are **illustrative state labels**, not fabricated measurements for a real episode. Unknown metadata is omitted or labelled, never filled with plausible invented values.

### Arabic/English and accessibility contract

- Translate app chrome, not publisher names/titles. Suggested glossary for review: Podcasts **بودكاست**; Follow **متابعة**; Continue listening **متابعة الاستماع**; Downloaded **تم التنزيل**; Play next **تشغيل التالي**; Add to end **إضافة إلى نهاية القائمة**; Mark played **تحديد كمستمَع إليها**. Have an Arabic speaker review final inflections and context.
- Mirror navigation, reading order and start/end spacing for RTL. Isolate feed URLs, GUID-like text, Latin titles and timecodes so they do not corrupt adjacent Arabic punctuation. Long Arabic titles wrap without forcing the player off-screen.
- Media direction is semantic: Back 15 must always move to an earlier position and Forward 30 to a later one. Do not blindly mirror media arrows/timelines through layout mirroring. Validate visual/keyboard/screen-reader behavior in both locales with the accessibility researcher’s platform guidance.
- Never communicate Played/Downloaded/Error only by color. Include accessible state/action names, keyboard reachability, scalable type and non-drag alternatives. Read completion/remaining time meaningfully rather than as an unexplained percentage.

## 5. Offline and long-form failure states

| Situation | Truthful UI and safe action |
|---|---|
| No followed shows | “Follow a show or add its RSS feed”; no fake recommendations or error banner |
| Directory fails/rate-limits | Distinguish “Search unavailable” from “No results”; keep local search and Add RSS usable; offer retry and cached results with timestamp |
| Feed refresh fails | Keep last successful episode list and follows; show last refresh and Retry; never empty the library as if the user unfollowed |
| Feed parses but has no episodes | “This feed currently has no episodes”; distinguish a genuinely empty channel from malformed XML and from unavailable audio |
| Invalid feed URL/HTML/XML | Keep user input; explain invalid/non-podcast response; do not add a phantom empty show; safe details/retry |
| Missing, expired or unsupported enclosure | Disable only unavailable play/download action with a reason; preserve notes/progress and offer publisher page/refresh; do not substitute another episode |
| Stream buffers or connection drops | Show Buffering/Interrupted rather than falsely advancing elapsed time; preserve confirmed position; bounded retry with cancel |
| Seek unsupported/fails | Do not pretend the requested position was reached or jump to zero unnoticed; retain last confirmed position and offer local download/retry |
| Waiting for Wi-Fi / network | Explain why transfer waits; allow explicit cellular override or cancel; not a forever-spinner |
| Partial download / changed remote resource | Keep partial bytes out of Ready; resume only when the resource version/range semantics are safe, otherwise explain restart |
| Storage full / path permission lost | Pause/fail with Manage storage or choose/regrant location; do not delete unrelated files or mark successful |
| Offline queue reaches unavailable item | Pause at that item; offer “Play next downloaded” or “Skip unavailable this session”; no silent destructive queue edits |
| File missing/corrupt | Remove the ready claim, retain metadata/progress, explain that re-download needs a connection |
| Feed item removed by publisher | Retain locally referenced record/file; explain source no longer lists it; do not infer that the media URL still works |
| App reinstall / another device | Explain device-local storage/export limits; do not claim cloud handoff or permanent retention through uninstall |

**Transfer state vocabulary:** Not downloaded → Waiting/Queued → Downloading → Paused/Failed or Verifying → Ready; Removing is explicit. A cached stream segment is not Ready. Default cleanup is manual; optional “remove played downloads” must disclose timing, exclude the active file and respect keep-offline/pinned choices. File deletion does not erase follows, completion or progress.

## 6. Genuine data and no-key source recommendation

### Evidence, not a feasibility declaration

[Apple Search API documentation](https://performance-partners.apple.com/search-api) (the archived developer URL redirects here) documents JSON search/lookup, `media=podcast`, podcast entities, country/storefront, result limits, approximately **20 calls/minute subject to change**, and caching guidance. It describes no API-key parameter for these requests. Public requests without credentials were observed by the integration researcher. The language parameter documents English/Japanese values; **do not invent an Arabic API language value**. Arabic search terms and country selection are different from UI language.

[Apple RSS requirements](https://podcasters.apple.com/support/823-podcast-requirements) describe public RSS 2.0, stable episode GUIDs, enclosure URL/length/type, and server support for HEAD/byte ranges. These requirements are useful interoperability guidance, **not proof that every indexed feed obeys them**. [Overcast’s public-RSS/directory design](https://overcast.fm/podcasterinfo) demonstrates the model’s existence, not permission to copy its implementation or a guarantee of Apple API availability.

Coordinated live observations on 2026-11-06:

| Genuine source | Observed metadata | Limit on the claim |
|---|---|---|
| [Planet Money RSS](https://feeds.npr.org/510289/podcast.xml), Apple ID `290783428` | English feed, 355 observed items; “The history of light (classic)”, GUID `d7ae601b-8465-47f2-835c-21fba48f631d`, declared duration 1266 seconds | NPR feed says Personal Use Only; not a redistributable bundled soundtrack |
| [فنجان RSS / ثمانية](https://files.hosting.thmanyah.com/podcasts/89/1713955813943-768/rss-feed.rss), Apple ID `985515827` | Arabic feed, 364 observed items; “لماذا تخلّى علماء الإسلام عن عروبتهم”, GUID `37c66119-fcef-4eba-8000-91ecae9ec5a6`, declared duration `02:13:05` | Genuine Arabic result was not the first same-name search result; feed copyright is retained by ثمانية |
| [Hacker Public Radio RSS](https://hackerpublicradio.org/hpr_rss.php), Apple ID `281699640` | English independent feed; [episode 4764 by Ahuka](https://hackerpublicradio.org/eps/hpr4764/index.html) is a compact candidate for a codec/offline test | [About/licensing](https://hackerpublicradio.org/about.html) and episode-specific exceptions/attribution must be retained; do not generalize one episode’s licence to every feed |

The integration researcher observed bounded audio-range responses for NPR and فنجان, but **RSS byte lengths differed from the actual range response totals**: NPR declared `20,264,002` versus observed `20,264,410`; فنجان declared `191,262,162` versus observed `127,773,758`. Therefore show feed sizes as estimates until a transfer’s authoritative resource information is available. Do not validate completeness using the RSS length alone. Dynamic insertion, alternate encodings and changing resources also mean a previously saved timestamp cannot guarantee exactly the same spoken sentence after a remote asset changes.

Read [integration-feasibility.md](integration-feasibility.md) for exact probe evidence, HEAD-method caveats and any full-media decode test. Directory success, XML parse success and a small HTTP 206 response are **not** five-platform background playback, seek, licence clearance, verified local download, or product readiness.

### Proposed source path and gates

1. **Core fallback:** user pastes a public RSS feed URL, previews real show/publisher metadata, then chooses Follow. Local library and already downloaded files do not require directory access. Supporting user-owned or permissioned feeds must not require an Apple listing.
2. **Optional discovery:** Apple search/lookup resolves a show to its feed. Deduplicate by stable feed identity, not title; display publisher and source. Debounce, cache, constrain results and back off; exact throttling ownership and current service terms still need confirmation.
3. **Episode authority:** fetch publisher RSS and use validated enclosures, not Apple music previews, YouTube search guesses or scraped streaming-service playback URLs. Missing chapters/transcripts remain absent. Do not invent provider completeness.
4. **Terms gate:** Apple’s API page restricts promotional assets and describes store badges/linking, attribution for applicable previews and non-entertainment uses of promotional content. Before a catalog/artwork UI ships, record the applicable current terms and precise badge/attribution/cache requirements. Fetching publisher artwork instead does not automatically grant a licence. Do not state “all Apple metadata/artwork is free to reuse.”
5. **Audio rights gate:** public syndication permits a technical fetch, not arbitrary rehosting, redistribution or removing sponsorships. Respect publisher terms; obtain permission or use explicitly licensed/user-owned material for bundled test media. NPR/ثمانية observations establish real sources, not distribution licences. HPR is a documented open-licence candidate subject to episode attribution/ShareAlike obligations and exceptions.
6. **Security/privacy gate:** treat RSS, descriptions, redirects, artwork and audio URLs as untrusted; bound parse/network work, disable external entity expansion, sanitize active markup and unsafe URI schemes, and define a deliberate local/private-address policy for imported URLs. Signed/private feed URLs are secrets: never leak them to directory search, telemetry, logs, share sheets or ordinary OPML export. Direct publishers still receive network requests/IP addresses; local-account-free is not network anonymity.
7. **Runtime gate:** validate Android/iOS/Windows/macOS/Linux networking, redirect/TLS behavior, native decoders, speed, seek, cancellation, background interruptions and durable offline storage independently. No-key today is not an SLA or proof of unlimited commercial use.

## 7. Acceptance criteria for design and implementation review

All rows below are **required evidence to obtain**, not tests already passed.

| ID | Scenario and observable pass condition |
|---|---|
| P01 | Without any account, import a valid permissioned RSS feed, inspect show/episodes, follow/unfollow, restart and retain the intended local state. Invalid feed input does not create a phantom show. |
| P02 | Search real English and Arabic terms. Results include publisher/source, not just names; selecting a same-name result never silently follows another feed. Simulated empty result, timeout, 429 and cached result are visibly different. |
| P03 | New/In progress/Played states, Saved and Downloaded are independently demonstrable. Remove download retains resume/completion; remove queue entry does not mark played; unfollow does not silently delete files/history. |
| P04 | Play >45 minutes into a genuine long episode; pause, force-close, restart and resume from the durable position without starting audio on launch. Measure the checkpoint/seek tolerance on each target, including offline restart. |
| P05 | Natural EOF, seek-near-end, sleep expiry, network interruption and manual Mark played produce the defined distinct completion states. Undo restores a manual state change without fabricating listening time. Replay/Start over is distinguishable from Resume. |
| P06 | Given current A and pending B,C, Play next D yields D,B,C; Add to end E yields D,B,C,E. Reorder/removal/clear are reversible as specified; duplicate addition is handled visibly. Starting another episode does not clear pending items or conditionally requeue A. |
| P07 | Start a song while an episode is unfinished, then return. Exactly one item is audible; both pending orders survive; podcast position survives; inspecting a queue never autoplays; music shuffle/repeat does not reorder/repeat episodes. |
| P08 | Podcast queue-empty stops by default; opt-in show continuation uses the selected order and is visible. A queue cap/auto-add conflict is explained without discarding explicit additions. |
| P09 | Show-specific speed persists across its episodes but does not alter other shows or songs. 15s back/30s forward work semantically in Arabic and English, including keyboard and screen-reader actions. Unsupported effects are not falsely enabled. |
| P10 | A 15-minute timer expires after real elapsed time at 0.5×/1×/2×, independent of media time; End of this episode pauses before queue advance. With a fake clock, set 60s at 2× podcast, switch to music at 30s and observe one pause at 60s with timer visible throughout. Also race manual switch exactly at expiry. Pause/background/item-change behavior matches the contract; expiry never starts playback. Test on desktop as well as phone. |
| P11 | Complete a real download, disconnect network, restart and seek/play the local file. Only then claim offline readiness. Partial bytes, corrupt/missing files, changed enclosure and stale RSS lengths cannot produce Ready. |
| P12 | Network loss, storage full, permission loss, unsupported media, HTTP errors and interrupted transfers expose useful recovery while preserving progress and other files. Offline queue behavior is explicit and non-destructive. |
| P13 | Real Arabic and English titles, mixed-script notes/timecodes/URLs, long names and large type remain legible on compact phone and wide/narrow desktop. Core actions do not depend on swipe/hover/drag or color alone. |
| P14 | Local state survives normal upgrades; export/import behavior and uninstall limits are documented. Any OPML export is labelled as subscriptions, not full progress/queue/download backup; private credentials are excluded by default. |
| P15 | Record source terms/attribution decisions and run the corresponding real-media playback/offline/interruption checks on **each** Android, iOS, Windows, macOS and Linux target. A server-side curl/decoder result alone cannot close this gate. |

## 8. Open decisions and rejected shortcuts

- **Keep the two-context queue recommendation pending integrated review.** If intentionally mixed queues are chosen, rewrite P07/P08 and the repeat/shuffle/continuation contract before implementation.
- **Per-show automatic downloads are useful but require explicit limits and informed enablement.** Never default to downloading an entire multi-year feed when followed.
- **Manual completion is not telemetry.** A played flag is library organization, not proof someone listened to every second; do not manufacture a listening-statistic reward from it.
- **Parser/native media choices are architecture decisions still requiring probes.** This research does not endorse an unevaluated XML library/version or promise MP3/M4A/Opus parity.
- **No fallback theatre.** If music playback from the designated engine is blocked, genuine RSS podcast playback does not prove music success. An open-licensed direct-audio music source could be a separately labelled supplement, only after rights/source/codec checks—not relabelled as the original music service.
- **Do not borrow private-platform guarantees.** Account synchronization, proprietary subscriptions, transcripts generated by a service, silence trimming, casting/vehicle integrations and unlimited directory availability are not implied by reading public support pages.

## 9. Top-three cross-review challenges and resolutions

Reviewed [music-ux.md](music-ux.md) and [architecture.md](architecture.md), not just verbal feature lists:

1. **One owner must not accidentally become one queue context.** The architecture draft's progress section said music could differ “without introducing a second queue,” while music/podcast proposals require two preserved contexts. Its initial snapshot description did not explicitly encode the distinction. **Resolution:** one coordinator/native transport owns two context snapshots, with `activeTransportContext` distinct from UI-only `viewedQueueContext`. Only Play/Resume activates; a row selection or open-details action does not. Commit outgoing progress and the context switch coherently. The architecture researcher accepted the correction. **Test:** browse/select the other context while shuffled music is audible, then deliberately Resume an episode; only the second action switches audio, both orders/positions survive, and stale callbacks cannot mutate the new context.
2. **Shuffle persistence alone does not define unshuffle after manual edits.** Music's initial contract left this open; architecture's persisted seed/order did not resolve the interaction. **Resolution:** lead chose original insertion ranks for ordinary unshuffle while preserving explicit additions; a manual reorder disables shuffle with disclosure and retains the resulting order. Next/end continue to mean the positions the user can see. **Test:** shuffle, Play next, Add to end, create/remove one duplicate occurrence, manually reorder, toggle modes and restart; no occurrence or manual addition disappears, and the displayed/persisted order agrees.
3. **A podcast-only timer UI can silently pause music.** Music review identified that “timer remains active across item changes” was ambiguous across contexts. **Resolution:** a duration timer is global active-session state, visible/cancellable in both modes' mini/full players. It pauses whichever item is active at expiry. An end-of-episode timer is tied to the original episode and cancels visibly on manual item/context change. Expiry and switching share serialized ownership. **Test:** fake clock, 60s timer at 2× podcast, deliberate Music switch at 30s, one pause at 60s, countdown visible throughout; repeat with switch exactly at deadline and stale native callbacks.

**Integrated navigation decision:** the lead selected Listen / Search / Podcasts / Library. Podcasts owns Continue/New/Following/Add RSS; Search remains shared, and Library owns unified saved/downloaded collections. The optional desktop queue is docked only when width/type scale permit, otherwise overlaid, with the same one-action access and one underlying transport.

**Supplemental open music:** the music researcher verified metadata for a specifically CC0 performance on Wikimedia Commons and documented the per-recording rights/codec caveats in [music-ux.md](music-ux.md). Use that evidence rather than start a second broad catalog search. Such a clearly labelled supplementary source must not hide a blocked kmpYT result or be reported as successful YouTube playback.

Research and cross-review complete; implementation and P01–P15 verification remain outstanding.
