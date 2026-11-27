package com.controlx.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class InstalledAppSummary(
    val packageName: String,
    val label: String,
)

data class DeviceCapabilitiesPayload(
    val contactsGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val deviceAdminActive: Boolean = false,
    val cameraAvailable: Boolean = true,
)

data class IntentInterpretRequest(
    val deviceId: String,
    val command: String,
    val capabilities: DeviceCapabilitiesPayload = DeviceCapabilitiesPayload(),
    val installedApps: List<InstalledAppSummary> = emptyList(),
)

data class InterpretedIntentResponse(
    val intent: String,
    val thought: String = "",
    val appPackage: String? = null,
    val appName: String? = null,
    val contactQuery: String? = null,
    val messageText: String? = null,
    val mediaQuery: String? = null,
    val mediaPlatform: String? = null,
    val searchQuery: String? = null,
    val alarmHour: Int? = null,
    val alarmMinute: Int? = null,
    val timerSeconds: Int? = null,
    val torchEnabled: Boolean? = null,
    val volumeStream: String? = null,
    val volumePercent: Int? = null,
    val volumeDirection: String? = null,
    val settingsTarget: String? = null,
    val answerText: String? = null,
    val agentGoal: String? = null,
)

data class SnapshotNodePayload(
    val id: String,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null,
    val bounds: List<Int> = emptyList(),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isSensitive: Boolean = false,
)

data class SnapshotPayload(
    val packageName: String = "",
    val activityName: String? = null,
    val nodes: List<SnapshotNodePayload> = emptyList(),
)

data class ActionHistoryPayload(
    val action: String,
    val targetNodeId: String? = null,
    val screenFingerprint: String = "",
)

data class AgentPlanRequest(
    val deviceId: String,
    val goal: String,
    val snapshot: SnapshotPayload,
    val history: List<ActionHistoryPayload> = emptyList(),
)

data class AgentPlanResponse(
    val thought: String,
    val action: String,
    val targetNodeId: String? = null,
    val coordinates: List<Float>? = null,
    val textInput: String? = null,
    val scrollDirection: String? = null,
    val systemCommand: String? = null,
    val expectedOutcome: String = "",
)

class NvidiaNimProxy(
    private val apiKey: String?,
    val model: String = DEFAULT_MODEL,
    private val endpointUrl: String = CHAT_COMPLETIONS_URL,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val gson = Gson()

    fun isConfigured(): Boolean = !apiKey.isNullOrBlank()

    fun interpretIntent(request: IntentInterpretRequest): InterpretedIntentResponse {
        val appsJson = JsonArray().apply {
            request.installedApps.take(150).forEach { app ->
                add(
                    JsonObject().apply {
                        addProperty("packageName", app.packageName.take(160))
                        addProperty("label", app.label.take(80))
                    },
                )
            }
        }
        val systemPrompt = """
            You are Control X, an Android device assistant intent router running securely on the backend.
            Return ONE valid JSON object only (no markdown fences, no thinking tags) with keys:
            intent, thought, appPackage, appName, contactQuery, messageText, mediaQuery, mediaPlatform,
            searchQuery, alarmHour, alarmMinute, timerSeconds, torchEnabled, volumeStream, volumePercent,
            volumeDirection, settingsTarget, answerText, agentGoal.

            Allowed intent values:
            OPEN_APP, DIAL_CONTACT, DRAFT_SMS, PLAY_MEDIA, WEB_SEARCH, SET_ALARM, SET_TIMER,
            TOGGLE_TORCH, SET_VOLUME, OPEN_SETTINGS, LOCK_SCREEN, ANSWER_TEXT, NEEDS_SCREEN_AGENT, BLOCKED.

            Rules:
            - Match apps ONLY from installedApps when picking appPackage/appName.
            - Use DIAL_CONTACT for calling; use DRAFT_SMS when user wants to message/text/SMS a contact.
            - Use OPEN_SETTINGS with settingsTarget in (wifi, bluetooth, display, hotspot, dnd, notifications, accessibility).
            - Use ANSWER_TEXT for general knowledge, math, chat, or Q&A that needs no phone action.
            - Use NEEDS_SCREEN_AGENT ONLY if the task genuinely requires clicking/typing/scrolling inside a third-party app screen.
            - Use BLOCKED immediately for passwords, PINs, OTPs, CVVs, banking, UPI payments, transfers, purchases, or wiping data.
        """.trimIndent()
        val userPayload = gson.toJson(
            JsonObject().apply {
                addProperty("command", request.command)
                add("capabilities", gson.toJsonTree(request.capabilities))
                add("installedApps", appsJson)
            },
        )
        val content = callChatCompletions(systemPrompt, userPayload)
        return parseIntentResponse(content)
    }

    fun planReActStep(request: AgentPlanRequest): AgentPlanResponse {
        val systemPrompt = """
            You control Android through one atomic action.
            Return ONE valid JSON object only (no markdown fences, no thinking tags) with keys:
            thought, action, targetNodeId, coordinates, textInput, scrollDirection, systemCommand, expectedOutcome.
            action must be one of CLICK, TYPE, SCROLL, SYSTEM_ACTION, FINISH, FAIL.
            targetNodeId must be null or exactly one of the node_id values in the snapshot.
            Never target sensitive nodes or invent coordinates outside the screen.
            Never provide passwords, PINs, OTPs, CVVs, or financial/destructive actions.
        """.trimIndent()
        val nodesArray = JsonArray().apply {
            request.snapshot.nodes.take(180).forEach { node ->
                add(
                    JsonObject().apply {
                        addProperty("node_id", node.id)
                        addProperty("className", node.className)
                        addProperty("text", node.text)
                        addProperty("contentDescription", node.contentDescription)
                        addProperty("viewIdResourceName", node.viewIdResourceName)
                        add("bounds", gson.toJsonTree(node.bounds))
                        addProperty("isClickable", node.isClickable)
                        addProperty("isEditable", node.isEditable)
                        addProperty("isScrollable", node.isScrollable)
                        addProperty("isSensitive", node.isSensitive)
                    },
                )
            }
        }
        val userPayload = gson.toJson(
            JsonObject().apply {
                addProperty("goal", request.goal)
                addProperty("packageName", request.snapshot.packageName)
                addProperty("activityName", request.snapshot.activityName)
                add("nodes", nodesArray)
                add("history", gson.toJsonTree(request.history))
            },
        )
        val content = callChatCompletions(systemPrompt, userPayload)
        return parseAgentPlanResponse(content)
    }

    private fun callChatCompletions(systemPrompt: String, userPrompt: String): String {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Backend NVIDIA_API_KEY is not configured.")
        val primary = buildCompletionPayload(systemPrompt, userPrompt, includeResponseFormat = true)
        val response = postJson(key, primary)
        if (response.statusCode() in setOf(400, 422)) {
            val fallback = buildCompletionPayload(systemPrompt, userPrompt, includeResponseFormat = false)
            return handleCompletionResponse(postJson(key, fallback))
        }
        return handleCompletionResponse(response)
    }

    private fun postJson(key: String, payload: JsonObject): HttpResponse<String> {
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(endpointUrl))
            .timeout(Duration.ofSeconds(35))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    }

    private fun handleCompletionResponse(response: HttpResponse<String>): String {
        if (response.statusCode() !in 200..299) {
            val detail = extractApiErrorMessage(response.body())
            throw IllegalStateException(detail ?: "NVIDIA NIM returned HTTP ${response.statusCode()}.")
        }
        val root = runCatching {
            JsonParser.parseString(response.body()).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: throw IllegalStateException("NVIDIA NIM returned an invalid JSON payload.")

        val choices = root.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
        val firstChoice = choices?.takeIf { it.size() > 0 }?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject
        val message = firstChoice?.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
        return message?.optString("content")
            ?: throw IllegalStateException("NVIDIA NIM returned empty completion content.")
    }

    internal fun buildCompletionPayload(
        systemPrompt: String,
        userPrompt: String,
        includeResponseFormat: Boolean = true,
    ): JsonObject =
        JsonObject().apply {
            addProperty("model", model)
            if (includeResponseFormat) {
                add("response_format", JsonObject().apply { addProperty("type", "json_object") })
            }
            add(
                "messages",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", systemPrompt)
                        },
                    )
                    add(
                        JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", userPrompt)
                        },
                    )
                },
            )
            addProperty("temperature", 0.2)
            addProperty("top_p", 0.95)
            addProperty("max_tokens", 512)
            addProperty("stream", false)
            add(
                "chat_template_kwargs",
                JsonObject().apply { addProperty("enable_thinking", false) },
            )
        }

    internal fun parseIntentResponse(rawContent: String): InterpretedIntentResponse {
        val json = extractJsonObject(rawContent)
        val intent = json.optString("intent")?.uppercase()
            ?.takeIf { it in ALLOWED_INTENTS }
            ?: throw IllegalArgumentException("Unsupported dynamic intent returned.")
        return InterpretedIntentResponse(
            intent = intent,
            thought = json.optString("thought").orEmpty(),
            appPackage = json.optString("appPackage"),
            appName = json.optString("appName"),
            contactQuery = json.optString("contactQuery"),
            messageText = json.optString("messageText"),
            mediaQuery = json.optString("mediaQuery"),
            mediaPlatform = json.optString("mediaPlatform"),
            searchQuery = json.optString("searchQuery"),
            alarmHour = json.optInt("alarmHour"),
            alarmMinute = json.optInt("alarmMinute"),
            timerSeconds = json.optInt("timerSeconds"),
            torchEnabled = json.optBoolean("torchEnabled"),
            volumeStream = json.optString("volumeStream"),
            volumePercent = json.optInt("volumePercent"),
            volumeDirection = json.optString("volumeDirection"),
            settingsTarget = json.optString("settingsTarget"),
            answerText = json.optString("answerText"),
            agentGoal = json.optString("agentGoal"),
        )
    }

    internal fun parseAgentPlanResponse(rawContent: String): AgentPlanResponse {
        val json = extractJsonObject(rawContent)
        val action = json.optString("action")?.uppercase()
            ?.takeIf { it in ALLOWED_REACT_ACTIONS }
            ?: throw IllegalArgumentException("NVIDIA NIM returned an unknown ReAct action.")
        val coords = json.get("coordinates")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { elem ->
            elem.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asDouble
                ?.toFloat()
                ?.takeIf { it.isFinite() }
        }
        return AgentPlanResponse(
            thought = json.optString("thought").orEmpty(),
            action = action,
            targetNodeId = json.optString("targetNodeId"),
            coordinates = coords,
            textInput = json.optString("textInput"),
            scrollDirection = json.optString("scrollDirection"),
            systemCommand = json.optString("systemCommand"),
            expectedOutcome = json.optString("expectedOutcome").orEmpty(),
        )
    }

    private fun extractJsonObject(rawContent: String): JsonObject {
        val cleaned = rawContent
            .replace(THINK_BLOCK_REGEX, "")
            .replace(CODE_FENCE_START_REGEX, "")
            .replace(CODE_FENCE_END_REGEX, "")
            .trim()
        val slice = findFirstJsonObject(cleaned)
            ?: throw IllegalStateException("NVIDIA NIM response did not contain a JSON object.")
        return runCatching { JsonParser.parseString(slice).asJsonObject }
            .getOrElse { throw IllegalStateException("NVIDIA NIM returned malformed JSON.") }
    }

    private fun findFirstJsonObject(text: String): String? {
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> if (depth > 0) inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun extractApiErrorMessage(body: String?): String? =
        runCatching {
            val root = JsonParser.parseString(body.orEmpty()).asJsonObject
            val nested = root.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
            (nested?.optString("message")
                ?: root.optString("detail")
                ?: root.optString("title")
                ?: root.optString("message"))?.take(160)
        }.getOrNull()

    private fun JsonObject.optString(key: String): String? {
        val element: JsonElement = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.optInt(key: String): Int? {
        val element: JsonElement = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return runCatching { element.asInt }.getOrNull()
    }

    private fun JsonObject.optBoolean(key: String): Boolean? {
        val element: JsonElement = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) return null
        return runCatching { element.asBoolean }.getOrNull()
    }

    companion object {
        const val CHAT_COMPLETIONS_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val DEFAULT_MODEL = "nvidia/nemotron-3-ultra-550b-a55b"

        private val ALLOWED_INTENTS = setOf(
            "OPEN_APP",
            "DIAL_CONTACT",
            "DRAFT_SMS",
            "PLAY_MEDIA",
            "WEB_SEARCH",
            "SET_ALARM",
            "SET_TIMER",
            "TOGGLE_TORCH",
            "SET_VOLUME",
            "OPEN_SETTINGS",
            "LOCK_SCREEN",
            "ANSWER_TEXT",
            "NEEDS_SCREEN_AGENT",
            "BLOCKED",
        )
        private val ALLOWED_REACT_ACTIONS = setOf(
            "CLICK",
            "TYPE",
            "SCROLL",
            "SYSTEM_ACTION",
            "FINISH",
            "FAIL",
        )
        private val THINK_BLOCK_REGEX = Regex("""(?is)<think>.*?</think>""")
        private val CODE_FENCE_START_REGEX = Regex("""(?i)^```(?:json)?\s*""")
        private val CODE_FENCE_END_REGEX = Regex("""\s*```$""")
    }
}
