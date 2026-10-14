package ai.revyl.maestro

import maestro.js.GraalJsEngine
import maestro.js.JsEngine
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.MaestroCommand

internal object FlowParameters {
    private val namePattern = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val referencePattern = Regex("\\$\\{[A-Z][A-Z0-9_]{0,63}}")

    fun requireName(name: String) {
        requireAdapter(namePattern.matches(name) && name != "JSON", "Parameter names must be uppercase identifiers of at most 64 characters; reserved names are unsupported.")
    }

    fun requireTemplate(value: String) {
        requireAdapter(!value.contains("\\\${") && !referencePattern.replace(value, "").contains("\${"), "Only simple parameter references are supported; scripts and escaped expressions are unsupported.")
    }

    fun resolve(commands: List<MaestroCommand>, platform: String): List<MaestroCommand> {
        ParameterEngine(GraalJsEngine(platform = platform)).use { engine ->
            var definitionCount = 0
            fun resolveScope(commands: List<MaestroCommand>, depth: Int): List<MaestroCommand> {
                requireAdapter(depth <= MAX_FLOW_DEPTH, "Flow exceeds the 16-level subflow depth limit.")
                engine.enterEnvScope()
                try {
                    commands.mapNotNull { it.defineVariablesCommand }.forEach { definition ->
                        definitionCount += definition.env.size
                        requireAdapter(definitionCount <= 4096, "Flow exceeds the 4096 parameter-definition limit.")
                        val resolved = definition.evaluateScripts(engine)
                        resolved.env.forEach(engine::putEnv)
                    }
                    return commands.filter { it.asCommand() !is DefineVariablesCommand }.map { wrapper ->
                        val resolved = wrapper.evaluateScripts(engine)
                        resolved.runFlowCommand?.let { flow ->
                            resolved.copy(runFlowCommand = flow.copy(commands = resolveScope(flow.commands, depth + 1)))
                        } ?: resolved
                    }
                } finally { engine.leaveEnvScope() }
            }
            return resolveScope(commands, 0)
        }
    }

    private class ParameterEngine(private val delegate: JsEngine) : JsEngine by delegate {
        private var names = mutableSetOf<String>()
        private val scopes = mutableListOf<Set<String>>()
        private var resolvedBytes = 0L
        private var referenceCount = 0
        private var interpolatedBytes = 0L

        override fun putEnv(key: String, value: String) {
            requireName(key)
            requireAdapter(!value.contains("\${") && value.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES, "Resolved parameters must be literal strings of at most 16 KiB.")
            resolvedBytes += value.toByteArray(Charsets.UTF_8).size
            requireAdapter(resolvedBytes <= 1024 * 1024, "Flow exceeds the 1 MiB resolved-parameter limit.")
            names.add(key)
            delegate.putEnv(key, value)
        }

        override fun evaluateScript(script: String, env: Map<String, String>, sourceName: String, runInSubScope: Boolean, scriptDir: String?): Any? {
            requireName(script)
            requireAdapter(script in names && env.isEmpty() && !runInSubScope && scriptDir == null, "Parameter references must name an explicitly defined flow parameter.")
            requireAdapter(++referenceCount <= 16_384, "Flow exceeds the 16384 parameter-reference limit.")
            val value = delegate.evaluateScript(script).toString()
            interpolatedBytes += value.toByteArray(Charsets.UTF_8).size
            requireAdapter(interpolatedBytes <= 8 * 1024 * 1024, "Flow exceeds the 8 MiB interpolation limit.")
            return value
        }

        override fun enterEnvScope() {
            scopes.add(names.toSet())
            delegate.enterEnvScope()
        }

        override fun leaveEnvScope() {
            names = scopes.removeAt(scopes.lastIndex).toMutableSet()
            delegate.leaveEnvScope()
        }
    }
}
