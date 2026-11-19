# Third-party notices

NativeCmpKit retains Calf's Apache-2.0 source license and attribution; see
`LICENSE` and `NOTICE`. Third-party components keep their own licenses. This
document is not a certification of every transitive dependency's licensing.

## Embedded Jodd utilities and MIME database

The `jodd` sources/resources in `kira-file-picker` retain their complete
upstream two-clause BSD license headers:

- `src/desktopMain/kotlin/jodd/io/IOUtil.kt`
- `src/desktopMain/kotlin/jodd/net/MimeTypes.kt`
- `src/desktopMain/kotlin/jodd/util/Wildcard.kt`
- `src/desktopMain/resources/jodd/net/MimeTypes.properties`
- `src/desktopTest/kotlin/jodd/net/MimeTypesTest.kt`

Copyright (c) 2003-present, Jodd Team (http://jodd.org).
The five existing headers were compared and have identical license text
(after removing Kotlin/properties comment prefixes). A convenient full copy
is in [licenses/jodd-BSD-2-Clause.txt](licenses/jodd-BSD-2-Clause.txt).
This migration preserves the headers; it does **not** report them as missing.
Binary redistribution requires reproduction of the copyright, conditions,
and disclaimer in the accompanying documentation/materials. Verify that
artifact notice packaging includes them before future publication.

## Cupertino Icons font

`kira-cupertino-icons/src/commonMain/composeResources/font/sf_symbols.ttf` is
the **CupertinoIcons Version 1.08** font, not Apple's proprietary SF Symbols
font. On 2026-10-24 its 257,628 bytes were verified byte-for-byte against
Flutter's `cupertino_icons` package at commit
`23a05b6ad5d55bc0df5b9340623570b69064edf1`:

- [Pinned font source](https://github.com/flutter/packages/blob/23a05b6ad5d55bc0df5b9340623570b69064edf1/third_party/packages/cupertino_icons/assets/CupertinoIcons.ttf)
- [Pinned MIT license](https://github.com/flutter/packages/blob/23a05b6ad5d55bc0df5b9340623570b69064edf1/third_party/packages/cupertino_icons/LICENSE)
- SHA-256: `67c44fe9183b002e79dde7f6977e2988661c9a3e4a3c5fce968787efdbed823c`
- Copyright (c) 2016 Vladimir Kharlampidi
- Local, verbatim license: [licenses/cupertino-icons-MIT.txt](licenses/cupertino-icons-MIT.txt)
- Machine-readable verification: [licenses/cupertino-icons-provenance.json](licenses/cupertino-icons-provenance.json)

The MIT permission and copyright notice must accompany distributed copies
or substantial portions. Preserve the local license when packaging the font
in Android/JVM/Native/Web artifacts. **The migration adds source-tree notices;
packaged-artifact notice inclusion remains a pre-publication verification
gate.** No artifact publication was authorized or performed as part of this
notice addition.

## Apple SF Symbol name constants

`kira-ui/src/commonMain/kotlin/io/github/apdelrahman1911/kira/sf/symbols/SFSymbol.kt`
contains string identifiers passed to Apple's system image APIs; it does
not contain the Apple SF Symbols font or image outlines. The identifier
catalogue and Cupertino Icons font are different components. System symbol
availability and permitted uses depend on Apple's platform and applicable
terms; the Apache-2.0 project license does not grant rights to Apple's system
artwork. The catalogue's generation source/version mapping has not been
independently reconstructed; see `docs/audit/navigation-core-icons-sample.md`.

## Compose Cupertino slider adaptation

`kira-ui/src/iosMain/kotlin/io/github/apdelrahman1911/kira/ui/slider/CupertinoSlider.kt`
explicitly credits the Compose Cupertino implementation at baseline lines76-80.
The reference source at observation commit
`f66875aa6f3848b30c38e42a89fc99c9ac24a585` carries:

> Copyright (c) 2023-2024. Compose Cupertino project and open source contributors.

It is licensed under Apache License2.0. Preserve this source attribution along
with Calf/NativeCmpKit's adaptation history. The upstream license is retained
verbatim in
[licenses/compose-cupertino-Apache-2.0.txt](licenses/compose-cupertino-Apache-2.0.txt),
including its separate JetBrains2020-2021/respective-authors attribution.

- [Pinned source/header](https://github.com/alexzhirkevich/compose-cupertino/blob/f66875aa6f3848b30c38e42a89fc99c9ac24a585/cupertino/src/commonMain/kotlin/io/github/alexzhirkevich/cupertino/CupertinoSlider.kt)
- [Pinned license](https://github.com/alexzhirkevich/compose-cupertino/blob/f66875aa6f3848b30c38e42a89fc99c9ac24a585/LICENSE.txt)

This reference identifies observed attribution, **not** the exact version
originally copied into Calf; that import revision remains to be reconstructed.

## Kyant AndroidLiquidGlass sample adaptations

The following `kira-ui/src/iosMain/kotlin/io/github/apdelrahman1911/kira/ui/` files are
adapted from the AndroidLiquidGlass reference implementation:

- `button/LiquidGlassButton.kt`
- `slider/LiquidGlassSlider.kt`
- `utils/DragGestureInspector.kt`
- `utils/DampedDragAnimation.kt`
- `utils/InteractiveHighlight.kt`

Copyright2025 Kyant. Licensed under Apache License2.0; the original full
license/notice is preserved in
[licenses/android-liquid-glass-Apache-2.0.txt](licenses/android-liquid-glass-Apache-2.0.txt).
The component-default comments explicitly credit AndroidLiquidGlass. The drag
inspector matches the observed upstream source after package normalization;
the damping/highlight helpers are closely adapted, not asserted byte-identical.

- [Observed reference tree](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog)
- [Pinned license](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/LICENSE)
- [Source/license hashes and comparison scope](licenses/ui-derived-source-provenance.json)

The observation commit is `65ab177e90e5c1d8c62e70cf7755841982da65f6` on the
upstream `kmp` branch. It is **not** a claim that this exact revision was the one
originally imported. This copied-code notice is separate from the licensing and
version policy of the Backdrop/Shapes binary dependencies.

## AndroidX/AOSP navigation and bundle adaptations

The common navigation sources `NavArgument.kt`, `AdaptiveNavType.kt`,
`AdaptiveBaseBundle.kt` and `AdaptiveBundle.kt` preserve substantial AndroidX/AOSP
implementation/documentation structure. Relevant original reference notices are:

- NavArgument/NavType: Copyright2018 The Android Open Source Project.
- BaseBundle: Copyright(C)2014 The Android Open Source Project.
- Bundle: Copyright(C)2007 The Android Open Source Project.

These sources are licensed under Apache License2.0 (full text in `LICENSE`).
The original reference headers, including their license/disclaimer blocks, are
retained in
[licenses/androidx-aosp-NOTICE.txt](licenses/androidx-aosp-NOTICE.txt).
Pinned paths/hashes and scope are in
[licenses/navigation-aosp-provenance.json](licenses/navigation-aosp-provenance.json):
AndroidX reference commit `53bc2d6eef5ae5438d07f0071dc6f698428df978`;
AOSP frameworks-base reference commit `1cdfff555f4a21f71ccc978290e2e212e2f8b168`.

These are comparison snapshots observed2026-10-24, not verified original-copy
revisions. The adaptation's removed Android parcel/reflection features and Calf's
own changes remain attributable through preserved Git history. Reconstruct
precise imports and restore appropriate source headers in a coordinated change;
do not label all Calf code as authored by AOSP.

**Distribution gate:** the migration preserves these established attributions
and local license texts in the source tree. Exact copied-version reconstruction,
any additional upstream notice obligations and inclusion in future binary/web
distributions remain verification work (`NAV-018`). No release or package
publication was authorized by adding these notices.
