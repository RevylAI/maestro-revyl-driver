package ai.revyl.maestro

import maestro.js.GraalJsEngine
import maestro.js.JsEngine

internal class CompatJsEngine(private val delegate: GraalJsEngine, private val apiKey: String, private val dynamicPaths: Map<String, (JsEngine) -> String>, private val onFailure: (AdapterFailure) -> Unit) : JsEngine by delegate {
    override fun putEnv(key: String, value: String) {
        if (key == "REVYL_API_KEY" || (apiKey.isNotEmpty() && value.contains(apiKey))) return
        delegate.putEnv(key, value)
    }

    override fun evaluateScript(script: String, env: Map<String, String>, sourceName: String, runInSubScope: Boolean, scriptDir: String?): Any? {
        try {
            dynamicPaths[script]?.let { return it(this) }
            return delegate.evaluateScript(script, env.filter { (key, value) -> key != "REVYL_API_KEY" && (apiKey.isEmpty() || !value.contains(apiKey)) }, sourceName, runInSubScope, scriptDir)
        } catch (failure: AdapterFailure) {
            onFailure(failure)
            throw failure
        }
    }
}
