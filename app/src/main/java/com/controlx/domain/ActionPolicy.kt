package com.controlx.domain

class ActionPolicy {
    fun evaluate(request: ActionRequest): ActionResult {
        if (request.source == RequestSource.AGENT_PROPOSAL) {
            return ActionResult(
                status = ResultStatus.REQUIRES_CONFIRMATION,
                message = "Agent proposals require explicit user confirmation.",
            )
        }

        return when (request.actionId) {
            ActionId.OPEN_APP,
            ActionId.HOME,
            ActionId.BACK,
            ActionId.RECENTS,
            ActionId.SET_MEDIA_VOLUME,
            ActionId.TOGGLE_FLASHLIGHT,
            ActionId.DIAL_NUMBER,
            ActionId.SET_ALARM -> validateAllowlistedAction(request)
        }
    }

    private fun validateAllowlistedAction(request: ActionRequest): ActionResult {
        val requiredArgument = when (request.actionId) {
            ActionId.OPEN_APP -> "packageName"
            ActionId.SET_MEDIA_VOLUME -> "volumePercent"
            ActionId.DIAL_NUMBER -> "phoneNumber"
            ActionId.SET_ALARM -> "triggerAtMillis"
            else -> null
        }

        if (requiredArgument != null && request.arguments[requiredArgument].isNullOrBlank()) {
            return ActionResult(
                status = ResultStatus.REJECTED,
                message = "Missing required argument: $requiredArgument.",
            )
        }

        if (request.actionId == ActionId.SET_MEDIA_VOLUME) {
            val volume = request.arguments["volumePercent"]?.toIntOrNull()
            if (volume == null || volume !in 0..100) {
                return ActionResult(
                    status = ResultStatus.REJECTED,
                    message = "volumePercent must be an integer from 0 to 100.",
                )
            }
        }

        if (request.actionId == ActionId.SET_ALARM) {
            val triggerAtMillis = request.arguments["triggerAtMillis"]?.toLongOrNull()
            if (triggerAtMillis == null || triggerAtMillis <= 0L) {
                return ActionResult(
                    status = ResultStatus.REJECTED,
                    message = "triggerAtMillis must be a positive timestamp.",
                )
            }
        }

        return ActionResult(
            status = ResultStatus.ALLOWED,
            message = "Action passed the allowlist policy.",
        )
    }
}
