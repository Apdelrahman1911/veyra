# nativeCmpkit integration

Veyra compiles selected public UI APIs from the pinned `third_party/nativeCmpkit`
submodule (revision recorded in `gradle/nativecmpkit.commit`). Original packages,
source files, authorship and Apache-2.0 license are retained. No Maven package is
claimed: nativeCmpkit has not published one. Its standalone build requires a
newer toolchain than Veyra; this small consumer module uses Veyra's existing
Kotlin/Compose versions and excludes unrelated pickers, WebView, permissions
and Liquid Glass rendering dependencies.

On iOS the app uses these toolkit components natively:

| Veyra surface | Toolkit API | iOS control |
|---|---|---|
| Song, playlist, episode, sort and validation menus | `AdaptiveDropDown` (via `NativeMenu`) | `UIMenu` pull-down menu, SF Symbols, destructive items |
| Confirmations, playlist name, RSS feed, custom sleep duration | `AdaptiveAlertDialog`, `AdaptiveBasicAlertDialog` (via `NativeAlert`) | `UIAlertController` alert with `UITextField`s |
| Add to playlist, sleep timer presets, playback speed | `AdaptiveBasicAlertDialog` action-sheet style | `UIAlertController` action sheet |
| Add songs to a playlist | `AdaptiveBottomSheet` (via `NativeSheet`) | `UISheetPresentationController` page sheet |
| Primary destinations | `AdaptiveNavigationBar` (via `NativeTabBar`) | `UITabBar` (Liquid Glass on iOS 26) |
| Screen title, back and settings buttons | `AdaptiveTopBar` (via `NativeTopBar`) | `UINavigationBar` with SF Symbol bar button items |
| Queue actions (clear, undo) in the full player | the toolkit's `ToolbarManager` (via `NativeToolbar`) | `UIToolbar` (Liquid Glass on iOS 26) |
| Main and secondary action buttons | `NativeButton` (Veyra adapter) | `UIButton` filled / gray configurations, SF Symbols |
| Custom sleep-timer duration | `NativeDurationPicker` (Veyra adapter) in `NativeSheet` | `UIDatePicker` countdown wheel |
| Cards, tiles and shelves | `adaptiveClickable` | iOS press-scale feedback (Material ripple elsewhere) |
| Settings switches, player seek bar | `AdaptiveSwitch`, `AdaptiveSlider` | `UISwitch`, `UISlider` |
| Loading indicators, icon buttons | `AdaptiveCircularProgressIndicator`, `AdaptiveIconButton` | Cupertino activity indicator and button |

`NativeAlert`, `NativeSheet`, `NativeTabBar`, `NativeTopBar`, `NativeButton` and
`NativeDurationPicker` render the existing Material
composables unchanged on Android and desktop, so those platforms keep their
designs and test tags. Library defects found while adopting these components are
fixed in nativeCmpkit itself and re-pinned, never patched here.

Android and desktop compile the toolkit's Material implementations except for
a consumer slider actual that retains a real 48 dp thumb/input node. It preserves
the pinned public API, Material semantics, colors and callbacks; adding only an
outer minimum height to the upstream slider leaves its input node at 44 dp.
Dialogs compile its UIKit implementation on iOS. Veyra's iOS compatibility
actuals supply native UISwitch/UISlider adapters and the toolkit's Cupertino
icon button, so controls do not require the optional new Liquid Glass renderer.
These compatibility actuals live here rather than modifying the pinned library.
UIKit draws these hosted controls above the Compose canvas, so Compose content
cannot cover them: a caller whose content is covered (the full player) provides
`LocalNativeControlsCovered`, and the adapters hide their views meanwhile.

Three toolkit pieces are not used as-is, for reasons found while adopting them:
- `AdaptiveToolbar` is not compiled: its Material side needs the Material 3
  Expressive floating toolbar, absent from the Material 3 version pinned with
  Compose Multiplatform 1.10.3. `NativeToolbar` drives the toolkit's iOS
  `ToolbarManager` directly; other platforms keep their in-content buttons.
- `AdaptiveTimePicker` picks a clock time (with AM/PM), not a duration, so the
  sleep timer uses the same UIKit control in countdown mode.
- `AdaptiveButton`'s Liquid Glass button is drawn in Compose with the Backdrop
  library, not by UIKit. Native glass (`UIButton.Configuration.glass()`) is not
  usable in content either: its backdrop copy cannot capture the Compose Metal
  canvas, so it renders a flat capsule on a gray rectangle. In-content buttons
  use the standard filled and gray configurations; glass stays on the bars.
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

By default Compose Multiplatform places a UIKit interop view below its Metal canvas and
cuts a transparent hole through the canvas, so every pixel a native control leaves unpainted
shows black instead of the surface around it. The iOS controls are therefore hosted with
`placedAsOverlay = true` (Compose Multiplatform 1.10+): the view sits above the canvas with a
transparent background and blends with any surface, still clipped to its ancestors. UISwitch
is never sized by a guess: its host takes the switch's measured intrinsic size (wider since
iOS 26), at least 48 dp, so the control is never squeezed or clipped.
