package me.rerere.asr.providers

import me.rerere.asr.ASRProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIRealtimeAuthHeaderTest {
    @Test fun `openai endpoint uses bearer authorization`() {
        val provider = ASRProviderSetting.OpenAIRealtime(apiKey = "sk-test")
        assertEquals("Authorization" to "Bearer sk-test", provider.authHeader())
    }

    @Test fun `azure endpoints use api-key header`() {
        for (url in listOf(
            "wss://rikka-voice-resource.openai.azure.com/openai/v1/realtime?intent=transcription",
            "wss://example.cognitiveservices.azure.com/openai/v1/realtime",
            " wss://Example.services.ai.azure.com/openai/v1/realtime ",
        )) {
            val provider = ASRProviderSetting.OpenAIRealtime(apiKey = "azure-key", websocketUrl = url)
            assertEquals(url, "api-key" to "azure-key", provider.authHeader())
        }
    }

    @Test fun `lookalike and malformed hosts keep bearer authorization`() {
        for (url in listOf("wss://openai.azure.com.evil.example/realtime", "not a url")) {
            val provider = ASRProviderSetting.OpenAIRealtime(apiKey = "k", websocketUrl = url)
            assertEquals(url, "Authorization" to "Bearer k", provider.authHeader())
        }
    }
}
