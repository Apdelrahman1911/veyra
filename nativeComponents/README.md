# NativeCmpKit integration

Veyra uses `io.github.apdelrahman1911.nativecmpkit:kira-ui:0.1.0-preview.1`
from Maven Central. Kira Core is transitive. Veyra’s adapters live in
`:nativeComponents` and use the `io.github.apdelrahman1911.kira.ui` public API.

On iOS the app uses these toolkit components natively:

| Veyra surface | Toolkit API | iOS control |
|---|---|---|
| Song, playlist, episode, sort and validation menus | `AdaptiveDropDown` (via `NativeMenu`) | `UIMenu` pull-down menu, SF Symbols, destructive items |
| Confirmations, playlist name, RSS feed, custom sleep duration | `AdaptiveAlertDialog`, `AdaptiveBasicAlertDialog` (via `NativeAlert`) | `UIAlertController` alert with `UITextField`s |
| Add to playlist, sleep timer presets, playback speed | `AdaptiveBasicAlertDialog` action-sheet style | `UIAlertController` action sheet |
| Add songs to a playlist | `AdaptiveBottomSheet` (via `NativeSheet`) | `UISheetPresentationController` page sheet |
| Primary destinations | `AdaptiveNavigationBar` (via `NativeTabBar`) | `UITabBar` (Liquid Glass on iOS 26) |
| Screen title, back and settings buttons | `AdaptiveTopBar` (via `NativeTopBar`) | `UINavigationBar` with SF Symbol bar button items |
| Queue actions (clear, undo) in the full player | `AdaptiveToolbar` (via `NativeToolbar`) | `UIToolbar` (Liquid Glass on iOS 26) |
| Main and secondary action buttons | `NativeButton` (Veyra adapter) | `UIButton` filled / gray configurations, SF Symbols |
| Custom sleep-timer duration | `NativeDurationPicker` (Veyra adapter) in `NativeSheet` | `UIDatePicker` countdown wheel |
| Cards, tiles and shelves | `adaptiveClickable` | iOS press-scale feedback (Material ripple elsewhere) |
| Settings switches, player seek bar | `NativeSwitch`, `NativeSlider` (Veyra adapters) | `UISwitch`, `UISlider` |
| Loading indicators, icon buttons | `AdaptiveCircularProgressIndicator`, `CupertinoButton` (via `NativeIconButton`) | Cupertino activity indicator and button |

`NativeAlert`, `NativeSheet`, `NativeTabBar`, `NativeTopBar`, `NativeButton` and
`NativeDurationPicker` render the existing Material
composables unchanged on Android and desktop, so those platforms keep their
designs and test tags. Library fixes are maintained in NativeCmpKit and reach
Veyra through updated releases.

The app’s Material slider keeps a 48 dp thumb and input node. UIKit adapters
preserve the host’s accessibility semantics, color and layout direction. iOS
icon buttons use the released Cupertino button with Veyra’s colors and touch target.
UIKit draws these hosted controls above the Compose canvas, so Compose content
cannot cover them: a caller whose content is covered (the full player) provides
`LocalNativeControlsCovered`, and the adapters hide their views meanwhile.

The sleep timer uses `UIDatePicker` in countdown mode. In-content buttons retain
the UIKit filled and gray configurations. The tab bar and toolbar provide the
system glass treatment on iOS 26.

Native slider callers can provide `LocalNativeControlAccessibility` with the
localized control name, displayed value, and speech language. The iOS adapter
passes these to UIKit, so a 75–200% text-size range is announced as the actual
text size rather than the slider's internal 0–100% fraction. Native switches
and sliders apply the Compose layout direction and theme on updates, including
runtime language and theme changes. Switch rows retain their single Compose
accessibility action when the decorative native switch has no callback.

The full player uses `NativeSlider` on each platform. Elapsed/duration speech and the language
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
