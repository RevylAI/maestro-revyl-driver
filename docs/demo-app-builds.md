# Build the pinned Maestro demo app

`.github/workflows/build-demo-app.yml` builds only Maestro's Flutter fixture at
`e2e/demo_app`. Its source is fixed to
[`cli-2.10.0`, commit `14a408335e17a090df1e26bdbeaf80d8122a8fd8`](https://github.com/mobile-dev-inc/maestro/tree/14a408335e17a090df1e26bdbeaf80d8122a8fd8/e2e/demo_app),
not a caller-supplied ref. The workflow verifies the checkout's commit and selects
Flutter **3.38.5** from that commit's `pubspec.yaml`.

Builds run on manual dispatch and pull requests that change the workflow itself,
not on every driver change. They do not run conformance flows, provision devices, install apps,
publish releases, or deploy anything. No repository secrets or Apple signing
credentials are needed: the workflow grants only `contents: read`, and checkout
does not persist credentials.

## Dispatch and download

Once the workflow is published on the repository's default branch, use Actions →
Build Maestro demo app → Run workflow, or run these commands from this repository
with an authenticated GitHub CLI:

```bash
gh workflow run build-demo-app.yml --ref YOUR_WORKFLOW_BRANCH
gh run list --workflow build-demo-app.yml --event workflow_dispatch --limit 5
gh run watch RUN_ID --exit-status
gh run download RUN_ID --name maestro-demo-app-android --dir .revyl-maestro/demo-builds/RUN_ID/android
gh run download RUN_ID --name maestro-demo-app-ios --dir .revyl-maestro/demo-builds/RUN_ID/ios
```

Choose the run corresponding to your dispatch; `--ref` selects the workflow
revision, not the pinned upstream app source. Downloads stay under the ignored
`.revyl-maestro/` directory. Artifacts expire after 14 days.

| GitHub Actions artifact | App file inside | Build |
| --- | --- | --- |
| `maestro-demo-app-android` | `demo_app.apk` | `ubuntu-24.04`, Java 17, `flutter build apk --release` |
| `maestro-demo-app-ios` | `demo_app.app.zip` | `macos-15`, `flutter build ios --simulator --debug --no-codesign` |

Each artifact also contains `SOURCE_COMMIT.txt`, `BUILD-INFO.txt`, and
`SHA256SUMS`. The job summary repeats the commit, build information, and checksums.
Check these before installing or hosting a binary:

```bash
(cd .revyl-maestro/demo-builds/RUN_ID/android && shasum -a 256 -c SHA256SUMS)
(cd .revyl-maestro/demo-builds/RUN_ID/ios && shasum -a 256 -c SHA256SUMS)
```

The Android APK uses release mode but the upstream project's **debug signing
key**, not a production signing identity. It is a test fixture, not a Play Store
release. Both platform app IDs are `com.example.example`.

## iOS packaging and compatibility

The iOS job selects the installed Xcode 26.1.1 SDK because the upstream
`connectivity_plus` dependency uses `NWPath.isUltraConstrained`, which is not
available in the runner's default Xcode 16 SDK. The selected Xcode version and
actual executable architectures are recorded in `BUILD-INFO.txt`.

The iOS artifact is a **debug simulator build**, not an IPA and not installable on
a physical iPhone. The workflow selects `macos-15` rather than a moving
`macos-latest` label. The actual Runner executable architectures and Xcode version
are recorded in `BUILD-INFO.txt`. Confirm those architectures match the target
simulator host; do not assume this is a universal Intel/Apple Silicon build.
See [GitHub's runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).

`demo_app.app.zip` contains `Runner.app` at its root. The workflow uses macOS
`ditto --keepParent` to preserve the bundle structure, executable permissions, and
symlinks before artifact upload. Keep this inner ZIP intact for transport; uploading
an unarchived `.app` directly as an Actions artifact can lose file permissions.
For local inspection on macOS, extract it with `ditto`:

```bash
ditto -x -k .revyl-maestro/demo-builds/RUN_ID/ios/demo_app.app.zip .revyl-maestro/demo-builds/RUN_ID/ios/unpacked
```

Pinned app source and Flutter do not make builds byte-for-byte reproducible:
runner images, Xcode, package resolution, and generated debug signing keys can
change. Preserve the chosen binary and its checksums rather than treating a
later rebuild as identical.

## Using the app for live conformance

An Actions artifact is **not a public install URL**. Artifact links require GitHub
access and expire; they are not a substitute for a worker-downloadable app URL.
Download the inner `demo_app.apk` or `demo_app.app.zip`, verify its checksum, and
have the user host those exact bytes at an immutable object URL with an
appropriately scoped, time-limited signed download URL. Do not point an installer
at the outer GitHub artifact archive.

Treat a signed URL as a secret: supply it only through the approved private
installation mechanism, never in committed files, workflow inputs, logs, summaries,
screenshots, or public comments. The workflow deliberately does not host binaries
or accept hosting credentials.

Live testing is a separate, explicitly authorized step. Use
[`conformance/live-suite.sh`](../conformance/live-suite.sh) with the hosted
artifact URL to own/start/stop the suite sessions, as described in
[`docs/live-suite.md`](live-suite.md), or attach the single-workspace harness to
an existing authorized session yourself.
A successful build alone does not prove installation, app launch, or flow
compatibility. Flutter/Android builds and the macOS/iOS packaging must still be
verified by an authorized workflow run; no device validation is performed here.
