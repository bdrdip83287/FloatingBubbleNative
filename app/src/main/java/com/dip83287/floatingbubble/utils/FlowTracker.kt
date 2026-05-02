package com.dip83287.floatingbubble.utils

class FlowTracker(private val logManager: LogManager) {
    private val stateMap = mutableMapOf<String, Any>()

    fun trackEnter(tag: String, method: String, params: Map<String, Any?> = emptyMap()) {
        val paramsStr = if (params.isNotEmpty()) params.map { "${it.key}=${it.value}" }.joinToString(", ") else ""
        logManager.flow(tag, "→ $method($paramsStr)")
    }

    fun trackExit(tag: String, method: String, result: Any? = null) {
        val resultStr = result?.let { " → $it" } ?: ""
        logManager.flow(tag, "← $method$resultStr")
    }

    fun trackState(tag: String, stateName: String, stateValue: Any) {
        val old = stateMap[stateName]
        stateMap[stateName] = stateValue
        val change = if (old != null && old != stateValue) " (was $old)" else ""
        logManager.state(tag, "$stateName = $stateValue$change")
    }

    fun trackEvent(tag: String, eventName: String, details: String = "") {
        logManager.i(tag, "📌 Event: $eventName${if (details.isNotEmpty()) " | $details" else ""}")
    }

    fun trackWarning(tag: String, warningName: String, details: String = "") {
        logManager.w(tag, "⚠️ Warning: $warningName${if (details.isNotEmpty()) " | $details" else ""}")
    }

    fun trackError(tag: String, errorName: String, error: Throwable) {
        logManager.e(tag, "🔥 Error: $errorName", error)
    }
}
