package app.qingchuan.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** 应用设置。所有项都以 StateFlow 暴露，界面直接 collect。 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("qc_prefs", Context.MODE_PRIVATE)

    val did: String = sp.getString(KEY_DID, null) ?: UUID.randomUUID().toString().also {
        sp.edit().putString(KEY_DID, it).apply()
    }

    private val _alias = MutableStateFlow(
        sp.getString(KEY_ALIAS, null)?.takeIf { it.isNotBlank() } ?: defaultAlias()
    )
    val alias: StateFlow<String> = _alias.asStateFlow()

    private val _port = MutableStateFlow(sp.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _background = MutableStateFlow(sp.getBoolean(KEY_BACKGROUND, true))
    val background: StateFlow<Boolean> = _background.asStateFlow()

    private val _theme = MutableStateFlow(ThemeMode.values().firstOrNull { it.name == sp.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _storageMode = MutableStateFlow(
        StorageMode.values().firstOrNull { it.name == sp.getString(KEY_STORAGE, null) } ?: StorageMode.DOWNLOAD
    )
    val storageMode: StateFlow<StorageMode> = _storageMode.asStateFlow()

    private val _askBeforeReceive = MutableStateFlow(sp.getBoolean(KEY_ASK, true))
    val askBeforeReceive: StateFlow<Boolean> = _askBeforeReceive.asStateFlow()

    fun setAlias(value: String) {
        val v = value.trim().ifBlank { defaultAlias() }.take(40)
        _alias.value = v
        sp.edit().putString(KEY_ALIAS, v).apply()
    }

    fun setPort(value: Int) {
        val v = value.coerceIn(1024, 65535)
        _port.value = v
        sp.edit().putInt(KEY_PORT, v).apply()
    }

    fun setBackground(value: Boolean) {
        _background.value = value
        sp.edit().putBoolean(KEY_BACKGROUND, value).apply()
    }

    fun setTheme(value: ThemeMode) {
        _theme.value = value
        sp.edit().putString(KEY_THEME, value.name).apply()
    }

    fun setStorageMode(value: StorageMode) {
        _storageMode.value = value
        sp.edit().putString(KEY_STORAGE, value.name).apply()
    }

    fun setAskBeforeReceive(value: Boolean) {
        _askBeforeReceive.value = value
        sp.edit().putBoolean(KEY_ASK, value).apply()
    }

    fun info(port: Int = _port.value): DeviceInfo = DeviceInfo(
        alias = _alias.value,
        did = did,
        port = port,
        os = "Android ${Build.VERSION.RELEASE}",
        version = BuildInfo.VERSION_NAME,
    )

    companion object {
        const val DEFAULT_PORT = 53318
        private const val KEY_DID = "did"
        private const val KEY_ALIAS = "alias"
        private const val KEY_PORT = "port"
        private const val KEY_BACKGROUND = "background"
        private const val KEY_THEME = "theme"
        private const val KEY_STORAGE = "storage"
        private const val KEY_ASK = "ask"

        fun defaultAlias(): String {
            val model = Build.MODEL?.trim().orEmpty()
            return when {
                model.isBlank() -> "我的手机"
                model.length > 24 -> model.take(24)
                else -> model
            }
        }
    }
}
