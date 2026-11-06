# Veyra / ڤيرا — original design, accessibility, and Arabic RTL research

**Phase:** research completed; shared design implementation and static audits authored. No compiled/UI/accessibility certification is implied.
**Research date:** 2026-11-06.  
**Scope:** Kotlin Multiplatform / Compose app, Android, iOS, Windows, macOS, Linux; English and Arabic; music and podcasts; online and genuinely available offline media.

**Authoritative scope amendments:** `DECISIONS.md` D005/D006/D008/D009 supersede early product assumptions in this research. All playable acquisition uses complete-title → first ordered kmpYT result, including podcasts and externally discovered/local references; RSS/Commons are metadata, not an independent audio route. Room owns durable state, downloads require complete resumable/background/retry/validation phases, and native equal-power **music-only crossfade is required**. One coordinator may own two deliberately overlapping decks; “one player” never means disabling that requirement. See the implementation handoff update below for evidence limits.

## 1. Recommendation and evidence boundary

Build a **quiet listening room**, not a clone of an existing service: deep petrol and warm paper surfaces, sea-glass actions, restrained sand highlights, generous typographic rhythm, and artwork treated as content rather than decoration. The original mark and interface icons should be code/vector assets. Do not generate raster assets merely to decorate empty states. Use licensed artwork only; a missing cover gets a neutral geometric placeholder, never a fabricated album cover.

**Cross-review revision:** recommend four primary destinations — **Listen, Search, Podcasts, Library** — stable across sizes. The initial three-destination proposal used media filters; reviewing the distinct podcast Follow/New/In-progress/Add-RSS tasks justifies a dedicated surface rather than making it a hidden filter. This is a task-based design decision, **not** a measured claim that four destinations outperform three. The lead owns final approval. A persistent player is a separate region, not another destination. Downloads are conspicuous inside Library and directly reachable from offline messaging. Music and podcasts share the shell but do not pretend to have identical controls or metadata.

Evidence supports familiar *interaction categories*, not copying composition, branding, or artwork:

| Source inspected | What it supports | Veyra decision / limitation |
| --- | --- | --- |
| Android adaptive guidance [S1] | Window width classes at 600, 840, 1200, 1600dp; compact height below 480dp | Adapt to the available window, not an assumed device category; make height matter for the player. |
| Android Compose accessibility defaults [S2] | Interactive targets at least 48dp; accessible standard components | Keep shared touch targets at least 48dp. Custom drawing does not excuse missing semantics. |
| Apple current HIG accessibility [S3] | Larger text, contrast, assistive technology, gesture alternatives; currently lists 44pt *default* iOS controls and smaller platform minimums | Do not inaccurately cite 44pt as the universal current Apple minimum. Veyra deliberately chooses the more generous shared target, and verifies at least 44pt on iOS. |
| Apple current RTL HIG [S4] | Leading alignment, language-appropriate paragraphs, reversed navigation, preserved images/logos | Use relative start/end throughout. Do not flip artwork or numeral glyphs. |
| Material archived bidirectionality + Apple internationalization archive [S5, S6] | Explicit exception: media transport and playback timelines remain LTR | Preserve an LTR transport island inside an otherwise RTL player. These are legacy primary sources; current HIG generic slider advice alone does not describe this exception. |
| Microsoft keyboard accessibility [S7] | Focusable controls, visible focus, intentional tab order, keyboard activation | Treat desktop keyboard work as a real interaction mode, not a pointer UI with occasional shortcuts. XAML-specific APIs do not prove Compose support. |
| WCAG 2.2 and explanatory pages [S8–S10] | Contrast, resize/reflow, focus visibility, non-drag alternatives, status messages | Use measurable cross-platform acceptance criteria; this is not a claim that a native app automatically conforms to a web standard. |
| W3C bidi guidance + Android internationalization [S11, S12] | Isolation of inserted opposite-direction text; unknown first-strong direction; bidi-aware formatting | Metadata direction is independent of interface language. Never implement RTL by reversing strings. |
| Spotify's official desktop redesign account [S13] | Separately resizable library and now-playing regions around a central content area | Optional supporting pane at sufficient width; not a compulsory three-column squeeze. The article's usability claims are Spotify's, not independently verified here. |
| Pocket Casts official Up Next documentation [S14] | Queue access from mini/full player, explicit add/remove actions, queue distinct from saved collection | Put Queue in both player sizes; support Play next / Add to queue. Do not require a swipe or long press to discover essentials. |
| Font publisher/distribution files [S15–S18] | Redistributable fonts under OFL 1.1; inspectable glyph coverage | Prefer one bilingual family to avoid inconsistent Latin/Arabic fallback. Record exact asset hashes and licenses when bundling. |

Only primary pages/files actually fetched were used for these observations. No installed competitor app was exercised. The Material 2 page was a JavaScript shell, a Material 3 URL returned 404, and guessed Pocket Casts download URLs returned 404; they are **not** evidence. No UI, screen reader, platform binary, or hardware playback has been verified in this phase. The lead's requested `gpt-6-astra` / `max` selection was accepted by orchestration; this worker has no effective-runtime model/effort attestation interface and does not claim independent confirmation.

## 2. Design principles and original visual language

1. **Content before chrome.** One dominant action per content area; quiet surfaces; no commercial-promotion panel occupying the queue's space.
2. **Listening is continuous.** Changing a tab, locale, theme, or window size must not silently reset playback, queue, or position.
3. **Offline means playable.** Saved, cached metadata, downloaded media, and currently reachable stream are different facts.
4. **Arabic is a first-class layout.** Design paired en/ar states from the beginning, including mixed-script catalog names and long episode descriptions.
5. **Comfort beats density.** No tiny hover-only buttons, forced one-line Arabic text, or inaccessible waveform-only seeking.
6. **Capability honesty.** A prototype state is visibly identified. Unsupported playback, downloads, transcript, synchronization, or account functions do not masquerade as working controls.

The visual motif is a pair of gently diverging listening strokes forming a restrained V, with an optional small echo stroke. Draw an original path on a documented grid; the mark must remain unchanged in RTL. It is a proposed mark, not trademark clearance. Use the actual Arabic brand **ڤيرا** (initial character U+06A4) and English **Veyra** as text, not outlined body copy. Avoid faux-Arabic Latin lettering.

### Icon contract

- A single 24-unit grid, approximately 2-unit rounded strokes, rounded joins; optical balancing is allowed but documented.
- Navigation/content icons are outlined; central play/pause may be filled for immediate recognition. Use the same geometry across states, not an unrelated icon pack per screen.
- Start with Listen, Search, Library, Download, Queue, More, Back, Close, Play, Pause, Previous, Next, Rewind 15, Forward 30, Repeat, Shuffle, Volume, Settings, Check, Alert.
- Icon-only controls always have localized accessible names and desktop tooltips. Selected state is shape/container plus semantics, not color alone.
- Directional navigation is explicitly auto-mirrored; media transport is explicitly not. A global canvas `scaleX = -1` is forbidden.
- Never flip album art, photographs, wordmarks, checkmarks, clock faces, or numerals. Do not draw recognizable service logos without permission.

## 3. Concrete color tokens

Proposed names map cleanly into Material roles while leaving native high-contrast treatment possible. Values below are **opaque sRGB**. Artwork-derived or dynamic color must not override critical text/control colors without another contrast audit.

| Token / intended use | Light | Dark |
| --- | --- | --- |
| `canvas` | `#F6F8F8` | `#0D161B` |
| `surface` — main panels/cards | `#FFFFFF` | `#14232A` |
| `surfaceContainer` — navigation/player | `#EDF2F3` | `#1C2E37` |
| `surfaceContainerHighest` — selected/raised neutral area | `#E0EAED` | `#253D47` |
| `onSurface` — primary text | `#15252D` | `#E8F0F3` |
| `onSurfaceVariant` — secondary text, durations | `#465F6B` | `#AFC2CC` |
| `outline` — essential boundaries/icons | `#647D89` | `#819AA5` |
| `outlineSubtle` — decorative separators only | `#CDDADF` | `#314852` |
| `primary` — sea-glass action | `#00696A` | `#76D7D4` |
| `onPrimary` | `#FFFFFF` | `#003735` |
| `primaryContainer` | `#BDEDEB` | `#164E50` |
| `onPrimaryContainer` | `#003736` | `#BDEDEB` |
| `secondary` — restrained sand accent | `#855223` | `#E5B88A` |
| `onSecondary` | `#FFFFFF` | `#442B11` |
| `secondaryContainer` | `#F9DFC3` | `#604322` |
| `onSecondaryContainer` | `#3B2209` | `#F9DFC3` |
| `error` | `#AD292F` | `#FFB3B5` |
| `onError` | `#FFFFFF` | `#680A13` |
| `errorContainer` | `#FFE2E1` | `#552025` |
| `onErrorContainer` | `#680A13` | `#FFDADB` |
| `success` — with check + text | `#196B44` | `#85D3AA` |
| `warning` — with warning icon + text | `#785104` | `#EFBC66` |
| `focusRing` — neutral-surface controls | `primary` | `primary` |

Do not apply `onSurface` on a filled primary/error/secondary button; use its paired `on…` token. Status colors on `surface` are suitable for ordinary-size text, not merely large text. Do not make downloaded/playing/error states identifiable only through hue.

### Contrast verified in this research

Calculated with the WCAG sRGB relative-luminance formula, `(lighter + .05)/(darker + .05)`, with no alpha blending. These checks validate **token pairs only**, not future screenshots, overlays, font rendering, or entire accessibility conformance.

| Pair | Light | Dark |
| --- | ---: | ---: |
| `onSurface / canvas` | 14.76:1 | 15.85:1 |
| `onSurface / surface` | 15.74:1 | 13.95:1 |
| `onSurfaceVariant / surface` | 6.75:1 | 8.75:1 |
| `onSurfaceVariant / surfaceContainerHighest` | 5.52:1 | 6.21:1 |
| `outline / surfaceContainerHighest` | 3.55:1 | 3.86:1 |
| `primary / surface` | 6.50:1 | 9.54:1 |
| `primary / surfaceContainerHighest` | 5.32:1 | 6.77:1 |
| `onPrimary / primary` | 6.50:1 | 7.79:1 |
| `onPrimaryContainer / primaryContainer` | 10.30:1 | 7.36:1 |
| `secondary / surface` | 6.50:1 | 8.86:1 |
| `onSecondary / secondary` | 6.50:1 | 7.22:1 |
| `onSecondaryContainer / secondaryContainer` | 11.54:1 | 7.05:1 |
| `error / surface` | 6.70:1 | 9.49:1 |
| `onError / error` | 6.70:1 | 7.53:1 |
| `onErrorContainer / errorContainer` | 10.49:1 | 10.07:1 |
| `success / surface` | 6.51:1 | 9.13:1 |
| `warning / surface` | 7.05:1 | 9.25:1 |

`outlineSubtle / surface` is only 1.43:1 light / 1.67:1 dark: **not** a permitted essential control outline, focus ring, or text color. Use it only when the separator is decorative and grouping is otherwise apparent.

Focus indicator: at least 2dp contrasting outline, 2dp offset; no reliance on shadow. A ring matching a filled button's background disappears: use a two-tone indicator (contrasting inner stroke and outer ring) or an appropriate `on…` stroke, verified against both adjacent surfaces. Keep it fully visible and unclipped. Increased-contrast mode should use stronger boundaries, opaque surfaces, and explicit selected markers; read actual platform preferences where available rather than claiming custom tokens automatically follow them.

Disabled actions should normally retain readable labels and show a reason nearby. Do not use opacity to make useful status text illegible. Pressed/hover state layers are supplementary; they cannot replace selection/focus semantics. Blur/glass behind text is optional, never necessary for hierarchy.

## 4. Typography and redistribution

### Recommended initial family: IBM Plex Sans Arabic

Use unmodified static **Regular (400), Medium (500), SemiBold (600)** from the published font distribution [S15, S16]. The inspected Regular font contains Latin and Arabic in the same file, reducing the risk of different shaping/fallback choices between mobile and desktop. This avoids depending on proprietary system fonts being redistributable. Do not require internet font fetching at runtime; offline rendering must be identical.

In-memory inspection of the fetched Regular font's Unicode `cmap` confirmed nonzero mappings for `A`, `a`, `0`, `ا`, `ڤ` U+06A4, Eastern Arabic digits `١` and `٣`, `é`, en dash, curly apostrophe, fatha and shadda. This is **glyph coverage only**, not a shaping, legibility, or screenshot test. The fetched Regular file was 235,924 bytes; SHA-256:

`6f611412270a132bbac838da9259d4c68569b4175f3b3b8fa3fa36a30b56dab9`

The distribution's license is **SIL Open Font License 1.1**, copyright 2017 IBM Corp., with **Reserved Font Name “Plex”**. Bundle the exact copyright/license with shipped fonts and include an in-app license entry. Fonts may be bundled/embedded/redistributed with the app but not sold standalone. Modified font versions must respect reserved-name restrictions and remain under OFL. Prefer shipping upstream bytes unchanged; subsetting/renaming is a separate reviewed operation. Pin a source revision and record SHA-256 for **each** bundled weight during implementation; a moving `main` URL alone is not a reproducible lock.

Alternatives verified:

- **Noto Sans Arabic** [S17]: OFL 1.1, very broad Arabic coverage; the fetched variable file also contains tested Latin/Arabic characters including ڤ. Strong fallback if Plex proves hard to read at small sizes. Tested file: 844,676 bytes, SHA-256 `63111b5b2e074dd48cc67692e0a2726d86ee94c1c37fe8598257b7b4e87e869e`.
- **Manrope + Noto Sans Arabic** [S17, S18]: attractive distinct Latin branding, both OFL, but Manrope lacks all tested Arabic letters/marks and creates more mixed-run/fallback testing. Manrope fetched file SHA-256 `3ae11c49db0455a3cc33e37d380f20fdb8c7f8b41dc07625c177e3d87a9d6ae6`. Not the first implementation recommendation.
- **Platform system fonts**: native feel and good platform scaling, but inconsistent visual identity and Arabic metrics; never copy/redistribute Apple or Microsoft font binaries merely because installed locally.

### Type scale

Units are unscaled logical `sp`; line heights scale with text. These are initial style tokens, not fixed container heights.

| Token | Size / line height | Weight | Use |
| --- | --- | --- | --- |
| `display` | 36 / 48 | 600 | Occasional wide-screen heading, not every screen |
| `headline` | 28 / 40 | 600 | Screen/collection heading |
| `title` | 22 / 34 | 600 | Section heading / now-playing title |
| `body` | 17 / 28 | 400 | Descriptions, settings, explanatory text |
| `rowTitle` | 16 / 26 | 500 | Song/episode title |
| `action` | 15 / 24 | 600 | Buttons and compact action labels |
| `label` | 14 / 22 | 500 | Secondary label, navigation, status |
| `duration` | 14 / 22 | 400 | Time/count; tabular digits only if font feature verified |

- No all-caps UI labels, forced Latin letter-spacing in Arabic, artificial kashida, or disabling contextual shaping/ligatures.
- Keep Arabic diacritics and descenders visible; test line-height and clipping instead of forcing Latin ascent/descent metrics.
- Use full locale-sensitive dates/counts and localized plural forms; Arabic needs zero/one/two/few/many/other, not an English singular/plural branch.
- Metadata stays in its authored language; do not silently translate artist/song titles.
- Use system fallback for unsupported scripts/emoji when needed and verify the result on each renderer. Do not assume the tested font covers every language in user media.
- At 200% text, grow rows and wrap actions. Remove/reduce artwork and decorative space before hiding labels or shrinking fonts. Full title remains available on the detail/player screen and to assistive technology; no mandatory marquee.
- `sp` in shared code is not proof that every platform's accessibility text preference is honored. Verify Android font scale, iOS Dynamic Type integration, and desktop text scaling separately. Provide an explicit 100/125/150/200% app text-size setting if a platform bridge is absent, without claiming it replaces full native accessibility integration.

## 5. Geometry, rhythm, and adaptive layout

| Category | Proposed tokens |
| --- | --- |
| Spacing | `4, 8, 12, 16, 24, 32, 48, 64` dp; 4dp base rhythm |
| Content insets | 16dp compact, 24dp medium, 32dp expanded; native safe/window insets in addition |
| Radii | 8dp small controls, 12dp row/artwork, 20dp cards/player sheet; capsule only for chips |
| Targets | Minimum 48×48dp for shared interactive areas; central play 64×64dp; verify physical platform mapping |
| Rows | Minimum 64dp music / 88dp episode; content-driven height, never fixed maximum |
| Artwork | Square; 48dp row, 144–224dp collection tile, at most ~360dp phone full player |
| Mini-player | Minimum 72dp compact / 88dp desktop, grows for text; dedicated safe layout region |
| Rail / sidebar | 80dp compact rail; 224–280dp expanded sidebar |
| Supporting pane | 288–360dp queue; user-hideable; collapse before main content becomes cramped |
| Reading width | Description/transcript ideally 45–75 characters per line; max content width around 1120dp for central lists |
| Motion | 140–220ms small state changes; ~240ms pane change; no essential timing-dependent action |

Corner shape is hierarchy, not decoration: rows may be flat; avoid every text group becoming a rounded card. Use thin decorative dividers rather than heavy elevation. Keyboard focus must not be clipped by rounded card bounds.

### Breakpoint contract

Measure the actual available window in logical units after relevant insets [S1]. A resized desktop window may be compact; an unfolded device may be expanded. Thresholds are defaults; content or large text can require collapsing earlier.

| Available width | Navigation | Content / player behavior |
| --- | --- | --- |
| `<600dp` compact | Bottom Listen / Search / Podcasts / Library, text labels visible | One content pane; mini-player immediately above navigation; full player is a dismissible full-screen surface. Queue is a dedicated sheet/screen, not a squeezed side pane. If scaled labels no longer fit, use a clearly labeled navigation menu/drawer rather than clipping labels or shrinking type. |
| `600–839dp` medium | 80dp leading rail | Single wide pane or well-fitting collection grid; no mandatory queue pane. Landscape player can use compact art next to controls. |
| `840–1199dp` expanded | Leading rail, optionally expanded sidebar if it fits | Main pane plus optional detail/queue only when each remains usable. Queue may overlay rather than force unreadable width. Persistent player spans safe content width. |
| `1200–1599dp` large | 224–280dp leading sidebar | Center content plus optional 288–360dp trailing queue; width budget must preserve a usable main pane. Player spans bottom; no duplicate active transport. |
| `>=1600dp` extra-large | Same as large | Cap reading width, increase grid columns only while tiles remain comfortable; do not stretch a description across the display. |

Height `<480dp`: compact-height policy overrides decorative decisions. Reduce or remove hero art, use a short scrolling header, and retain immediately accessible playback controls. Do not let bottom bars consume all content height. On the full player at large text, the complete surface scrolls with controls reachable; fixed controls must not obscure focused content.

Do not hardcode a huge desktop minimum window size merely to hide layout failures. Verify 320dp-equivalent content width as a stress case. Native title bars/window controls stay native; no drawn macOS traffic lights on Linux. Recompute layout while retaining destination, selection, scroll anchor, and player state. No device-orientation lock.

## 6. Screen concepts and state contract

### Shared state model

Avoid a single `isLoading` flag. Catalog availability, connectivity, playback and durable downloads are orthogonal. A network error must not erase a local library or stop an unrelated local track.

| Domain | States and visible obligations |
| --- | --- |
| Catalog | Initial loading → ready; ready may be cached/stale; empty with reason; partial/error with scoped retry. Skeletons have one accessible loading status, not dozens of focusable placeholders. Preserve usable previous results on refresh failure. |
| Connectivity | Unknown, reachable, unavailable, restricted as supported by the actual platform. A failed request alone warrants “Couldn't connect,” not a fabricated definitive device-offline diagnosis. No blocking full-screen offline modal. |
| Playback | Idle, resolving, buffering, playing, paused, ended, error. Display actual engine state; never animate a fake elapsed timer to imply audio. During pending play/buffering allow cancellation/pause; do not replace the action with an untappable spinner. |
| Download | Not downloaded, queued, waiting for permitted network, downloading (known/unknown size), paused if supported, verifying, downloaded, failed, removing. “Downloaded” requires a persisted readable local file, not a tap or a 100% transfer event. |
| Empty library | “Your library starts here” with Search and Import/Open local media only where supported; no fake populated library. |
| Empty queue | Explain that the currently playing item can continue; offer discovery/library, not forced autoplay. |
| Provider failure | Name the affected source generically, preserve other results, offer relevant retry/setup. Never display secrets, raw URLs with credentials, or terminal errors. |

Download percentages must use measured bytes when total is known; otherwise show indeterminate progress and downloaded bytes. Status announcements occur at meaningful transitions, not every byte or playback second. If free space/permission/rights prevents download, state the actual reason and a feasible action. Cached artwork or metadata must not earn an offline badge. A missing formerly-downloaded file becomes “File unavailable” with retry/relink where supported, not silent deletion of the saved item.

### Listen

- Top title and optional media filters (All / Music / Podcasts); no carousel that requires swiping to discover basic functions.
- **Continue listening** uses actual history/position. Show type, remaining podcast time, and a direct Resume action. Omit rather than invent history.
- A compact **Ready offline** collection appears when applicable; offline messaging links directly to Downloads.
- Discovery sections reflect actual catalog/source capability. Prototype/local samples are clearly labeled as samples; do not claim personalization or licensed commercial catalog availability.
- Loading, stale cached, empty, offline-with-content, offline-without-content, and per-section failure must each have an intentional composition.

### Podcasts (cross-review addition)

- A distinct long-form hub: Continue listening, New episodes, Following, and Add RSS feed. Follow/New/In progress are not renamed music favorites.
- Listen remains cross-media recency plus primarily music discovery. Podcasts focuses show/episode tasks; global Search retains Music/Podcasts filters and is the search authority rather than creating conflicting independent queries.
- Library remains the unified place for saved items, playlists, and downloads, with a Podcasts filter. Following a show does not secretly download its archive or subscribe to an account.
- Browsing this destination never switches the audible session. Show actual-active-mode identity in the mini-player while a different mode is viewed.

### Search

- Persistent label, clear control, correct input direction, query preserved across navigation. Never use placeholder text as the only label.
- Separate Local library results and Online results where this distinction matters. Offline local search still works; online section describes its limitation without suppressing local hits.
- Debounce remote queries and ignore stale results using query/source/filter identity. New results do not steal text-field focus.
- Distinct initial suggestions, searching, results, no results, partial failure, source setup needed, and offline states.
- Search cards lead to details. A result's playback action must not ambiguously claim to play if the source only supplies metadata.
- Metadata success and playback availability are independent. An observed source restriction (for example, a provider requiring sign-in or returning no supported formats) is not “Offline” or “No results.” Explain the capability beside the affected action; never silently substitute an open-music provider under the original source name.

### Library and Downloads

- Library subviews: Saved, Playlists, Downloads; filter Saved by All / Music / Podcasts. Preserve filter/scroll position when returning.
- Music row primary action plays the track only when actually playable; a distinct sibling More control exposes details and secondary actions. Avoid a nested button inside another semantic button.
- Podcast episode title opens details; explicit Play/Resume button makes continuation intent clear. Show played/progress/downloaded status with text/shape as well as color.
- Collection card opens its detail; do not make a tiny hover overlay the only Play command.
- Downloads subview includes All / In progress / Completed filters, measured storage where available, per-item actions and useful failure reasons. “Saved to Library” and “Downloaded” remain separate states and actions.
- Confirm destructive local-file deletion with item/count and impact. Do not offer pretend Undo after irreversible deletion. A reversible library removal may offer Undo with an accessibility-appropriate timeout and a durable recovery route if implemented.
- An unavailable saved item remains visible with a reason; do not silently play an unrelated replacement.

### Collection / episode detail

- Artwork, heading, author/artist, explicit source/type, primary action, download action when legally and technically supported, and a text summary.
- For a podcast: duration, publication date, episode-level progress, optional chapter list, transcript availability when provided. Do not advertise a transcript/lyrics button as working when unavailable.
- Long descriptions use their content language's paragraph direction and comfortable line length. Links and imported feed URLs are safe, visibly identifiable, and bidi-isolated.
- Full names and error explanations wrap; no rigid hero height. Selecting or reading a detail does not replace the active queue.

### Mini-player / Now playing

- Mini-player: leading art, title/subtitle, actual play/pause state, accessible open-player action; Queue available directly when width permits and otherwise in a plainly discoverable labeled action. On very narrow widths preserve Play/Pause and Open player before optional skip controls.
- Full player: no duplicate keyboard-focusable mini-player behind a modal surface. Dismiss/back button always available; swiping is optional. Return focus to the opener.
- Music transport: Previous / Play-Pause / Next, plus explicit Shuffle and Repeat states where implemented. Podcast transport: Back 15 seconds / Play-Pause / Forward 30 seconds, with speed and sleep timer where engine support exists.
- Playback seek is an LTR timeline in both locales; elapsed and remaining/duration labels are isolated numeric runs. Provide semantic value and step actions; no waveform-only representation.
- A seekable item exposes tap-to-seek plus keyboard/screen-reader adjustment. A live/non-seekable item says Live / Not seekable and does not pretend that a slider works. Unknown duration is not `0:00` masquerading as a known duration.
- Use language-aware spoken durations (“1 minute, 5 seconds”), not a screen reader spelling a colon or minus sign. Rate value such as `1.25×` is isolated and has a localized accessible name.
- Cover art never determines text contrast. Long titles wrap; lyrics/transcript auto-scroll is user-controllable and optional. Reduce-motion turns nonessential animations off; no perpetual animated equalizer is required to identify Playing.
- Errors preserve context and offer Retry/Choose another item; do not silently restart from zero or switch to a different source without a disclosed policy.
- Two preserved queue contexts (Music and Podcasts) still share one audible player. The mini-player follows the actually active context, never merely the visible tab/queue.
- A real-elapsed-duration sleep timer, if it survives switching to Music, remains visibly active and cancellable in the Music mini/full player too. An episode-end timer cancels with explicit feedback when changing its bound episode. A hidden cross-context timer must not unexpectedly pause music.

### Queue

- Current item distinct from Upcoming. “Play next” inserts immediately after current; “Add to queue” appends. Persistence/recovery policy belongs to the playback design, not an undocumented visual trick.
- Reordering offers Move up / Move down / Move to position controls in addition to optional drag handles. Keyboard support alone is not a sufficient touch-pointer alternative [S9].
- Clear upcoming must not silently stop current playback; name the action precisely. Current-item removal needs an explicitly named stop/remove behavior, rather than applying an ambiguous Upcoming Remove action.
- Stable item identifiers preserve focus through reorder and async updates. Announce the result once, such as “Moved to position 3,” then retain focus on the moved item.
- Large screens may dock this pane at the trailing edge; Arabic puts that edge on the left. A modal compact queue traps focus only while open and restores it on dismissal.
- Header names the **viewed** Music/Podcast queue; a separate marker names the **active/audible** context. Opening either queue is inspection only. Play/Resume is the deliberate transition, preserving the outgoing context's position/order/modes.

### Settings

- Language: System / English / العربية; theme: System / Light / Dark; accessible text size if needed; network/download/storage policy only for working capabilities; About and licenses.
- Locale/theme changes preserve playback and current task. Never require app restart merely to apply colors unless a platform limitation is explicitly recorded.
- Destructive actions are separated, plainly named, and confirmed. Permission is requested at the moment of need, with an explanation and denial recovery rather than a startup permission wall.

## 7. Arabic, bidi, and localization contract

### Layout direction is not text direction

1. Root layout uses selected app locale (`ar` → RTL, `en` → LTR), including navigation semantics. Use `start/end` padding/alignment/arrangements, not `left/right` with separate manual reversals.
2. Keep logical source order stable. Let layout direction position leading/trailing elements; do not reverse the list again and produce double mirroring.
3. Short row labels/title blocks align consistently to the row's leading edge, even for foreign-script titles [S4]. Each text node determines its internal base direction from content or a known field policy.
4. Multi-line paragraphs/descriptions use the language/first-strong direction of their own content rather than forcing Arabic direction on English prose.
5. Isolate unknown interpolated metadata with **FSI U+2068 … PDI U+2069**, or the renderer's equivalent bidi isolation. Use LRI/RLI when direction is explicitly known. Do this at presentation boundaries; do not persist these formatting wrappers into canonical metadata.
6. Prefer distinct text nodes for title, artist, duration and status over concatenating raw metadata with ambiguous separators. For unavoidable localized sentences, use a localized format resource and isolate each inserted run. Android `BidiFormatter` is platform-specific; do not leak it directly into `commonMain` [S11, S12].
7. IDs, URLs, file paths, ASCII code strings and technical error identifiers use an isolated LTR run. Display readable localized error prose separately. Do not reverse character arrays, numeral order, or path separators.
8. Imported metadata may itself contain directional controls. Preserve legitimate script content; guard rendering/security-sensitive paths against bidi spoofing and test nested isolates. Never claim blanket removal of all Arabic shaping/format controls is safe.
9. Keep search query in logical order; preserve Arabic letters, diacritics and user input. Any diacritic/orthographic normalization for retrieval belongs in a separate search key, not destructive display mutation. Native editing, selection, cursor movement and copy/paste need manual tests.
10. Localize quantity/date formatting using the actual locale. Arabic numerals differ by region/user preference; do not assume all Arabic users require Eastern digits. Numeric time runs remain internally ordered LTR regardless of digit glyph choice.

### Mirroring matrix

| Element | Arabic behavior | Reason |
| --- | --- | --- |
| Leading nav / Back / breadcrumb / drill-in chevron | Mirror placement and directional icon | Navigation follows reading order. |
| Row artwork, text, trailing actions | Mirror positions, not artwork pixels | Preserve familiar leading/trailing relationship. |
| Queue supporting pane | Move to trailing left edge | Part of outer app layout. |
| Media Previous / Play / Next | **Do not mirror glyphs or transport order** | Playback-direction exception [S5, S6]. |
| Podcast rewind/forward arcs and their 15/30 numerals | **Do not mirror**; localize accessible labels/numeral presentation deliberately | Actions refer to media time, not screen navigation. |
| Playback seek/time labels | **LTR island**; start at left, increase right | Same playback exception. Do not use a generic RTL slider accidentally. |
| Generic download/onboarding progress | Follow native RTL semantic progression where appropriate | Not a media timeline; never infer its policy from the player. |
| Volume/value slider | Follow native localized value progression; test labels and keyboard together | Do not force the entire settings UI into the media LTR island. |
| Album artwork / logos / clocks / checkmarks | Do not mirror pixels | Content and universal/object semantics. |
| Text-alignment / text-direction icon | Localized direction-aware variant | Represents text flow itself. |

The media island's visual direction must not accidentally make Arabic button names or titles LTR. Limit the override to transport positioning/seek behavior, and let text retain correct bidi handling. Native iOS wrappers should use playback semantic content where applicable; shared Compose must implement/test the same contract rather than assuming the platform does it automatically.

### Provisional Arabic vocabulary (native review required)

| English | Arabic proposal |
| --- | --- |
| Listen | استمع |
| Search | بحث |
| Library | المكتبة |
| Music | موسيقى |
| Podcasts | بودكاست |
| Downloads | التنزيلات |
| Now playing | قيد التشغيل |
| Play | تشغيل |
| Pause | إيقاف مؤقت |
| Resume | متابعة الاستماع |
| Play next | تشغيل تاليًا |
| Add to queue | إضافة إلى قائمة الانتظار |
| Ready offline | متاح دون اتصال |
| Retry | إعادة المحاولة |

These are design copy candidates, not certified translations. Avoid unreviewed transliteration of “queue” in user-facing text. A native Arabic reviewer must check idiom, context, pluralization, brand pronunciation and screen-reader pronunciation.

### Required mixed-direction fixtures

Treat these as logical Unicode strings, not manually visual-reordered snapshots:

- `ڤيرا` and `Veyra` in both UI locales.
- `ليلة هادئة — Live 2026 (Remastered)`.
- `Episode 12: كيف نسمع؟` and `الحلقة ١٢: Design Systems`.
- `أم كلثوم / Umm Kulthum` and `Björk — Jóga`.
- `Mix (نسخة حيّة) [2026]` with a duration `01:23:45` adjacent.
- Arabic title ending in punctuation followed by an English artist and numeral count.
- Only digits/emoji, leading punctuation, no strong-direction character, and empty metadata.
- Diacritics/shadda, the brand's ڤ, long Arabic joined runs, Arabic-Indic and Western digits, parentheses and brackets.
- A feed URL and Windows-style file path inside an Arabic explanatory sentence.
- Explicit directional control characters and nested isolates in untrusted metadata.

Verify visual glyph order, punctuation attachment, truncation boundaries, cursor movement, copy/paste logical order, screen-reader name, and list focus order. A screenshot alone cannot prove bidi input correctness.

## 8. Accessibility and desktop interaction acceptance contract

### Semantics and assistive technology

- Use real Button, Slider, Tab, Switch, Dialog and heading semantics. Set role, localized accessible name, checked/selected state, range/value, state description and actions where appropriate.
- Do not announce decorative artwork/placeholder geometry if title/artist already communicates identity. Meaningful images receive useful alternatives; do not repeat “album art” on every row.
- Merge a row's descriptive text sensibly but keep distinct interactive commands independently discoverable; never hide More/Download inside an over-merged parent.
- Playback progress does not announce every second. Track changes, actual playback errors and download completion are polite, concise status events; avoid repeated recomposition announcements.
- Loading placeholders are not focusable. Async updates preserve focus. Disabled commands explain the limitation in reachable text, not only in an unavailable tooltip.
- All essential information has a visual/text channel; no sound-only error/success cue. Transcripts are shown when legitimately available; absence is acknowledged, not fabricated.
- Respect reduce-motion, high-contrast and text preferences. Native Android/iOS/desktop semantic bridges must be tested; a shared Compose semantics tree is not proof of VoiceOver/Narrator/Orca interoperability.

### Keyboard and focus

- Every action is reachable and operable with a keyboard; Tab/Shift+Tab sequence follows logical reading flow. In RTL, visual placement and logical focus order must be audited together.
- Enter/Space activate focused buttons according to platform conventions. A focused text field keeps editing shortcuts and Space; a focused slider keeps its arrow/range commands.
- Visible focus is distinct from selection and hover. Bring the focused item fully into view, including its indicator; the persistent player must not cover it [S10]. This intentionally exceeds the WCAG 2.4.11 minimum of “not entirely hidden.”
- Modal dialog/sheet focus enters predictably, remains within the modal, supports Escape or a visible close button, and restores to its opener. Non-modal supporting panes do not trap focus.
- Menus support keyboard opening, arrow navigation, dismissal, and a touch-accessible equivalent. Queue manipulation is not drag-only or long-press-only.
- Desktop long lists should support a tested composite/roving-row interaction: Up/Down changes active row, Enter performs the documented primary action, Menu/Shift+F10 opens actions, Tab enters/exits the group. Do not improvise custom traversal that removes rows from screen-reader access. Small first increments may use straightforward focusable controls until the full composite is verified.
- Minimal initial app shortcuts: Ctrl/Cmd+K to Search; Escape to close the top dismissible surface; hardware media commands through the platform media integration. Optional global Space playback applies only outside text editing, modal interactions and focused controls that already own Space. No double-handling native media keys.
- Document shortcuts in a reachable help/menu; do not override OS/assistive-technology shortcuts. Add next/previous/volume global bindings only after per-platform conflict testing.
- Hover affordances are supplementary. Primary playback, queue and download actions remain discoverable on touch and keyboard without hover.

### Sizing, contrast, and scaling

- Shared target aim: 48×48dp or larger, non-overlapping even when visual icons are 24dp. Verify rendered logical sizes on every platform. Desktop does not get tiny controls simply because a mouse exists.
- Text contrast at least 4.5:1 for ordinary text; at least 3:1 for large text and meaningful graphical/control information, using the correct size/weight definition. Veyra's listed text pairs exceed 4.5 rather than relying on large-text exceptions [S8].
- 200% text: no clipped Arabic marks, inaccessible labels, control overlap, lost action, or horizontal page scrolling. Card summaries may truncate only when full content remains discoverable; full detail/player names wrap.
- Test 320dp-equivalent width and desktop 400% effective zoom/reflow as a stress condition. Do not equate CSS pixels, Compose dp and Apple points without checking platform scaling.
- Provide non-drag single-pointer alternatives for seek/reorder [S9]. Keyboard support is an additional requirement, not a substitute for this one.
- Timing of transient feedback uses platform accessibility timeout preferences where supported; important failure/recovery actions remain available outside a vanishing toast.
- Reduced motion disables nonessential movement, shimmering, parallax and autonomous scrolling. No flashing content; no animated background needed for playing state.

## 9. Validation plan and exit gates

### Automated / reproducible checks planned

1. Unit-test semantic color pairs with the same luminance formula; include every container/foreground combination and focus/selection state actually used. Keep decorative `outlineSubtle` excluded from essential roles by component contract.
2. Resource parity check for en/ar, missing keys, placeholder parity and plural categories; test localized duration/number examples. Ensure only intentional provider metadata remains untranslated.
3. Unit-test bidi presentation wrappers: balanced isolates, no mutation of canonical metadata, stable logical string round-trip, field-specific LTR handling.
4. UI tests for real role/name/action semantics, modal focus restoration, playback-intent controls, queue reorder alternatives, and offline/download state transitions. Screenshot tests alone are insufficient.
5. Screenshot/layout fixtures at widths 320, 360, 599, 600, 839, 840, 1199, 1200, 1599, 1600 logical units; sample heights 360, 479, 480, 800; both locales, both themes, 100/150/200% text. Reduce combinations intelligently but keep every boundary and worst-case string.
6. Verify available font assets, license files, locked provenance and SHA-256. Check glyph mapping **and** rendered shaping for all three shipped weights; coverage of Regular alone is insufficient.
7. Test no fake “Downloaded” after failed write, revoked access, missing file, incomplete transfer or restart. Test no fake Playing on resolve failure. Source errors remain local to the source.

### Manual platform matrix required before release claims

| Platform | Required sessions |
| --- | --- |
| Android | TalkBack en/ar, large font + display size, touch exploration, Switch Access where feasible, keyboard, RTL developer stress mode and real Arabic locale, portrait/landscape, system bars/cutouts |
| iOS | VoiceOver en/ar, Dynamic Type through accessibility sizes, Full Keyboard Access, Reduce Motion, Increase Contrast, safe areas, native playback/control-center coherence |
| Windows | Narrator, keyboard only, high contrast / contrast themes, 125/150/200% display scaling, resize, focus recovery, native window controls |
| macOS | VoiceOver, keyboard navigation/Full Keyboard Access, text scaling, Reduce Motion/Increase Contrast, native title/menu/shortcut behavior |
| Linux | Orca + actual chosen desktop/accessibility bridge, keyboard, scaling, window manager behavior; report unsupported bridge paths explicitly rather than inferring parity from Windows |

Every platform session includes: navigate to media; start/pause/resume; seek where supported; open/dismiss player; enqueue/reorder/remove upcoming; search mixed-script metadata; make a download only if enabled; airplane/offline/local-file playback; change theme/language without losing playback; recover from a real failed operation. Native Arabic review must include shaping, bidi punctuation, numeral preference, labels, pluralization, and spoken output.

### Small formative evaluation

Before polishing large numbers of screens, test a focused prototype with Arabic readers and people using keyboard/screen readers. Suggested tasks: resume a podcast, distinguish Save from Download, play an already-downloaded item while offline, add a track to upcoming, return from full player, and interpret a recoverable failure. Record observed difficulty and task outcomes; do not invent a usability score or claim accessibility from visual taste. Participant recruiting/consent and any external sharing need owner approval; do not share private files or media.

### Release gates

- No missing core action in en/ar, no mirrored play/seek direction, no clipped Arabic marks at tested scale.
- Keyboard-only completion of all implemented tasks; no focus trap/hidden focus; modal focus restoration verified.
- Verified primary text/control contrast in actual UI states, not merely the base palette.
- Screen-reader names/actions correct on each platform whose accessibility is claimed. An untested or unsupported bridge is documented as such.
- Offline/download/playback claims backed by real file/engine state and restart/recovery tests.
- Bundled fonts/artwork/icons carry permissible provenance and required notices.
- Every unsupported capability is omitted, disabled with an honest explanation, or clearly identified as a nonfunctional design preview — never “passed” by a mock.

## 10. Alternatives, risks, and decisions to revisit

| Alternative / risk | Assessment / mitigation |
| --- | --- |
| Platform-native UI everywhere | Strong native conventions; much more UI duplication and risk of inconsistent cross-platform bilingual behavior. Prefer shared visual system plus genuine platform adapters, and verify actual accessibility bridges. |
| Unmodified stock Material everywhere | Fast and well-understood on Android; can look foreign on desktop/iOS and misses original brand. Reuse accessible primitives, not a phone layout stretched to desktop. |
| Always-visible three-pane desktop shell | Efficient on wide displays; cramped at 840–1199dp or large text. Make queue optional and width-budgeted. |
| Pure black/neon-green music identity | High contrast but derivative and visually aggressive. Petrol/sea-glass with sand accents remains recognizably original without depending on artwork color. |
| Manrope + Arabic fallback | More distinctive Latin silhouette but additional mixed-run metrics and rendering variability. Begin with the verified bilingual Plex family; change only after comparison in actual renderers. |
| Global RTL mirroring | Breaks album art, media transport, numbers, URLs, and mixed-script names. Use the explicit mirroring matrix and targeted overrides. |
| Treat every Arabic label as forced RTL | Breaks embedded Latin metadata, paragraphs and editing. Separate outer layout direction from content direction and isolation. |
| Read `44pt minimum` from old Apple summaries | Current inspected HIG distinguishes default/minimum and platforms. Cite accurately and enforce Veyra's own larger target. |
| Shared Compose semantics assumed equal across targets | Android success does not attest iOS/desktop/Linux bridges. Platform assistive-technology testing is a release gate, not an optional polish task. |
| Transcript/lyrics shown as if always available | Availability, licensing and accuracy differ by source. Provide only real authorized text; state absence honestly. |
| Mark/font originality or trademark assumed cleared | OFL covers font use, not brand clearance; a proposed original vector mark still needs name/mark review before commercial release. |
| Prototype looks complete while engine is missing | Expose implementation status and use explicit samples. Visual polish must not imply real playback/download/provider integration. |

### Cross-review: concrete challenges, resolutions, tests

1. **Hidden podcast tasks versus duplicated destinations.** Music initially proposed three destinations, while podcast research specifies distinct Follow/New/Continue/Add-RSS tasks. Resolution proposed to the lead: four labeled destinations with the non-overlapping responsibilities above; retain global Search and unified Library. Test fresh Arabic/English users finding/following a show, resuming offline, then returning to music; preserve state/back behavior. Layout-check 320 logical units and 200% text; if four labels cannot fit, activate an accessible menu/drawer rather than hiding their meaning. No preference vote or competitor popularity substitutes for the task test.
2. **Visible mode mistaken for audible mode.** Two queue contexts are valuable but create a specific ambiguity. Resolution: identify viewed and active contexts separately; tab/queue inspection is side-effect-free; only Play/Resume switches the one audible player. Test playing music → inspect podcast hub/queue → resume an episode → return to music, asserting preserved pending order, progress, mode-specific repeat/shuffle and exactly one audible stream. Include a duration timer across the switch; it remains visible/cancellable or is explicitly cancelled by a chosen policy, never silently hidden.
3. **Metadata success mistaken for usable audio / errors mistaken for empty data.** Integration observed successful YouTube metadata with source-side playback restriction and zero formats. Resolution: separate per-item capabilities from search/catalog success, and translate typed source/connectivity/format/storage/feed/file errors centrally. Keep local search/favorites/downloads visible when a remote source fails. Test metadata-ready plus source-restricted, true empty, timeout, malformed feed, missing enclosure, permission denied, storage full, missing file and unsupported format in en/ar. No raw exception, credential-bearing URL, fabricated Ready state, or silent alternate-provider substitution.

### Proposed shared UI contract for implementation handoff

Final module/package names are architecture-owned. The intended surface is deliberately small:

```kotlin
// Uses the shared domain LanguagePreference; the shell resolves SYSTEM first.
class AppStrings(language: LanguagePreference, val arabicIndicDigits: Boolean = false) {
    fun text(key: TextKey): String
    fun format(key: TextKey, vararg arguments: String): String
    fun quantity(key: QuantityKey, count: Long): String
    fun mediaTime(milliseconds: Long?): String
    fun spokenDuration(milliseconds: Long?): String
    fun byteSize(bytes: Long): String
    fun errorFor(failure: AppFailure): String
}
// LocalStrings provides the resolved AppStrings to composables.
// App code does not persist the instance or its rendered messages.
@Composable
fun VeyraTheme(darkTheme: Boolean, language: LanguagePreference, content: @Composable () -> Unit)
// VeyraIcons exposes one consistent ImageVector family.
```

`TextKey` covers navigation, playback controls/states, music library/playlist actions, podcast follow/progress/queue/timer actions, all download phases, empty states, settings/licenses, accessibility names, and recoverable errors. Typed domain errors map into keys at the UI boundary: Offline/Connection, Timeout, RateLimited, SourceRestricted, SourceUnavailable, InvalidFeed, UnsupportedEnclosure/Format, PermissionDenied, StorageFull, FileMissing/Corrupt, DownloadInterrupted, SeekUnavailable, plus a safe unknown fallback. Exact enum names should match the agreed domain model, not create duplicate error taxonomies. Cancellation is normally a user-requested state, not a scary error toast.

Every key has en/ar content. Formatted metadata arguments are bidi-isolated; quantity/duration formatting is a dedicated locale-aware operation, not English string concatenation. UI errors never accept arbitrary backend prose as already-localized safe text. UI agents request new keys rather than embedding independent translations. Parity/placeholder tests and a scan for user-facing literals accompany integration. These interfaces are now authored, but compilation and native-language/rendering review remain separate checks.

### Implementation handoff update — 2026-11-06

- `ui/design` contains the bilingual typed catalogue, actual opaque theme tokens, original code-native icons and shared components. `VeyraIconButton` provides a 48dp minimum, selected/state semantics, localized tooltip/name and visible focus border. `MediaDirection` is only for transport/seek geometry, not queue navigation or an entire Arabic screen.
- Catalogue copy separates original metadata/title/duration/licence from the exact requested/resolved audio receipt. A matching title is **not** a verified recording match. Current audio, downloaded audio and previous search results have distinct labels; an unrelated newer search receipt must not stand in for the audible/downloaded one. Listing rights do not automatically cover first-result audio.
- Podcast historical-position notices now distinguish a known changed recording (this recording starts from the beginning) from an unverified selection (resume is conditional on matching the prior recording). Both en/ar keys include the separately preserved historical position and accept visual media time or spoken duration. Two additional Kotlin interpolation/bidi/argument-count tests are authored, **not yet executed**; these resources do not themselves prove the playback/progress-compatibility policy.
- Crossfade copy covers persisted enable/duration/headroom controls and Preparing/Ready/Overlapping states. Only the native coordinator's actual `OVERLAPPING` state may be labeled active. Podcast behavior must remain unchanged; no source search, UI animation, null-sink probe or automated test substitutes for mandatory physical audible-output verification.
- Root-delegated `ui/settings` now contains a responsive, wrapping settings screen and typed `SettingsAction` intents. Actions apply one field to the latest Room settings inside its transaction, preserving unrelated fields and show-specific speed preferences; text-size step buttons use relative intents rather than stale slider snapshots. Controls cover native-gated crossfade/2–5-second presets/0.1–30-second custom duration, podcast defaults/skips, language/theme/75–200% text size, offline mode, parallelism/storage and real root navigation callbacks. Choice buttons reserve space for a separate 2dp keyboard-focus ring, distinguishable even on an already-selected choice without layout movement; their inner targets remain at least 48dp. Constant -3.5 dB music headroom is explained, not presented as an adjustable or transition-only control. These controls still require actual root/native application and rendered interaction tests.
- Playback-tools banner keys distinguish preparing/verifying/downloading/installing software from acquiring audio. Required-state copy explains first online playback/download setup, no offline-startup tool fetch, published release sources and internet-data use. Exact optional byte totals and Node.js/Chromium/FFmpeg names have bilingual resource coverage. Copy does not claim every third-party tool binary is an official upstream build or that setup success establishes playable media.
- Legal copy explains that bundled licences/notices remain in their original language. Explicit recovery copy warns that restoring the last valid backup returns the library to that earlier state, may omit later changes, retains damaged files and does not reset the library. The prepare-tools action has a separate imperative label from its in-progress status. These labels do not establish that legal bundling or recovery execution passed.
- Cross-review of the new shared recovery UI found that mobile hosts originally showed it only after initialization had succeeded, making corrupt-store recovery unreachable. Native owners now distinguish constructed-container **availability** from successful initialization, keep the shared failed-state UI mounted, and reject OS playback commands until ready rather than replaying an old Play after recovery. The Android worker observes each current initialization attempt instead of a permanently failed deferred. Source review supports this integration direction; startup/recovery interaction execution remains pending.
- `Artwork` prefers a supplied persisted path. `LocalRemoteArtworkAllowed` defaults to false: production UI never passes a network URL or potentially cloud-backed `content://` provider to Coil, even online. The owned metadata artwork cache supplies app-local files; isolated tests may explicitly opt into remote models, but offline mode still overrides that opt-in. Network file authorities/UNC paths and encoded equivalents are rejected. The UI helper cannot detect network-mounted filesystem directories: the storage owner must supply only managed local artwork. Missing art remains a neutral real-title initial/original mark, never a fabricated cover.
- Font manifest and unchanged OFL live in `composeResources/files/licenses`. All three weights are pinned to Google Fonts revision `702964cf46bd1bf33d0745d24bbaac33edf8b8af`; the manifest records original names, exact URLs, byte sizes and SHA-256. No runtime font download is needed.
- **Executed:** `python3 -B scripts/verify-design-resources.py` passed a read-only, stdlib-only static audit (495 bilingual keys and references across 117 Kotlin source files at the latest check). It checks en/ar key/reference/placeholder consistency; 37 text pairs and 15 essential/focus pairs per theme, including selected queue rows (minimum text light **5.103:1**, dark **5.095:1**; essential light **3.385:1**, dark **3.056:1**); all three font hashes/sizes/pins; 171 required Unicode cmap characters in each weight; Arabic GSUB/GPOS script/feature presence; unchanged OFL. New error-container foreground pairs measure **10.489:1** light and **10.066:1** dark. Table/feature presence is **not a shaping or legibility test**.
- **Executed:** `python3 -B scripts/tests/test_design_resources.py` passed **49/49 tests**, zero failures/errors/skips. Disposable negative fixtures under `.work/` cover missing/duplicate keys and malformed placeholders, unsafe interpolation/bidi controls, missing references/raw templates, low contrast/alpha, font hash/size/pin/path/symlink/weight/coverage/layout faults, truncated OFL, unmanifested fonts and theme mismatches. A current-catalogue regression preserves both recording-conditional podcast notices and their position placeholders. CLI tests verify failure exit/aggregation and a read-only repeat from another directory. No fonts are installed, no network is requested, and no Kotlin build is run by this suite.
- Both static commands were rerun after adding the podcast notices and before the pending third GUI batch; exit-zero logs, 134 checked input hashes and their unchanged pre/post comparison are preserved in `.work/design-review-evidence/podcast-recording-notes-fenbnpva/` (49 tests, 0.470s). The earlier 48-test/493-key rerun remains in `.work/design-review-evidence/pre-ui3-static-fvdl57ru/`. Neither run executes the pending Kotlin UI repairs or new Kotlin formatting tests.
- **Executed and independently inspected:** all **35/35** common Kotlin design/settings tests passed in the authorized first combined desktop batch. Design's 26 tests cover catalogue/interpolation/privacy, Arabic cardinal categories/digits, duration/byte formatting, no-network/default-offline artwork policies, actual color pairs/minimum target and directional icons. Nine settings tests cover single-field/latest-state application, rapid steps/bounds, exact decimal Arabic/Latin input and native speed-range choices. Preserved XML is under `.work/music-ui-evidence/first/`; the full log is `.work/music-ui-tests-first.log`. The enclosing batch reports **196 tests, 127 passed, 69 failed, zero errors/skips**, not a full-suite success.
- **Historical first-run UI initialization failure:** eleven desktop settings UI tests cover capability gating, persisted-field intents, decimal/error/Done behavior, custom defaults, nondrag text controls, navigation callbacks, focused-button keyboard activation and Arabic 320dp/200% **unclipped** control bounds (clipped semantics bounds alone can hide overflow). These fixtures create no source, database, player or runtime installer. All eleven failed at X11/AWT toolkit initialization in the guarded first run, before layout/interaction checks; this was not evidence of eleven application layout defects. The successful second-run execution is recorded below.
- **Compiled; UI initialization failed before test bodies:** nine additional `RecoveryAndRuntimeUiTest` cases reuse the actual root/controller/Room fixture with a no-network engine and controlled software-runtime state. They cover passive/offline-disabled preparation, determinate/unknown progress, typed Arabic errors, explicit confirmed recovery versus Cancel, absent-backup/future-schema refusal, actual bundled offline legal documents and one independently unavailable document through the real legal screen's reader seam. Recovery corrupts only a newly seeded task-owned Room database and checks preserved damaged bytes/library contents. The tests use Compose 1.9.3's public `runDesktopComposeUiTest(width = 320, height = 760)`: a 320dp inner Box inside the default 1024px test scene would not actually constrain separate modal layers. Assertions compare unclipped control/modal bounds and text-layout overflow at 200% Arabic. All nine failed during the same guarded X11/AWT initialization; no PNG was generated. These are not native installer, media, screen-reader or physical-output claims. Of **55** authored design/settings/recovery Kotlin cases, **35 passed and 20 report environment-initialization failures** at this checkpoint; rendering, focus/keyboard/large-text journeys and native accessibility remain unverified.

### Second guarded desktop UI checkpoint — actual rendering, not full-suite success

Independently parsed `.work/music-ui-evidence/second/test-results` reports
**249 tests, 240 passed, nine failures, zero errors/skipped tests**. These are
real GUI test bodies after the display guard repair, not the first run's AWT
initialization failures. The design/settings/recovery group is **51/55 passed**:
all 35 pure design/settings cases and all 11 settings UI cases pass; recovery/
runtime is 5/9. The five passing root cases cover actual damaged-Room confirmed
restore/Cancel, missing-backup/future-schema refusal and both offline legal-reader
paths. Controlled runtime states and fake audio/source services remain explicit.

The four runtime failures are retained, not weakened:

- Prepare's unclipped semantic control is **184×40dp**, below the 48dp target.
- The Arabic Chromium and FFmpeg detail fields fail the text-layout overflow
  assertion at **320×760, 200% text**.
- English byte-progress text fails the same assertion at **960×720, 100% text**,
  not at the narrow/large-text setting.

Pinned Compose 1.9.3 source review found that the simple String-text semantics
path reconstructs `MultiParagraph` at the parent's maximum constraints while
returning the naturally sized text node (`ParagraphLayoutCache.kt:366–401`)
[S19, S20].
`hasVisualOverflow` compares these widths; a short text node can therefore
report width overflow without proof of clipped glyphs. The second run has no
failing-detail screenshot or detailed width diagnostics, so rendered truncation
is **not established** merely by those three assertions. The authored repair
makes the dialog column and each text explicitly full-width within the existing
bounded, scrollable body, and gives every Prepare/Retry/Close control a 48dp
minimum on both axes. It does not shrink text, remove bidi isolation, truncate
details or relax tests. Those `app/MediaRuntimeDetails.kt` and diagnostic/pre-assert
capture changes subsequently compiled and rendered in the incomplete fourth run
below; there is still no finalized fourth per-method result.

Preserved images/XML/labels and hashes are under `.work/design-ui-evidence/second/`.
Visual inspection confirms a rendered 320×760 Arabic compact-runtime scene,
Arabic large-text recovery confirmation with its scrollable prompt and visible
buttons, and the actual English bundled OFL page. The compact status deliberately
ellipsizes and opens full details; this image is not a full-detail fit proof.
The isolated one-missing-document capture has transparent background/black text:
its fixture lacked the real app's themed `Surface`, so it does not substantiate
dark-theme legibility. The next fixture uses that surface and captures the host;
all reader/error assertions remain. None of these captures establishes runtime
installation, media acquisition, screen-reader behavior or physical playback.

### Fourth guarded desktop checkpoint — partial execution and reviewed images

The third attempt was interrupted by SIGTERM during compilation, before tests.
The fourth reached actual test bodies, then ended **exit1 at10:17:44 UTC** when
the kernel killed Gradle daemon15612 for OOM. Independently inspected retained
Gradle/kernel evidence distinguishes this environment termination from the eight
earlier logged assertion failures. **Zero finalized XML does not mean zero test
execution:** there are eight unique FAILED method names and16 authored PNGs, but
no complete pass/fail count. The319 expected methods have no finalized results,
not necessarily319 unexecuted bodies. Independent review matched64/64 evidence
hashes,34/34 retained artifact hashes/sizes and identical475-entry before/after
input dictionaries. Evidence: `.work/podcast-ui-evidence/fourth/`; design review:
`.work/design-ui-evidence/fourth/{independent-review.json,review.md}`.

Actual runtime modal images now show complete Chromium/FFmpeg component labels,
English and Arabic byte progress, readable typed errors, visible Close controls
and a reachable full privacy paragraph. Arabic200% details/recovery use actual
320×760 scenes; English960×720 details yield a560×426 dialog crop. Long bodies
scroll, so an initial image's partially visible last line is not proof of a
truncated document. Later Arabic detail/error captures follow several strict
readability/control assertions, but precede later assertions/teardown: they are
**not whole-method success**. The repaired missing-legal fixture is opaque and
themed, but its final PNG depicts the later font-notice page, not the missing
error itself. The original bundled OFL is visible in the offline English capture.
No fourth podcast image or complete new Kotlin formatting result was retained.

Six shell failures remain independently identified: two selected-label overflow
flags, two48dp seek-target checks, the short-landscape Library query's clipping
check, and sleep-dialog Escape dismissal. The two selected words (`Listen` at
English200%, `المكتبة` at Arabic175%) visibly render completely; this does not
prove every200% destination, whose loop stopped early. Exact UI4 paragraph/node
metrics were not retained. The pinned String-semantics reconstruction above is
a supported diagnostic explanation, **not permission to remove the check**.
The surviving player image shows an episode transport in the initial viewport
with the default44dp thumb. Two failing methods overwrote that same filename;
there is no separate preserved long-metadata failure frame. The landscape query
is crowded between a runtime-error banner and a tall mini-player. Escape has no
retained modal/focus evidence yet; its cause remains undiagnosed.

After the lead lifted the freeze, a narrow source repair made nav labels fill
their existing bounded slot without shrinking/capping text, wired a short-height
mini-player preview, and added method/unique-group/sequence-isolated captures.
Strict48dp/overflow/containment assertions remain, with added ancestor-clipping,
line/character-advance diagnostics and failure-modal/key-consumption evidence.
Music separately enlarged the **actual thumb/layout**, not only semantics, and
authored mouse/touch-edge/keyboard/range and compact-Library regressions. These
latest repairs are **uncompiled/unrun** at this checkpoint. The two other logged
failures concern desktop cancellation ownership, not the design surface. No
screen-reader, real installation/media, physical audible output, native-window
resize or redistribution-readiness claim follows from these authored scenes.

## Sources

Fetched 2026-11-06. Apple HIG content was retrieved using its official documentation JSON endpoint because the web shell is JavaScript-driven; the public human-readable URL is given as well. Archive status is intentional, not concealed.

- **[S1]** Android, *Use window size classes*: https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
- **[S2]** Android, *Accessibility defaults in Compose*: https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- **[S3]** Apple HIG, *Accessibility*: https://developer.apple.com/design/human-interface-guidelines/accessibility ; fetched content: https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json
- **[S4]** Apple HIG, *Right to left*: https://developer.apple.com/design/human-interface-guidelines/right-to-left ; fetched content: https://developer.apple.com/tutorials/data/design/human-interface-guidelines/right-to-left.json
- **[S5]** Material Design archived guidance, *Bidirectionality*: https://m1.material.io/usability/bidirectionality.html
- **[S6]** Apple archived *Internationalization and Localization Guide — Supporting Right-to-Left Languages*: https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPInternational/SupportingRight-To-LeftLanguages/SupportingRight-To-LeftLanguages.html
- **[S7]** Microsoft, *Keyboard accessibility*: https://learn.microsoft.com/en-us/windows/apps/design/accessibility/keyboard-accessibility ; overview also inspected: https://learn.microsoft.com/en-us/windows/apps/design/accessibility/accessibility
- **[S8]** W3C, *WCAG 2.2*: https://www.w3.org/TR/WCAG22/ — particularly 1.4.3, 1.4.4, 1.4.10, 1.4.11, 2.1.1, 2.4.7, 2.4.11, 2.5.7, 2.5.8 and 4.1.3.
- **[S9]** W3C, *Understanding SC 2.5.7 Dragging Movements*: https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html
- **[S10]** W3C, *Understanding SC 2.4.11 Focus Not Obscured (Minimum)*: https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html
- **[S11]** W3C Internationalization, *Inline markup and bidirectional text in HTML*: https://www.w3.org/International/articles/inline-bidi-markup/ — principles apply; HTML elements are not Compose APIs.
- **[S12]** Android, *Support different languages and cultures*: https://developer.android.com/training/basics/supporting-devices/languages
- **[S13]** Spotify official newsroom, *Desktop experience: redesigned Your Library and Now Playing* (2023): https://newsroom.spotify.com/2023-06-20/spotify-desktop-experience-redesign-your-library-now-playing-views-customize/
- **[S14]** Pocket Casts, *Continuous Playback From the Up Next Queue*: https://support.pocketcasts.com/knowledge-base/up-next-continuous-playback/
- **[S15]** Google Fonts published IBM Plex Sans Arabic distribution: https://github.com/google/fonts/tree/main/ofl/ibmplexsansarabic ; inspected Regular binary: https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsansarabic/IBMPlexSansArabic-Regular.ttf
- **[S16]** IBM Plex Sans Arabic OFL: https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsansarabic/OFL.txt
- **[S17]** Noto Sans Arabic distribution / OFL: https://github.com/google/fonts/tree/main/ofl/notosansarabic ; https://raw.githubusercontent.com/google/fonts/main/ofl/notosansarabic/OFL.txt
- **[S18]** Manrope distribution / OFL: https://github.com/google/fonts/tree/main/ofl/manrope ; https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/OFL.txt
- **[S19]** Pinned Compose Foundation desktop1.9.3 source archive: https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.9.3/foundation-desktop-1.9.3-sources.jar ; inspected `commonMain/androidx/compose/foundation/text/modifiers/ParagraphLayoutCache.kt:366–401`; archive SHA-256 `4f6a4fd0046b6147dc1725777594d5fe0ba1bef718b3ea595dbc92cbe6296f6a`.
- **[S20]** Pinned Compose UI Text desktop1.9.3 source archive: https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-text-desktop/1.9.3/ui-text-desktop-1.9.3-sources.jar ; inspected `commonMain/androidx/compose/ui/text/TextLayoutResult.kt:315–325`; archive SHA-256 `5150f7b34bc891255a25506abacb83db3ac1ed1c0005ebfc60349a480a32dee1`. Both archives are preserved under `.work/design-ui-diagnostics/`; source inspection is not a rendered test result.
