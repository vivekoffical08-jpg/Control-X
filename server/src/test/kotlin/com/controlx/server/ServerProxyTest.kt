package com.controlx.server

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerProxyTest {
    @Test
    fun buildCompletionPayloadDisablesThinkingByDefault() {
        val proxy = NvidiaNimProxy(apiKey = "test-key")
        val payload = proxy.buildCompletionPayload("system", "user")

        assertEquals(NvidiaNimProxy.DEFAULT_MODEL, payload.get("model").asString)
        assertFalse(payload.getAsJsonObject("chat_template_kwargs").get("enable_thinking").asBoolean)
        assertEquals("json_object", payload.getAsJsonObject("response_format").get("type").asString)
    }

    @Test
    fun parseIntentResponseStripsThinkingAndMarkdownFences() {
        val proxy = NvidiaNimProxy(apiKey = "test-key")
        val raw = """
            <think>
            The user wants to draft a text message to Rahul.
            </think>
            ```json
            {"intent":"DRAFT_SMS","thought":"Draft SMS to Rahul","contactQuery":"Rahul","messageText":"I am on my way"}
            ```
        """.trimIndent()

        val result = proxy.parseIntentResponse(raw)

        assertEquals("DRAFT_SMS", result.intent)
        assertEquals("Rahul", result.contactQuery)
        assertEquals("I am on my way", result.messageText)
    }

    @Test
    fun parseAgentPlanResponseHandlesStrictJsonAction() {
        val proxy = NvidiaNimProxy(apiKey = "test-key")
        val raw = """{"thought":"Tap Play","action":"CLICK","targetNodeId":"cx_3","expectedOutcome":"Playback starts"}"""

        val plan = proxy.parseAgentPlanResponse(raw)

        assertEquals("CLICK", plan.action)
        assertEquals("cx_3", plan.targetNodeId)
    }

    @Test
    fun parseIntentResponseSupportsInAppExecutionIntents() {
        val proxy = NvidiaNimProxy(apiKey = "test-key")
        val raw = """{"intent":"WHATSAPP_MESSAGE","thought":"Message Rahul","contactQuery":"Rahul","messageText":"Reached home"}"""

        val result = proxy.parseIntentResponse(raw)

        assertEquals("WHATSAPP_MESSAGE", result.intent)
        assertEquals("Rahul", result.contactQuery)
        assertEquals("Reached home", result.messageText)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseIntentResponseRejectsUnknownIntent() {
        val proxy = NvidiaNimProxy(apiKey = "test-key")
        proxy.parseIntentResponse("""{"intent":"RUN_SHELL","thought":"unsafe"}""")
    }

    @Test
    fun sha256AndConstantTimeCompareWorkConsistently() {
        val hashA = DeviceAuthRepository.sha256Hex("token-1")
        val hashB = DeviceAuthRepository.sha256Hex("token-2")

        assertEquals(64, hashA.length)
        assertNotEquals(hashA, hashB)
        assertTrue(DeviceAuthRepository.constantTimeEquals("abc", "abc"))
        assertFalse(DeviceAuthRepository.constantTimeEquals("abc", "abd"))
    }
}
