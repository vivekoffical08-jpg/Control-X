package com.controlx.router

import com.controlx.domain.ActionId
import com.controlx.domain.ActionRequest
import com.controlx.domain.RequestSource

class FastPathRouter {
    fun route(command: String, source: RequestSource): ActionRequest? {
        val normalized = command.trim().lowercase()
        return when {
            normalized == "home" || normalized == "go home" ->
                ActionRequest(ActionId.HOME, source = source)
            normalized == "back" || normalized == "go back" ->
                ActionRequest(ActionId.BACK, source = source)
            normalized == "recents" || normalized == "open recents" ->
                ActionRequest(ActionId.RECENTS, source = source)
            normalized == "turn on flashlight" ->
                ActionRequest(ActionId.TOGGLE_FLASHLIGHT, source = source)
            else -> null
        }
    }
}
