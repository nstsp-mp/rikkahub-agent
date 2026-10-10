package me.rerere.speech

private val AZURE_OPENAI_HOST_SUFFIXES = listOf(
    ".openai.azure.com",
    ".cognitiveservices.azure.com",
    ".services.ai.azure.com",
)

// Azure OpenAI wants resource keys in an api-key header. On the realtime websocket a Bearer key
// is answered with a 302 to an ?api-key= URL, which OkHttp websockets don't follow.
fun openAICompatibleAuthHeader(url: String, apiKey: String): Pair<String, String> {
    val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()
    val isAzure = host != null && AZURE_OPENAI_HOST_SUFFIXES.any { host.endsWith(it) }
    return if (isAzure) "api-key" to apiKey else "Authorization" to "Bearer $apiKey"
}
