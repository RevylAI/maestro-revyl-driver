# Revyl Maestro adapter

This standalone repository owns a Kotlin/JVM Maestro adapter.
Revyl services and upstream Maestro are external dependencies. Other repositories'
agent policies do not automatically apply here.

- Attach only to an existing authorized Revyl session. Closing the CLI must close
  local resources, never stop the session, app, or device.
- Execute Maestro's published parser, models, and Orchestra runtime. Do not add a
  second YAML interpreter or claim this is a stock Maestro CLI plugin.
- Validate the complete parsed flow before attachment. Keep the supported subset
  explicit and reject unsupported commands, hooks, scripts, and recovery/replay
  options. Source-option guards may reject information Maestro normalizes or
  drops, but must not translate or execute YAML independently of Maestro.
- Distinguish intentional bounded tap/key sequences from error recovery. Bound
  expanded commands as well as individual counts, delays, text, and coordinates.
  An uncertain result must latch a terminal failure before any remaining action.
- Preserve native operation semantics. Never implement focused text input by
  retapping, app-specific lifecycle by an untargeted operation, or a missing
  state read with a cached guess. Keep platform and runtime limits explicit.
- Gate deployment-dependent commands on explicit boolean capabilities from the
  attached session's authenticated worker health before any flow mutation.
  Missing or malformed capabilities must fail closed. Preserve requested gesture
  duration, and validate dynamically copied text before focused input.
- Keep API credentials in the process environment only. Never log YAML, selectors,
  provider responses, hierarchy, credentials, or customer content. Keep output
  artifacts private and ignored by Git.
- Use authenticated backend routes, bounded requests, and no redirects or retries.
  Transport and unsupported-operation errors must not be MaestroException types.
  Do not infer provider at-most-once execution from adapter no-replay behavior.
- Test through real Maestro YAML and Orchestra against loopback fixtures. Passing
  offline tests does not establish real-device or cloud compatibility.
- Keep dependency selection and licensing notes explicit. Update Gradle lockfiles
  through Gradle, not by hand. Run `./gradlew --no-daemon clean check installDist`
  before handing off changes and keep README capability claims aligned with the code.
- Strip runtime credentials from dependency, build, and test subprocesses. Use
  only loopback fixture credentials in offline tests, including installed-CLI tests.
- Remote repository creation, publication, commits, pushes, and live-device or
  cloud operations require separate explicit authorization.
