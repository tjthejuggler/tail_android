package com.example.tail

import com.example.tail.data.AppSettings
import com.example.tail.data.ai.AiAssistantConfig
import com.example.tail.data.ai.LlmEndpoint
import com.example.tail.data.ai.LlmFallbackDefaults
import com.example.tail.data.ai.llmFallbackEndpoint
import com.example.tail.data.environment.WaterHardnessLlm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the EMERGENCY BACKUP LLM plumbing.
 *
 * The critical invariant under test: the backup endpoint is appended strictly
 * AFTER the primary endpoint, and is never used when the primary is healthy.
 */
class LlmFallbackTest {

    private val primary = LlmEndpoint(
        baseUrl = "https://api.z.ai/api/coding/paas/v4",
        apiKey = "primary-key",
        model = "glm-4.6"
    )

    private val backup = LlmEndpoint(
        baseUrl = LlmFallbackDefaults.BASE_URL,
        apiKey = "backup-key",
        model = LlmFallbackDefaults.MODEL
    )

    // ── LlmEndpoint ──────────────────────────────────────────────────────

    @Test
    fun `ppq default base url resolves to the documented endpoint`() {
        assertEquals(
            "https://api.ppq.ai/chat/completions",
            LlmEndpoint(LlmFallbackDefaults.BASE_URL, "k", LlmFallbackDefaults.MODEL)
                .chatCompletionsUrl()
        )
    }

    @Test
    fun `base url with trailing slash is normalised`() {
        assertEquals(
            "https://api.ppq.ai/chat/completions",
            LlmEndpoint("https://api.ppq.ai/", "k", "m").chatCompletionsUrl()
        )
    }

    @Test
    fun `full chat completions url is used verbatim`() {
        assertEquals(
            "https://api.ppq.ai/chat/completions",
            LlmEndpoint("https://api.ppq.ai/chat/completions", "k", "m").chatCompletionsUrl()
        )
    }

    @Test
    fun `versioned base urls get chat completions appended`() {
        assertEquals(
            "https://api.z.ai/api/coding/paas/v4/chat/completions",
            primary.chatCompletionsUrl()
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            LlmEndpoint("https://api.openai.com/v1", "k", "m").chatCompletionsUrl()
        )
    }

    @Test
    fun `endpoint requires url model and key`() {
        assertTrue(backup.isConfigured)
        assertFalse(backup.copy(apiKey = "").isConfigured)
        assertFalse(backup.copy(model = "").isConfigured)
        assertFalse(backup.copy(baseUrl = "").isConfigured)
    }

    // ── AppSettings.llmFallbackEndpoint() ────────────────────────────────

    @Test
    fun `fallback endpoint is null when disabled`() {
        val s = AppSettings(
            llmFallbackEnabled = false,
            llmFallbackBaseUrl = LlmFallbackDefaults.BASE_URL,
            llmFallbackApiKey = "backup-key",
            llmFallbackModel = LlmFallbackDefaults.MODEL
        )
        assertNull(s.llmFallbackEndpoint())
    }

    @Test
    fun `fallback endpoint is null without an api key`() {
        val s = AppSettings(
            llmFallbackEnabled = true,
            llmFallbackBaseUrl = LlmFallbackDefaults.BASE_URL,
            llmFallbackApiKey = "",
            llmFallbackModel = LlmFallbackDefaults.MODEL
        )
        assertNull(s.llmFallbackEndpoint())
    }

    @Test
    fun `fallback endpoint is built from settings when fully configured`() {
        val s = AppSettings(
            llmFallbackEnabled = true,
            llmFallbackBaseUrl = LlmFallbackDefaults.BASE_URL,
            llmFallbackApiKey = "backup-key",
            llmFallbackModel = LlmFallbackDefaults.MODEL
        )
        val endpoint = s.llmFallbackEndpoint()
        assertNotNull(endpoint)
        assertEquals("https://api.ppq.ai/chat/completions", endpoint!!.chatCompletionsUrl())
        assertEquals(LlmFallbackDefaults.MODEL, endpoint.model)
    }

    @Test
    fun `defaults ship the ppq base url and deepseek model`() {
        val s = AppSettings()
        assertEquals("https://api.ppq.ai", s.llmFallbackBaseUrl)
        assertEquals("deepseek-ai/DeepSeek-V4.1-Flash-1017", s.llmFallbackModel)
        assertTrue(s.llmFallbackEnabled)
        // No key shipped ⇒ backup inactive until the user pastes one.
        assertNull(s.llmFallbackEndpoint())
    }

    // ── AiAssistantConfig ordering ───────────────────────────────────────

    @Test
    fun `ai assistant tries primary first and backup last`() {
        val config = AiAssistantConfig(
            baseUrl = primary.baseUrl,
            apiKey = primary.apiKey,
            model = primary.model,
            fallback = backup
        )
        val endpoints = config.candidateEndpoints()
        assertEquals(2, endpoints.size)
        assertEquals(primary.chatCompletionsUrl(), endpoints[0].chatCompletionsUrl())
        assertEquals(backup.chatCompletionsUrl(), endpoints[1].chatCompletionsUrl())
    }

    @Test
    fun `ai assistant with only a backup configured still works`() {
        val config = AiAssistantConfig(
            baseUrl = "",
            apiKey = "",
            model = "",
            fallback = backup
        )
        assertTrue(config.isConfigured)
        val endpoints = config.candidateEndpoints()
        assertEquals(1, endpoints.size)
        assertEquals(backup.chatCompletionsUrl(), endpoints[0].chatCompletionsUrl())
    }

    @Test
    fun `ai assistant never duplicates an identical backup`() {
        val config = AiAssistantConfig(
            baseUrl = backup.baseUrl,
            apiKey = backup.apiKey,
            model = backup.model,
            fallback = backup
        )
        assertEquals(1, config.candidateEndpoints().size)
    }

    @Test
    fun `ai assistant is unconfigured when nothing is set`() {
        val config = AiAssistantConfig(baseUrl = "", apiKey = "", model = "", fallback = null)
        assertFalse(config.isConfigured)
        assertTrue(config.candidateEndpoints().isEmpty())
    }

    // ── WaterHardnessLlm ordering ────────────────────────────────────────

    @Test
    fun `water hardness tries primary first and backup last`() {
        val config = WaterHardnessLlm.Config(
            baseUrl = primary.baseUrl,
            apiKey = primary.apiKey,
            model = primary.model,
            fallback = backup
        )
        val endpoints = config.candidateEndpoints()
        assertEquals(2, endpoints.size)
        assertEquals(primary.chatCompletionsUrl(), endpoints[0].chatCompletionsUrl())
        assertEquals(backup.chatCompletionsUrl(), endpoints[1].chatCompletionsUrl())
    }

    @Test
    fun `water hardness works with only a backup configured`() {
        val config = WaterHardnessLlm.Config(
            baseUrl = "",
            apiKey = "",
            model = "",
            fallback = backup
        )
        assertTrue(config.isConfigured)
        assertEquals(1, config.candidateEndpoints().size)
    }

    @Test
    fun `water hardness is unconfigured when nothing is set`() {
        val config = WaterHardnessLlm.Config(baseUrl = "", apiKey = "", model = "", fallback = null)
        assertFalse(config.isConfigured)
        assertTrue(config.candidateEndpoints().isEmpty())
    }
}
