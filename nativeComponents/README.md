# nativeCmpkit integration

Veyra compiles selected public UI APIs from the pinned `third_party/nativeCmpkit`
submodule (revision recorded in `gradle/nativecmpkit.commit`). Original packages,
source files, authorship and Apache-2.0 license are retained. No Maven package is
claimed: nativeCmpkit has not published one. Its standalone build requires a
newer toolchain than Veyra; this small consumer module uses Veyra's existing
Kotlin/Compose versions and excludes unrelated navigation, pickers, WebView,
permissions and Liquid Glass rendering dependencies.

Android and desktop compile the toolkit's Material implementations except for
a consumer slider actual that retains a real 48 dp thumb/input node. It preserves
the pinned public API, Material semantics, colors and callbacks; adding only an
outer minimum height to the upstream slider leaves its input node at 44 dp.
Dialogs compile its UIKit implementation on iOS. Veyra's iOS compatibility
actuals supply native UISwitch/UISlider adapters and the toolkit's Cupertino
icon button, so controls do not require the optional new Liquid Glass renderer.
These compatibility actuals live here rather than modifying the pinned library.
Only executed platform checks recorded in the redesign report count as verified.

Native slider callers can provide `LocalNativeControlAccessibility` with the
localized control name, displayed value, and speech language. The iOS adapter
passes these to UIKit, so a 75–200% text-size range is announced as the actual
text size rather than the slider's internal 0–100% fraction. Native switches
and sliders apply the Compose layout direction and theme on updates, including
runtime language and theme changes. Switch rows retain their single Compose
accessibility action when the decorative native switch has no callback.

The full player's seek control uses the same AdaptiveSlider API: native UISlider
on iOS and Material on Android/desktop. Elapsed/duration speech and the language
are provided through the accessibility local. Transport direction stays LTR in
Arabic. Drafts are local to the control, commit once on release, and are discarded
when the playback entry or generation changes. A current-owner guard also rejects
late callbacks from a disposed native control.

Compose Multiplatform 1.9 draws a UIKit interop view in an area cut through the Compose
layer, so any pixel the native control does not paint shows black (UISwitch is a fixed
51×31 inside a 48 dp target; UISlider's track is thinner than its host). Each iOS control
is therefore hosted in a container that fills the whole area with the colour behind it:
`LocalNativeControlBackground`, defaulting to the theme background. Settings cards provide
their surface colour.
