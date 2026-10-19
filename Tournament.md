# DroidFishTournament — Tournament Mode Guide

This document describes the tournament features added to this DroidFish-derived application. The exact options available can vary by source revision; always check the Setup screen in the installed build.

## Important: separate app identity

The Android application ID is `org.petero.droidfishtournament` and the launcher name is **DroidFishTournament**. This allows it to be installed alongside the original DroidFish package (`org.petero.droidfish`) without replacing it. The Java source package remains `org.petero.droidfish`; changing the Android application ID does not require renaming every Java class. Install and test the generated APK to confirm the package identity shown by your build.

## Getting started

1. Open **DroidFishTournament**.
2. Open Tournament Setup from the tournament controls/menu.
3. Choose the tournament name, participating engines, number of games/pairings, time-control mode, and opening book options as required.
4. Review all settings before starting. Engine binaries must be installed/configured and compatible with the device.
5. Start the tournament and use **Pause/Play** to control it. Use **Switch** to change between the live tournament board and the mirror board.
6. Check the PGN output and standings after games complete. Keep a backup of tournament PGNs before changing builds or clearing app data.

## Time control: units and display

In **Time Clock: Time + Increment** mode:

- **Base time per side** is entered in decimal minutes. For example, `0.5` means 30 seconds; `1.5` means 1 minute 30 seconds; `1.7` means 1 minute 42 seconds.
- **Increment per move** is entered in seconds. For example, `0.5` means an increment of half a second per move.
- The header should display the base time as a clock-style duration followed by the increment in seconds, for example `TC 0:30+0.5` or `TC 1:42+0.5`, rather than `TC 0.5+0.5`, which can be mistaken for seconds.

The displayed remaining chess clocks are the actual per-side clock values. The tournament elapsed-time indicator (`T`) is a separate elapsed-time display; it is not a player's chess clock and should not be used to infer a remaining time control.

## Tournament board and Mirror Board

- **Tournament board** is the live board used to run the engine game.
- **Mirror Board** is a second view of the live position and offers move navigation and the tournament's mirrored information.
- Switching views should not restart an engine search or alter a position, clock, pairing, or result. When a view is hidden, it should not perform unnecessary drawing or text refreshes; on return it should refresh from the current shared tournament state.
- The mirror header may show the game number, time control, and elapsed event time.
- The live tournament move list is intended to be omitted from the main tournament board when space is needed; use the mirror board for the move list/navigation.

## Pause, resume, and leaving the app

Use the in-app Pause/Play control for explicit control. Backgrounding behavior depends on Android lifecycle callbacks and the build revision. Test it on the target device: verify that the tournament pauses when leaving the app, that engine processes do not continue unexpectedly, and that returning to the app does not unexpectedly resume a paused tournament. Do not rely on an untested APK for unattended tournaments.

## Automatic timers

If timer controls are present in the Setup screen, check their current labels and units before use:

- A **go-home / pause timer** is intended to pause the tournament and return to the app's main screen after the configured play interval. A value of `0` means disabled when specified by the UI.
- A **play/pause cooling cycle** is intended to alternate a period of active play with a cooling pause. The play interval counts active play time; the pause interval counts time while paused. The tournament chess clock must remain stopped during cooling pauses.
- Timer controls must not change chess-clock values or erase tournament state. Android may delay background callbacks or restrict launching activities while backgrounded, so verify timing on the phone with short test intervals before relying on it.

## PGN, standings, and recovery

- Allow games to finish or pause the tournament before force-closing the app.
- Confirm that the expected PGN file exists and contains the completed games.
- Keep backups of PGNs and saved tournament data. Do not assume app data will survive uninstalling or clearing storage.
- If a tournament fails to resume, preserve the PGN and logs and report the build/commit plus the steps that led to the failure.

## Engine and performance notes

- Engine search is expected to dominate CPU usage. Board drawing, analysis labels, clock refreshes, PGN writing, and standings also use resources, but normally much less.
- The hidden board should not perform redundant rendering or high-frequency text updates. A mirror view is not a second engine and should not start a separate search unless an explicit analysis feature asks for it.
- NPS can vary with device temperature, background load, engine options, and power management. Compare builds under the same conditions; NPS alone does not establish playing strength.
- Avoid charging during controlled heat/performance comparisons and allow the device to cool between long runs.

## Troubleshooting checklist

1. Confirm the correct APK is installed and that its app ID is `org.petero.droidfishtournament`.
2. Confirm each engine can start and answer UCI `uci` / `isready` commands.
3. Run a short tournament with two engines and a short time control.
4. Switch boards repeatedly and confirm the game continues without clock jumps or duplicate moves.
5. Test pause/resume, app backgrounding, timer behavior, PGN output, and standings independently.
6. If the app crashes or a workflow fails, save the complete build log and the relevant source revision before making further changes.

## Development notes

The tournament activity and runner are coupled to DroidFish's chess model, engine lifecycle, and GUI. Porting tournament logic to another chess application should separate pairing/scheduling, time control, PGN persistence, engine adapters, and UI rendering. Avoid changing the engine search or adjudication logic while optimizing screen refreshes. Run a full Gradle build and device test before treating a source ZIP as release-ready.
