# Software and source notices

Newly authored Veyra source is licensed under GNU GPL version 3 or later; see `LICENSE`. This choice permits satisfying the selected Linux media-runtime GPL requirements; it does not relicense other software or media.

- **kmpYT**: the owner explicitly authorized MIT adoption. The official MIT license, accurate 2026 abdelrahman fahmy / contributor attribution, third-party audit and Maven license metadata are committed in upstream revision `e8754184b37b8c30fa89956908f09cda7f56732e`, published in [PR #1](https://github.com/Apdelrahman1911/kmpYT/pull/1) (merge and Veyra final pin update pending). Independent review found no conflicting third-party contribution in the 15-commit baseline. Preserve kmpYT's complete MIT notice, its CC0 authored-fixture notices, and its dependencies' own licenses. MIT is GPLv3-compatible; this does not relicense kmpYT as GPL or grant rights to source-service media. See `docs/verification/licensing.md` for precise audit scope and distribution gates.
- **libmpv / FFmpeg and native closure**: exact per-platform MediaMP runtime artifacts are pinned. Linux runtime is GPL; other selected runtimes carry LGPL and other notices. Corresponding-source/build material and the full notice inventory must accompany distribution. Current dependency/engine probes alone do not satisfy this release gate.
- **IBM Plex Sans Arabic**: unmodified font assets are under SIL OFL 1.1 with their original notices, bundled in Compose resources. Reserved Font Name “Plex”.
- Kotlin, Compose, AndroidX/Media3, Ktor, coroutines, Okio, Coil, JNA, D-Bus, JDK, Node and Chromium each retain their upstream licensing and notices; build a final resolved dependency inventory before release.
- Content belongs to its creators. Podcast RSS access is not blanket redistribution permission. Commons music must retain per-recording creator/license/source information. Test media is authored or explicitly licensed; downloaded third-party media is not committed or shipped as application content.

No source-service logos, competitor interface assets, credentials, cookies or private listening libraries are included.
