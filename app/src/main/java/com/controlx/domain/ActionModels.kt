package com.controlx.domain

enum class ActionId {
    OPEN_APP,
    HOME,
    BACK,
    RECENTS,
    SET_MEDIA_VOLUME,
    TOGGLE_FLASHLIGHT,
    DIAL_NUMBER,
    SET_ALARM,
}

data class ActionRequest(
    val actionId: ActionId,
    val arguments: Map<String, String> = emptyMap(),
    val source: RequestSource,
)

enum class RequestSource {
    USER_TAP,
    USER_TEXT,
    USER_VOICE,
    AGENT_PROPOSAL,
}

data class ActionResult(
    val status: ResultStatus,
    val message: String,
)

enum class ResultStatus {
    ALLOWED,
    REQUIRES_CONFIRMATION,
    REJECTED,
}
