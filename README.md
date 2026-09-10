# Revyl Maestro driver

Run supported native Maestro flows against an **existing Revyl Android or iOS
session**. This runner embeds Maestro's parser and execution
engine; it is not a plugin for stock `maestro test`. Synthetic-app flows have
verified native assertions, taps and repeated taps, long press, focused Backspace
deletion, Enter, Home, appearance changes, and plain deep links on staging Android
emulators and iOS simulators. Android Back was also verified. Local clipboard
commands ran in those flows; OS clipboard behavior is not provided. Location
setting has loopback contract coverage, not app-level live GPS verification.

## Install

Requires Java 21 on Linux or macOS. Build before loading runtime credentials:

```bash
git clone https://github.com/RevylAI/maestro-revyl-driver.git
cd maestro-revyl-driver
./gradlew --no-daemon installDist
```

## Run

[Prepare a Revyl session](https://docs.revyl.ai/cli/device/quickstart) with your
app already installed and running. Adapt [examples/smoke.yaml](examples/smoke.yaml)
to your app; this sample expects tapping **Continue** to show **Welcome**:

```yaml
appId: com.example.app
---
- assertVisible: Continue
- tapOn: Continue
- assertVisible: Welcome
- assertNotVisible: Continue
- takeScreenshot: smoke
```

Provide `REVYL_API_KEY` through the runner's environment, never in YAML or
command-line arguments. From the repository directory:

```bash
build/install/revyl-maestro/bin/revyl-maestro \
  --session YOUR_RUNNING_SESSION_UUID \
  --platform android examples/smoke.yaml
```

Use `--platform ios` for iOS. A failed assertion or unsupported command exits
nonzero. The screenshot is saved privately to `.revyl-maestro/smoke.png`;
delete it or choose a new screenshot name before rerunning.

## Supported commands

| Command | Semantics and limits |
| --- | --- |
| Native assertions, taps, screenshots, waits | Native selectors and grid coordinates; screenshots stay private. |
| `longPressOn` | A 3000-ms hold at an element center or literal native/percent point. |
| `tapOn` with `repeat`, `doubleTapOn` | Intentional, individually acknowledged taps through Maestro's own repeat loop. Counts are 1–100; delays are 0–10000 ms, with at most 120000 ms total requested delay per sequence. Maestro defaults the delay to 100 ms. |
| `pressKey` | `Enter`, `Backspace`, and `Home`; `Back` and the `back` command are Android-only. The current HTTP Enter/Backspace path supports Android and iOS simulators, not physical iOS. |
| `eraseText` | Sends 1–100 Backspaces to current focus. Omitted count means 50 Backspaces, not a guarantee that the field is empty. |
| `setClipboard`, `copyTextFrom` | Store text only in Maestro's local clipboard variable; they do not set the OS clipboard. Literal `setClipboard` text is limited to 16 KiB UTF-8. Pasting requires the deployment-gated input capability below. |
| `setLocation` | Finite latitude in −90…90 and longitude in −180…180 degrees. Supported by the current Android emulator/iOS simulator worker paths, not physical iOS. |
| `setDarkMode` | Explicit `enabled` or `disabled` through system appearance setting. Current worker support is emulator/simulator-only; toggles and appearance assertions are unsupported. |
| `openLink` | Plain absolute HTTP(S) or app-scheme links, with `autoVerify: false` and `browser: false` (also the scalar form's defaults). Uses system URL routing without forcing a target app. URLs are limited to 8 KiB UTF-8; relative, control-character, embedded-credential, `file:`, `data:`, `javascript:`, and `intent:` links are rejected. |

For example, with a text field **already focused**:

```yaml
appId: com.example.app
---
- eraseText: 3
- pressKey: Enter
- setDarkMode: enabled
- longPressOn: Continue
```

The complete flow is checked before attachment. There may be at most 999 source
commands and 1000 expanded commands, counting each tap or Backspace in a sequence.
Optional commands, scripts/expressions, hooks, general repeat/retry blocks,
`retryTapIfNoChange`, and `waitUntilVisible` are rejected. Unsupported or invalid
options are not a request to approximate the action.

## Deployment-gated input and gestures

These commands have offline parser/Orchestra and installed-CLI coverage, **not
live-device verification**. They require worker changes and authenticated backend
relay support; their availability on current staging must not be assumed.
After session authorization, the runner reads the attached workflow's authenticated
`health` endpoint before executing any flow command. Health must identify the same
connected device and platform, and every required capability must be boolean
`true`. A missing, false, malformed, or unavailable capability fails the whole
flow before earlier taps, launches, or other mutations can run.
This rejection was verified on current staging Android and iOS: input and swipe
flows failed while the preceding tap's counter and the input value stayed unchanged.

| Command | Required health capability | Semantics and limits |
| --- | --- | --- |
| Android `inputText` | `supports_focused_text_input` | One `POST /text-input` with only `text`, acknowledged as action `input`. Inserts at the existing caret/selection without retapping, clearing, or adapter retries. Requires nonempty valid Unicode, at most 16 KiB UTF-8, and no control characters except tab, newline, and carriage return. iOS input remains unsupported. |
| Android `pasteText` | `supports_focused_text_input` | Requires an earlier `setClipboard` or `copyTextFrom`; sends that local value through focused text input, not the OS clipboard. Literal text is validated in preflight; dynamically copied text is validated before its input mutation. |
| `swipe` | `supports_explicit_drag_duration` | One native `POST /drag`, preserving explicit endpoints and a 1–10000-ms duration; Maestro defaults to 400 ms. Supports native coordinate pairs, integer percent pairs from 0–99%, a direction, or a direction from a native element center. Endpoints must differ and fit the current native viewport. Does not use the legacy `/swipe` path or promise element dragging semantics. |
| `scroll` | `supports_explicit_drag_duration` | One upward center-to-10%-height drag, using Maestro's native default of 400 ms on Android or 333 ms on iOS. No scroll-until-visible loop. |

Coordinates use the same native grid as taps: Android screenshot pixels and iOS
hierarchy points, not iOS screenshot pixels. Directional swipes follow Maestro's
platform geometry; an explicit duration is forwarded unchanged, not replaced with
a fixed provider timing. Capability advertisement is a deployment contract, not
evidence of actual device timing, caret behavior, or physical-iOS compatibility.

## Remaining limits and cleanup

iOS focused input/paste, app stop/kill/reset, keychain, permissions,
`scrollUntilVisible`, element dragging, orientation, keyboard dismissal, media,
recording, proxy, airplane mode, AI and web operations remain unsupported. The
worker's coordinate-based input is not equivalent to typing into current focus,
and is never used as a fallback for focused input.
The app must already be running; optional
[non-resetting launch](examples/non-resetting-launch.yaml) still requires
`stopApp: false` and `permissions: {}`.

The adapter does not replay a failed or uncertain request, and stops all remaining
actions when an acknowledgment fails. **This is not an end-to-end at-most-once
guarantee:** worker/device providers can retry internally or fall back to another
transport after partial execution. Repeated HTTP taps, including `doubleTapOn`,
do not guarantee the platform's native double-tap timing window. Use trusted flows,
avoid concurrent controllers, and inspect the session before manually retrying a
timed-out action. Runtime limitations can produce a terminal unsupported response;
`--platform ios` alone does not establish simulator capabilities.

Exiting detaches locally; it does **not** stop the Revyl session. When finished,
stop only a session you own:

```bash
revyl device stop --session-id YOUR_RUNNING_SESSION_UUID
```

Private source, not licensed for redistribution. See [LICENSE](LICENSE) and
[third-party notices](THIRD_PARTY_NOTICES.md). Contributor boundaries and validation
requirements are maintained in [AGENTS.md](AGENTS.md).
