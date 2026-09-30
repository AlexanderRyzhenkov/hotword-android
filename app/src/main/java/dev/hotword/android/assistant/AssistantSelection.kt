package dev.hotword.android.assistant

internal object AssistantSelection {
    fun fromSetting(value: String?): String? = value?.substringBefore('/')?.trim()?.takeIf {
        Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(it)
    }
    fun isResolver(className: String?): Boolean = className?.endsWith("ResolverActivity") == true || className?.endsWith("ChooserActivity") == true
}
