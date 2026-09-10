# Third-party dependencies and notices

This is a private experiment, not a redistribution clearance or exhaustive
license inventory. Revyl's source remains UNLICENSED; `LICENSE` does not replace
third-party licenses. Review the full resolved graph and bundled JAR notices
before any publication or distribution.

## Maestro

The build consumes `dev.mobile:maestro-client`, `maestro-orchestra`, and
`maestro-orchestra-models` version 2.10.0 from Maven Central. Upstream Maestro is
Copyright mobile.dev inc. and licensed under Apache License 2.0. Its API contract
was inspected at `mobile-dev-inc/Maestro`, tag `cli-2.10.0`, commit
`14a408335e17a090df1e26bdbeaf80d8122a8fd8`. No Maestro source code is vendored here.

- Source: https://github.com/mobile-dev-inc/Maestro
- Upstream license: https://github.com/mobile-dev-inc/Maestro/blob/cli-2.10.0/LICENSE

## Gradle wrapper

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are the official
Gradle wrapper files copied unchanged from that Maestro checkout. The scripts
retain their Gradle copyright and Apache-2.0 notices. The wrapper downloads
Gradle 8.13 and verifies the distribution SHA-256 published by Gradle.

- License text: `licenses/Apache-2.0.txt`
- Gradle licensing: https://github.com/gradle/gradle/blob/v8.13.0/LICENSE

## GraalJS and other libraries

Maestro's default `org.graalvm.js:js:24.2.0` dependency selects Oracle's
`truffle-enterprise` runtime, which is subject to the GraalVM Free Terms and
Conditions (GFTC), not Apache-2.0. This build deliberately substitutes
`org.graalvm.js:js-community:24.2.0`, whose runtime is
`org.graalvm.truffle:truffle-runtime:24.2.0`. The lockfile and dependency graph are
the maintained evidence for the exact selected artifacts; tests initialize the
actual community JavaScript engine.

Community Graal/Truffle includes UPL-1.0 and component-specific notices, and
other dependency groups have their own licenses. No statement here asserts
Apache-2.0 covers all transitive dependencies. Preserve each resolved artifact's
license and notices during a separately approved distribution review.

- GraalJS community metadata: https://repo.maven.apache.org/maven2/org/graalvm/js/js-community/24.2.0/js-community-24.2.0.pom
- Community runtime metadata: https://repo.maven.apache.org/maven2/org/graalvm/truffle/truffle-runtime/24.2.0/truffle-runtime-24.2.0.pom
- UPL 1.0: https://oss.oracle.com/licenses/upl/
