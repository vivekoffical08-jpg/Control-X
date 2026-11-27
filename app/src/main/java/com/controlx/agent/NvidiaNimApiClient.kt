package com.controlx.agent

import com.controlx.domain.ActionRecord
import com.controlx.domain.ActionType
import com.controlx.domain.AgentAction
import com.controlx.domain.ScreenSnapshot
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class NvidiaNimApiClient(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String = { DEFAULT_MODEL },
    private val endpointUrl: String = CHAT_COMPLETIONS_URL,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun planNextStep(
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<ActionRecord>,
    ): AgentAction = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("NVIDIA API key is not configured.")

        val primaryPayload = buildRequestJson(
            goal = goal,
            snapshot = snapshot,
            history = history,
            includeResponseFormat = true,
        )
        executeCompletion(apiKey, primaryPayload, goal, snapshot, history)
    }

    private fun executeCompletion(
        apiKey: String,
        payload: JsonObject,
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<ActionRecord>,
        allowFallbackWithoutResponseFormat: Boolean = true,
    ): AgentAction {
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IllegalStateException("NVIDIA NIM returned an empty response.")
            if (!response.isSuccessful) {
                if (allowFallbackWithoutResponseFormat && response.code in setOf(400, 422)) {
                    val fallbackPayload = buildRequestJson(
                        goal = goal,
                        snapshot = snapshot,
                        history = history,
                        includeResponseFormat = false,
                    )
                    return executeCompletion(
                        apiKey = apiKey,
                        payload = fallbackPayload,
                        goal = goal,
                        snapshot = snapshot,
                        history = history,
                        allowFallbackWithoutResponseFormat = false,
                    )
                }
                throw IllegalStateException(
                    extractApiErrorMessage(body)
                        ?: "NVIDIA NIM returned HTTP ${response.code}.",
                )
            }
            val content = extractChoiceContent(body)
            return parseResponseContent(content)
        }
    }

    internal fun buildRequestJson(
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<ActionRecord>,
        includeResponseFormat: Boolean = true,
    ): JsonObject {
        val resolvedModel = modelProvider().trim().ifEmpty { DEFAULT_MODEL }
        val nodesArray = JsonArray().apply {
            snapshot.nodes.forEach { node ->
                add(
                    JsonObject().apply {
                        addProperty("node_id", node.id)
                        addProperty("className", node.className)
                        addProperty("text", node.text)
                        addProperty("contentDescription", node.contentDescription)
                        addProperty("viewIdResourceName", node.viewIdResourceName)
                        add(
                            "bounds",
                            JsonArray().apply {
                                add(node.bounds.left)
                                add(node.bounds.top)
                                add(node.bounds.right)
                                add(node.bounds.bottom)
                            },
                        )
                        addProperty("isClickable", node.isClickable)
                        addProperty("isEditable", node.isEditable)
                        addProperty("isScrollable", node.isScrollable)
                        addProperty("isSensitive", node.isSensitive)
                    },
                )
            }
        }
        val historyArray = JsonArray().apply {
            history.forEach { record ->
                add(
                    JsonObject().apply {
                        addProperty("action", record.action.action.name)
                        addProperty("targetNodeId", record.action.targetNodeId)
                        addProperty("screenFingerprint", record.snapshotFingerprint)
                    },
                )
            }
        }
        val systemPrompt = """
            You control Android through one atomic action.
            Return a single valid JSON object only (no markdown fences, no thinking tags) with keys: thought, action, targetNodeId, coordinates, textInput, scrollDirection, systemCommand, expectedOutcome.
            action must be one of CLICK, TYPE, SCROLL, SYSTEM_ACTION, FINISH, FAIL.
            targetNodeId must be null or exactly one of the node_id values in the snapshot.
            Never target sensitive nodes or invent coordinates outside the screen.
            Never provide passwords, PINs, OTPs, CVVs, or financial/destructive actions.
        """.trimIndent()
        val userPrompt = gson.toJson(
            JsonObject().apply {
                addProperty("goal", goal)
                addProperty("packageName", snapshot.packageName)
                addProperty("activityName", snapshot.activityName)
                add("nodes", nodesArray)
                add("history", historyArray)
            },
        )
        return JsonObject().apply {
            addProperty("model", resolvedModel)
            if (includeResponseFormat) {
                add(
                    "response_format",
                    JsonObject().apply { addProperty("type", "json_object") },
                )
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
    }

    internal fun parseResponseContent(rawContent: String): AgentAction {
        val cleaned = rawContent
            .replace(THINK_BLOCK_REGEX, "")
            .replace(CODE_FENCE_START_REGEX, "")
            .replace(CODE_FENCE_END_REGEX, "")
            .trim()
        val jsonSlice = extractFirstJsonObject(cleaned)
            ?: throw IllegalStateException("NVIDIA NIM response did not contain a valid JSON action.")
        val json = runCatching {
            JsonParser.parseString(jsonSlice).asJsonObject
        }.getOrElse {
            throw IllegalStateException("NVIDIA NIM returned malformed JSON.")
        }
        return parseAgentAction(json)
    }

    private fun extractChoiceContent(body: String): String {
        val root = runCatching { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }
            .getOrNull()
            ?: throw IllegalStateException("NVIDIA NIM returned an invalid payload.")
        val choices = root.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalStateException("NVIDIA NIM returned no completion choices.")
        if (choices.size() == 0) {
            throw IllegalStateException("NVIDIA NIM returned no completion choices.")
        }
        val firstChoice = choices[0]?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("NVIDIA NIM returned an empty choice.")
        val message = firstChoice.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("NVIDIA NIM returned an empty message.")
        return message.optNullableString("content")
            ?: throw IllegalStateException("NVIDIA NIM returned empty content.")
    }

    private fun extractApiErrorMessage(body: String): String? =
        runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val nested = root.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
            val text = nested?.optNullableString("message")
                ?: root.optNullableString("detail")
                ?: root.optNullableString("title")
                ?: root.optNullableString("message")
            text?.take(160)
        }.getOrNull()

    private fun parseAgentAction(json: JsonObject): AgentAction {
        val actionName = json.optNullableString("action")?.uppercase()
            ?: throw IllegalArgumentException("NVIDIA NIM did not return an action.")
        val action = runCatching {
            ActionType.valueOf(actionName)
        }.getOrElse {
            throw IllegalArgumentException("NVIDIA NIM returned an unknown action.")
        }
        val coordinates = json.get("coordinates")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
            arr.mapNotNull { elem ->
                elem.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asDouble
                    ?.toFloat()
                    ?.takeIf { it.isFinite() }
            }
        }
        return AgentAction(
            thought = json.optNullableString("thought").orEmpty(),
            action = action,
            targetNodeId = json.optNullableString("targetNodeId"),
            coordinates = coordinates,
            textInput = json.optNullableString("textInput"),
            scrollDirection = json.optNullableString("scrollDirection"),
            systemCommand = json.optNullableString("systemCommand"),
            expectedOutcome = json.optNullableString("expectedOutcome").orEmpty(),
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val ch = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> if (depth > 0) inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun JsonObject.optNullableString(key: String): String? {
        val element: JsonElement = get(key) ?: return null
        if (element.isJsonNull || !element.isJsonPrimitive) return null
        return runCatching { element.asString }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val CHAT_COMPLETIONS_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val DEFAULT_MODEL = "nvidia/nemotron-3-ultra-550b-a55b"

        private val THINK_BLOCK_REGEX = Regex("""(?is)<think>.*?</think>""")
        private val CODE_FENCE_START_REGEX = Regex("""(?i)^```(?:json)?\s*""")
        private val CODE_FENCE_END_REGEX = Regex("""\s*```$""")
    }
}
