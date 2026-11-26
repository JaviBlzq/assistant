package com.javiblzq.assistant.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class LocalStore(context: Context) {
    private val storageDir = context.filesDir
    val filesDir: File get() = storageDir
    private val configFile = File(storageDir, "assistant_config.json")
    private val tempConfigFile = File(storageDir, "assistant_config.json.tmp")
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var config: AssistantConfig = loadConfig()
        private set

    @Volatile
    var snapshot: DashboardSnapshot = DashboardSnapshot()
        private set

    private fun loadConfig(): AssistantConfig {
        synchronized(lock) {
            if (!configFile.exists()) return AssistantConfig()
            return try {
                AssistantConfig.fromJson(JSONObject(configFile.readText(Charsets.UTF_8)))
            } catch (_: Exception) {
                AssistantConfig()
            }
        }
    }

    private fun writeConfigAtomically(cfg: AssistantConfig) {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        val payload = cfg.toJson(redactSecrets = false).toString(2)
        tempConfigFile.writeText(payload, Charsets.UTF_8)
        if (!tempConfigFile.renameTo(configFile)) {
            configFile.writeText(payload, Charsets.UTF_8)
            tempConfigFile.delete()
        }
    }

    fun updateConfig(transform: (AssistantConfig) -> AssistantConfig): AssistantConfig {
        val updated = synchronized(lock) {
            val next = transform(config)
            config = next
            writeConfigAtomically(next)
            next
        }
        notifyListeners()
        return updated
    }

    fun updateSnapshot(transform: (DashboardSnapshot) -> DashboardSnapshot): DashboardSnapshot {
        val updated = synchronized(lock) {
            val next = transform(snapshot)
            snapshot = next
            next
        }
        notifyListeners()
        return updated
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Exception) {
            }
        }
    }
}
