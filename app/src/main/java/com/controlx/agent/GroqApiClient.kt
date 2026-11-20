package com.controlx.agent

import com.controlx.domain.ActionRecord
import com.controlx.domain.ActionType
import com.controlx.domain.AgentAction
import com.controlx.domain.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GroqApiClient(
    private val apiKeyProvider: () -> String?,
    private val model: String = "llama-3.3-70b-versatile",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun planNextStep(
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<ActionRecord>,
    ): AgentAction = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
            ?: throw IllegalStateException("Groq API key is not configured.")

        val nodes = JSONArray(snapshot.nodes.map { node ->
            JSONObject().apply {
                put("node_id", node.id)
                put("className", node.className)
                put("text", node.text)
                put("contentDescription", node.contentDescription)
                put("viewIdResourceName", node.viewIdResourceName)
                put("bounds", JSONArray(listOf(
                    node.bounds.left,
                    node.bounds.top,
                    node.bounds.right,
                    node.bounds.bottom,
                )))
                put("isClickable", node.isClickable)
                put("isEditable", node.isEditable)
                put("isScrollable", node.isScrollable)
                put("isSensitive", node.isSensitive)
            }
        })
        val historyJson = JSONArray(history.map { record ->
            JSONObject().apply {
                put("action", record.action.action.name)
                put("targetNodeId", record.action.targetNodeId)
                put("screenFingerprint", record.snapshotFingerprint)
            }
        })
        val systemPrompt = """
            You control Android through one atomic action.
            Return JSON only with keys: thought, action, targetNodeId, coordinates,
            textInput, scrollDirection, systemCommand, expectedOutcome.
            action must be one of CLICK, TYPE, SCROLL, SYSTEM_ACTION, FINISH, FAIL.
            targetNodeId must be null or exactly one of the node_id values in the snapshot.
            Never target sensitive nodes or invent coordinates outside the screen.
            Never provide passwords, PINs, OTPs, CVVs, or financial/destructive actions.
        """.trimIndent()
        val userPrompt = JSONObject().apply {
            put("goal", goal)
            put("packageName", snapshot.packageName)
            put("nodes", nodes)
            put("history", historyJson)
        }.toString()
        val requestJson = JSONObject().apply {
            put("model", model)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
            put("temperature", 0.1)
            put("max_tokens", 320)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IllegalStateException("Groq returned an empty response.")
            if (!response.isSuccessful) {
                throw IllegalStateException("Groq returned HTTP ${response.code}.")
            }
            val content = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            parseAgentAction(JSONObject(content))
        }
    }

    private fun parseAgentAction(json: JSONObject): AgentAction {
        val action = runCatching {
            ActionType.valueOf(json.getString("action").uppercase())
        }.getOrElse {
            throw IllegalArgumentException("Groq returned an unknown action.")
        }
        val coordinates = json.optJSONArray("coordinates")?.let { array ->
            List(array.length()) { index -> array.optDouble(index).toFloat() }
        }
        return AgentAction(
            thought = json.optString("thought"),
            action = action,
            targetNodeId = json.opt("targetNodeId").takeUnless { it == JSONObject.NULL } as? String,
            coordinates = coordinates,
            textInput = json.opt("textInput").takeUnless { it == JSONObject.NULL } as? String,
            scrollDirection = json.opt("scrollDirection").takeUnless { it == JSONObject.NULL } as? String,
            systemCommand = json.opt("systemCommand").takeUnless { it == JSONObject.NULL } as? String,
            expectedOutcome = json.optString("expectedOutcome"),
        )
    }
}
