# Revyl Maestro driver

Run supported native Maestro flows against an **existing Revyl Android or iOS
session**. This experimental runner embeds Maestro's parser and execution
engine; it is not a plugin for stock `maestro test`. The smoke flow has been
verified against synthetic apps on staging Android and iOS devices.

## Install

Requires Java 21 on Linux or macOS. Build before loading runtime credentials:

```bash
git clone https://github.com/RevylAI/maestro-revyl-driver.git
cd maestro-revyl-driver
./gradlew --no-daemon installDist
```

## Run

[Prepare a Revyl session](https://docs.revyl.com/cli/device/quickstart) with your
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

## Limits and cleanup

Supports native assertions, taps, screenshots, and waits. Text input, reset,
stop, gestures, scripts, and mutation replay options are unsupported. The app
must already be running; optional [non-resetting launch](examples/non-resetting-launch.yaml)
requires `stopApp: false` and `permissions: {}`. Use trusted flows only, avoid
concurrent controllers, and inspect the session before retrying a timed-out action.

Exiting detaches locally; it does **not** stop the Revyl session. When finished,
stop only a session you own:

```bash
revyl device stop --session-id YOUR_RUNNING_SESSION_UUID
```

Private source, not licensed for redistribution. See [LICENSE](LICENSE) and
[third-party notices](THIRD_PARTY_NOTICES.md).
