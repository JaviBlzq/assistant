package com.javiblzq.assistant.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AssistantDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
