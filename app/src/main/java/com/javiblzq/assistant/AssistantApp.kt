package com.javiblzq.assistant

import android.app.Application
import com.javiblzq.assistant.data.LocalStore
import com.javiblzq.assistant.net.Tls12SocketFactory

class AssistantApp : Application() {
    lateinit var store: LocalStore
        private set

    override fun onCreate() {
        super.onCreate()
        Tls12SocketFactory.installIfNeeded(this)
        store = LocalStore(applicationContext)
    }
}
