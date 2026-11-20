package com.controlx.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class ControlXDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = Unit

    override fun onDisabled(context: Context, intent: Intent) = Unit
}
