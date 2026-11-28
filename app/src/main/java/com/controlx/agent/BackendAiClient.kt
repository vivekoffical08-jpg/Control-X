package com.controlx.agent

import com.controlx.core.AgentBrainPort
import com.controlx.domain.ActionRecord
import com.controlx.domain.ActionType
import com.controlx.domain.AgentAction
import com.controlx.domain.ScreenSnapshot
import com.controlx.platform.CatalogApp
import com.controlx.platform.CommandApiClient
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DynamicIntentType {
    OPEN_APP,
    DIAL_CONTACT,
    DRAFT_SMS,
    WHATSAPP_MESSAGE,
    DRAFT_EMAIL,
    IN_APP_SEARCH,
    MAPS_ACTION,
    OPEN_CAMERA,
    CREATE_CALENDAR_EVENT,
    PLAY_MEDIA,
    WEB_SEARCH,
    SET_ALARM,
    SET_TIMER,
    TOGGLE_TORCH,
    SET_VOLUME,
    OPEN_SETTINGS,
    LOCK_SCREEN,
    ANSWER_TEXT,
    NEEDS_SCREEN_AGENT,
    BLOCKED,
}

data class DeviceCapabilities(
    val contactsGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val deviceAdminActive: Boolean = false,
    val cameraAvailable: Boolean = true,
)

data class DynamicIntentPlan(
    val intent: DynamicIntentType,
    val thought: String = "",
    val appPackage: String? = null,
    val appName: String? = null,
    val contactQuery: String? = null,
    val messageText: String? = null,
    val emailSubject: String? = null,
    val calendarTitle: String? = null,
    val mediaQuery: String? = null,
    val mediaPlatform: String? = null,
    val searchQuery: String? = null,
    val navigate: Boolean? = null,
    val cameraVideo: Boolean? = null,
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

class BackendAiClient(
    private val commandApiClient: CommandApiClient? = null,
) : AgentBrainPort {
    private val gson = Gson()

    fun isConfigured(): Boolean = commandApiClient?.isConfigured() == true

    suspend fun interpretIntent(
        command: String,
        installedApps: List<CatalogApp>,
        capabilities: DeviceCapabilities,
    ): DynamicIntentPlan = withContext(Dispatchers.IO) {
        val api = commandApiClient
            ?: throw IllegalStateException("Backend client is unavailable.")
        if (!api.isConfigured()) {
            throw IllegalStateException(
                "Connect a Control X backend server to enable dynamic AI commands.",
            )
        }
        val response = api.postAuthorizedJson("/api/v1/assistant/interpret") { deviceId ->
            gson.toJson(
                mapOf(
                    "deviceId" to deviceId,
                    "command" to command,
                    "capabilities" to capabilities,
                    "installedApps" to installedApps.filter { it.launchable }.take(150).map { app ->
                        mapOf("packageName" to app.packageName, "label" to app.label)
                    },
                ),
            )
        }
        if (!response.isSuccessful) {
            throw IllegalStateException(
                response.errorMessage ?: "Backend AI returned HTTP ${response.code}.",
            )
        }
        parseIntentJson(response.body)
    }

    override suspend fun planNextStep(
        goal: String,
        snapshot: ScreenSnapshot,
        history: List<ActionRecord>,
    ): AgentAction = withContext(Dispatchers.IO) {
        val api = commandApiClient
            ?: throw IllegalStateException("Backend client is unavailable.")
        if (!api.isConfigured()) {
            throw IllegalStateException(
                "Connect a Control X backend server to use AI screen automation.",
            )
        }
        val response = api.postAuthorizedJson("/api/v1/agent/plan") { deviceId ->
            gson.toJson(
                mapOf(
                    "deviceId" to deviceId,
                    "goal" to goal,
                    "snapshot" to mapOf(
                        "packageName" to snapshot.packageName,
                        "activityName" to snapshot.activityName,
                        "nodes" to snapshot.nodes.map { node ->
                            mapOf(
                                "id" to node.id,
                                "className" to node.className,
                                "text" to node.text,
                                "contentDescription" to node.contentDescription,
                                "viewIdResourceName" to node.viewIdResourceName,
                                "bounds" to listOf(
                                    node.bounds.left,
                                    node.bounds.top,
                                    node.bounds.right,
                                    node.bounds.bottom,
                                ),
                                "isClickable" to node.isClickable,
                                "isEditable" to node.isEditable,
                                "isScrollable" to node.isScrollable,
                                "isSensitive" to node.isSensitive,
                            )
                        },
                    ),
                    "history" to history.map { record ->
                        mapOf(
                            "action" to record.action.action.name,
                            "targetNodeId" to record.action.targetNodeId,
                            "screenFingerprint" to record.snapshotFingerprint,
                        )
                    },
                ),
            )
        }
        if (!response.isSuccessful) {
            throw IllegalStateException(
                response.errorMessage ?: "Backend planner returned HTTP ${response.code}.",
            )
        }
        parseAgentActionJson(response.body)
    }

    internal fun parseIntentJson(rawJson: String): DynamicIntentPlan {
        val json = runCatching {
            JsonParser.parseString(rawJson).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: throw IllegalStateException("Backend returned invalid intent JSON.")

        val intentName = json.optString("intent")?.uppercase()
            ?: throw IllegalArgumentException("Backend response missing intent.")
        val intent = runCatching { DynamicIntentType.valueOf(intentName) }
            .getOrElse { throw IllegalArgumentException("Unsupported dynamic intent: $intentName") }

        return DynamicIntentPlan(
            intent = intent,
            thought = json.optString("thought").orEmpty(),
            appPackage = json.optString("appPackage"),
            appName = json.optString("appName"),
            contactQuery = json.optString("contactQuery"),
            messageText = json.optString("messageText"),
            emailSubject = json.optString("emailSubject"),
            calendarTitle = json.optString("calendarTitle"),
            mediaQuery = json.optString("mediaQuery"),
            mediaPlatform = json.optString("mediaPlatform"),
            searchQuery = json.optString("searchQuery"),
            navigate = json.optBoolean("navigate"),
            cameraVideo = json.optBoolean("cameraVideo"),
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

    internal fun parseAgentActionJson(rawJson: String): AgentAction {
        val json = runCatching {
            JsonParser.parseString(rawJson).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: throw IllegalStateException("Backend returned invalid action JSON.")

        val actionName = json.optString("action")?.uppercase()
            ?: throw IllegalArgumentException("Backend response missing action.")
        val action = runCatching { ActionType.valueOf(actionName) }
            .getOrElse { throw IllegalArgumentException("Backend returned unknown action: $actionName") }

        val coordinates = json.get("coordinates")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { elem ->
            elem.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asDouble
                ?.toFloat()
                ?.takeIf { it.isFinite() }
        }
        return AgentAction(
            thought = json.optString("thought").orEmpty(),
            action = action,
            targetNodeId = json.optString("targetNodeId"),
            coordinates = coordinates,
            textInput = json.optString("textInput"),
            scrollDirection = json.optString("scrollDirection"),
            systemCommand = json.optString("systemCommand"),
            expectedOutcome = json.optString("expectedOutcome").orEmpty(),
        )
    }

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
}
