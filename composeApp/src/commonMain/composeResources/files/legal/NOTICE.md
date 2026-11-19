# Software and source notices

Newly authored Veyra source is licensed under GNU GPL version 3 or later; see `LICENSE`. This choice permits satisfying the selected Linux media-runtime GPL requirements; it does not relicense other software or media.

- **kmpYT** retains its MIT license and contributor attribution. Veyra ships the exact `LICENSE` and third-party audit and fixture notices from the pinned Maven release. kmpYT’s dependencies and source-service media retain their own terms. [kmpYT license adoption](https://github.com/Apdelrahman1911/kmpYT/pull/1) · [Veyra licensing audit](docs/verification/licensing.md)
- **libmpv / FFmpeg and native closure**: exact per-platform MediaMP runtime artifacts are pinned. Linux runtime is GPL; other selected runtimes carry LGPL and other notices. Corresponding-source/build material and the full notice inventory must accompany distribution. Current dependency/engine probes alone do not satisfy this release gate.
- **NativeCmpKit Kira UI** provides adaptive UI, including iOS menus, alerts, sheets, the tab bar and the toolbar. Veyra ships the release’s Apache-2.0 grant, Calf attribution and additional Kira UI notices. The publisher archive and retained text hashes are recorded with the build inputs. [Notice provenance](third_party/notices/published-libraries.json)
- **IBM Plex Sans Arabic**: unmodified font assets are under SIL OFL 1.1 with their original notices, bundled in Compose resources. Reserved Font Name “Plex”.
- Kotlin, Compose, AndroidX/Media3, Ktor, coroutines, Okio, Coil, JNA, D-Bus, JDK, Node and Chromium each retain their upstream licensing and notices; build a final resolved dependency inventory before release.
- Content belongs to its creators. Podcast RSS access is not blanket redistribution permission. Commons music must retain per-recording creator/license/source information. Test media is authored or explicitly licensed; downloaded third-party media is not committed or shipped as application content.

No source-service logos, competitor interface assets, credentials, cookies or private listening libraries are included.
