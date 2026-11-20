package com.controlx.router

import com.controlx.domain.ActionId
import com.controlx.domain.RequestSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastPathRouterTest {
    private val router = FastPathRouter()

    @Test
    fun routesNavigationCommands() {
        assertEquals(
            ActionId.HOME,
            router.route("Go home", RequestSource.USER_VOICE)?.actionId,
        )
    }

    @Test
    fun rejectsUnknownCommandsInsteadOfGuessing() {
        assertNull(router.route("delete my photos", RequestSource.USER_TEXT))
    }
}
