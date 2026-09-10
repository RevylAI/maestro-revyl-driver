# Revyl Maestro driver

Run supported native Maestro flows against an **existing Revyl Android or iOS
session**. This runner embeds Maestro's parser and execution
engine; it is not a plugin for stock `maestro test`. Synthetic-app flows have
verified native assertions, taps and repeated taps, long press, focused Backspace
deletion, Enter, Home, appearance changes, and plain deep links on staging Android
emulators and iOS simulators. Android Back was also verified. Local clipboard
commands ran in those flows; OS clipboard behavior is not provided. Location
setting has loopback contract coverage, not app-level live GPS verification.
Text input uses the existing authenticated viewer control channel on Android and
iOS, with offline WebSocket/Orchestra coverage only; it has not been live-verified.

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
For text input, **close the live Revyl viewer before running the flow**, focus the
intended field, and do not type secrets through this legacy path. See the input
limitations below before relying on the result.

## Supported commands

| Command | Semantics and limits |
| --- | --- |
| Native assertions, taps, screenshots, waits | Native selectors and grid coordinates; screenshots stay private. |
| `longPressOn` | A 3000-ms hold at an element center or literal native/percent point. |
| `tapOn` with `repeat`, `doubleTapOn` | Intentional, individually acknowledged taps through Maestro's own repeat loop. Counts are 1–100; delays are 0–10000 ms, with at most 120000 ms total requested delay per sequence. Maestro defaults the delay to 100 ms. |
| `pressKey` | `Enter`, `Backspace`, and `Home`; `Back` and the `back` command are Android-only. The current HTTP Enter/Backspace path supports Android and iOS simulators, not physical iOS. |
| `eraseText` | Sends 1–100 Backspaces to current focus. Omitted count means 50 Backspaces, not a guarantee that the field is empty. |
| `inputText`, `pasteText` | Android and iOS input through the existing viewer control WebSocket. Requires a ready, authorized control connection before any flow mutation. See the accepted legacy input limitations below. |
| `setClipboard`, `copyTextFrom` | Store text only in Maestro's local clipboard variable; these commands do not set the OS clipboard. Literal `setClipboard` text is limited to 16 KiB UTF-8. `pasteText` requires an earlier clipboard command and uses the viewer input path below. |
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

## Existing viewer input: accepted limitations

`inputText` and `pasteText` use the existing backend-authorized viewer WebSocket,
not an unpublished `/text-input` endpoint or `supports_focused_text_input` health
flag. After validating the complete flow and attaching to the authorized running
session, the runner fetches that workflow's authenticated
`streaming/worker-connection` response and establishes the connection **before any
flow mutation**, including earlier taps or keys. Missing control permission,
unready/mismatched workflow responses, invalid addresses, and handshake failures
fail early. Worker addresses cannot be supplied in YAML or runner configuration.
WSS is required outside literal loopback; redirects are forbidden. The backend's
connection token stays internal to its URL; the API key is never sent to the worker.

Each input sends one `STREAM` / `MANUAL_INPUT` message with a unique UUID
`action_id`, a client timestamp, the exact text as `value`, `incremental: true`,
`skip_tap: true`, and `clear_first: false`. The driver omits `x`/`y`, does not derive
a focus target from hierarchy, and never deliberately re-taps. On iOS it also sends
`paste: true` to request the existing pasteboard path rather than limited HID
character input; that path can change the device clipboard. Android uses the
existing provider text-input path. `pasteText` sends Maestro's local clipboard
value through the same platform-specific path. Input must be nonempty valid
Unicode, at most 16 KiB UTF-8, without control characters except tab, newline,
and carriage return. Literal pasted values are checked in preflight; values from
`copyTextFrom` are checked again immediately before input.

**This is an explicitly accepted exception to a strict no-refocus guarantee.**
The runtime may fall back to `device.input` when its passthrough helper is absent,
or to another input transport internally. Omitting coordinates avoids inventing a
target; it does not prove every provider preserves focus or selection. Prepare
the intended focus explicitly, and use native assertions or inspect the app to
verify the actual result. Physical-iOS behavior and Unicode/paste support depend
on the deployed provider; no live compatibility is claimed here.

Only a matching `ACTION_ACK` with `action: input` and boolean `success: true`
confirms the handler returned successfully. `ACTION_RECEIVED`, buffered events,
and unrelated IDs are not completion. **An ACK does not verify the text, caret,
selection, or app-level outcome.** The current runtime preferentially routes ACKs
to an open viewer's WebRTC channel, so close the live viewer during the flow.
A missing, invalid, or negative ACK, disconnect, send uncertainty, or timeout
latches a terminal failure: no later mutation, reconnect, resend, or payload replay.
Input may already have executed. Inspect the app before starting a new flow.
Connection and ACK waits use the request timeout and total flow deadline. The
adapter accepts text events up to 64 KiB, retains no inbound event queue, caps
outgoing queued messages at 128 KiB, and caps received events at 16384 per connection.
It handles bounded server ping IDs with matching pongs and cancels the local socket
on exit without stopping the external session.

**Do not enter passwords, tokens, or other secrets through this legacy path.**
Although this runner suppresses payloads, URLs, and provider diagnostics, the
existing worker can log typed content and include it in manual-action reports.
The intended outcome is successful external-runner native input without a runtime
rollout. The primary metric is native-input flow completion rate, expected to
increase. Measure usage and handler outcomes from existing Revyl manual action
records; use runner exit status and native assertions to assess actual flow
completion. The guardrails
are terminal ambiguous outcomes, no adapter retries, authorization, and no local
credential/content disclosure. No new analytics events or live success baseline
are claimed.

## Deployment-gated gestures

`swipe` and `scroll` have offline parser/Orchestra and installed-CLI coverage, **not
live-device verification**. They require worker changes and authenticated backend
relay support; their availability on current staging must not be assumed.
After session authorization, the runner reads the attached workflow's authenticated
`health` endpoint before executing any flow command. Health must identify the same
connected device and platform, and every required capability must be boolean
`true`. A missing, false, malformed, or unavailable capability fails the whole
flow before earlier taps, launches, or other mutations can run.
The previous capability-gated implementation was checked on staging Android and
iOS: rejected input and swipe flows left preceding mutations untouched. That input
check does not verify the new viewer path; the explicit-duration gesture gate is
unchanged.

| Command | Required health capability | Semantics and limits |
| --- | --- | --- |
| `swipe` | `supports_explicit_drag_duration` | One native `POST /drag`, preserving explicit endpoints and a 1–10000-ms duration; Maestro defaults to 400 ms. Supports native coordinate pairs, integer percent pairs from 0–99%, a direction, or a direction from a native element center. Endpoints must differ and fit the current native viewport. Does not use the legacy `/swipe` path or promise element dragging semantics. |
| `scroll` | `supports_explicit_drag_duration` | One upward center-to-10%-height drag, using Maestro's native default of 400 ms on Android or 333 ms on iOS. No scroll-until-visible loop. |

Coordinates use the same native grid as taps: Android screenshot pixels and iOS
hierarchy points, not iOS screenshot pixels. Directional swipes follow Maestro's
platform geometry; an explicit duration is forwarded unchanged, not replaced with
a fixed provider timing. Capability advertisement is a deployment contract, not
evidence of actual device timing or physical-iOS compatibility.

## Remaining limits and cleanup

App stop/kill/reset, keychain, permissions,
`scrollUntilVisible`, element dragging, orientation, keyboard dismissal, media,
recording, proxy, airplane mode, AI and web operations remain unsupported. The
worker's coordinate-based input is not equivalent to typing into current focus;
the accepted runtime input-fallback limitation is described above.
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
