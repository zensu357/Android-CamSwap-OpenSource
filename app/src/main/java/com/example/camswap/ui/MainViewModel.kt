package com.example.camswap.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camswap.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MainUiState(
    val isModuleDisabled: Boolean = false,
    val forceShowWarning: Boolean = false,
    val playVideoSound: Boolean = false,
    val forcePrivateDir: Boolean = false,
    val disableToast: Boolean = false,
    val enableRandomPlay: Boolean = false,
    val enableMicHook: Boolean = false,
    val micHookMode: String = "mute",

    val notificationControlEnabled: Boolean = false,
    val hasPermission: Boolean = false,
    val isXposedActive: Boolean = false,
    val targetAppsCount: Int = 0,
    val originalVideoName: String? = null,
    val latestVersion: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val configManager = ConfigManager()
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
        checkLatestVersion()
    }

    fun loadConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.reload()
            _uiState.update { currentState ->
                currentState.copy(
                    isModuleDisabled = configManager.getBoolean(ConfigManager.KEY_DISABLE_MODULE, false),
                    forceShowWarning = configManager.getBoolean(ConfigManager.KEY_FORCE_SHOW_WARNING, false),
                    playVideoSound = configManager.getBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, false),
                    forcePrivateDir = configManager.getBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, false),
                    disableToast = configManager.getBoolean(ConfigManager.KEY_DISABLE_TOAST, false),
                    enableRandomPlay = configManager.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false),
                    enableMicHook = configManager.getBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, false),
                    micHookMode = configManager.getString(ConfigManager.KEY_MIC_HOOK_MODE, ConfigManager.MIC_MODE_MUTE),
                    notificationControlEnabled = configManager.getBoolean("notification_control_enabled", false),
                    targetAppsCount = configManager.targetPackages.size,
                    originalVideoName = configManager.getString(ConfigManager.KEY_ORIGINAL_VIDEO_NAME, null)
                )
            }
        }
    }

    fun setModuleDisabled(disabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_DISABLE_MODULE, disabled)
            _uiState.update { it.copy(isModuleDisabled = disabled) }
        }
    }

    fun setForceShowWarning(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_FORCE_SHOW_WARNING, enabled)
            _uiState.update { it.copy(forceShowWarning = enabled) }
        }
    }

    fun setPlayVideoSound(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_PLAY_VIDEO_SOUND, enabled)
            _uiState.update { it.copy(playVideoSound = enabled) }
        }
    }

    fun setForcePrivateDir(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_FORCE_PRIVATE_DIR, enabled)
            _uiState.update { it.copy(forcePrivateDir = enabled) }
        }
    }

    fun setDisableToast(disabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_DISABLE_TOAST, disabled)
            _uiState.update { it.copy(disableToast = disabled) }
        }
    }

    fun setEnableRandomPlay(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, enabled)
            _uiState.update { it.copy(enableRandomPlay = enabled) }
        }
    }

    fun setEnableMicHook(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean(ConfigManager.KEY_ENABLE_MIC_HOOK, enabled)
            _uiState.update { it.copy(enableMicHook = enabled) }
        }
    }

    fun setMicHookMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setString(ConfigManager.KEY_MIC_HOOK_MODE, mode)
            _uiState.update { it.copy(micHookMode = mode) }
        }
    }

    fun setNotificationControlEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configManager.setBoolean("notification_control_enabled", enabled)
            _uiState.update { it.copy(notificationControlEnabled = enabled) }
        }
    }



    fun updatePermissionStatus(hasPermission: Boolean) {
        _uiState.update { it.copy(hasPermission = hasPermission) }
    }

    fun updateXposedStatus(isActive: Boolean) {
        _uiState.update { it.copy(isXposedActive = isActive) }
    }

    fun setLanguage(context: Context, language: String) {
        com.example.camswap.utils.LocaleHelper.setLocale(context, language)
        // Restart app to apply changes
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun checkLatestVersion() {
        // 无网络权限，通过 LSPosed 托管更新
        _uiState.update { it.copy(latestVersion = null) }
    }
}
