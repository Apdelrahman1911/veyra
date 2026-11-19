# iPhone download finalization test

Use media you are authorized to download. Include one short item and one long track or podcast episode. Reuse an item that previously stalled if it is still available. Use a fresh download for each transfer test.

## Install a development build

1. On a Mac, check out the exact Veyra commit supplied with the test build. Run `scripts/verify-checkout.sh` to verify the bundled kmpYT source. Keep `gradle/kmpyt.mode` set to `source`.
2. Open `iosApp/Veyra.xcodeproj` in Xcode. Select the **Veyra** target and choose your Apple development team under **Signing & Capabilities**. Let Xcode manage signing. Use `dev.veyra.app` if your team can provision it. Building with a different bundle identifier creates a separate app sandbox and cannot test recovery against the existing installation.
3. Connect your iPhone, enable Developer Mode if Xcode prompts for it, select it as the run destination, and use **Product → Run** with the Debug configuration. Retain an existing installation when testing queued-download or catalog recovery.

## Exercise the transfer and recovery paths

1. Download the short item. Open **Library → Downloads** immediately. Confirm the accepted item has a row. Watch for **Processing downloaded media…**, **Validating downloaded audio…**, **Saving verified download…**, and **Ready offline**. Brief states can finish between frames.
2. Enable Airplane Mode with Wi-Fi off. Start playback from the download row, seek, pause, and resume. Confirm that audio actually plays and the player reports the offline source. Relaunch Veyra while still offline and repeat.
3. Restore connectivity and download the long item. Note the wall-clock time when transfer stops advancing. Record any explicit failure or retry message, the last UI state, and the elapsed wait. If an item stays in Processing, Validation, or Saving for five minutes, capture that state before retrying or relaunching.
4. Start another large download. Disconnect the network mid-transfer, confirm that it remains represented in Downloads, restore connectivity, and retry or resume. Confirm the download converges to Ready or presents a specific error. Check for duplicate rows or misleading success messages.
5. Repeat with a pause and resume. Then start another transfer and use the app switcher to close Veyra during Processing, Validation, or Saving if you catch the state. Reopen the app, confirm that it recovers or offers a retry, and test offline playback after it reports Ready. A restart before validation is journaled can require connectivity to check the original media representation.
6. Start two different downloads and issue a duplicate request for one. Cancel one of the rows, allow the other to finish, and retry the cancelled item. Verify each row reaches the expected outcome and both completed files play offline.

## Working with Debug console output

For a run attached to Xcode, select the Veyra process and filter the console for `VEYRA_IOS_DOWNLOAD`, `VEYRA_IOS_DECODER`, and `VEYRA_DOWNLOAD_DIAGNOSTIC`. Keep the lines immediately before and after the reported stall or error. Note the case number, approximate time and time zone, iOS version, device model, expected result, and what you observed. Preserve technical operation IDs and timestamps. Share only the relevant Veyra lines and omit any unrelated output or private media identifiers.
