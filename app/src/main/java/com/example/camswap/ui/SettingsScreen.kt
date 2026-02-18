package com.example.camswap.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camswap.BuildConfig
import com.example.camswap.R

@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ==================== General Settings ====================
        SettingsSection(title = stringResource(R.string.settings_category_general)) {
            SettingsSwitchRow(
                icon = Icons.Default.NotificationsActive,
                title = stringResource(R.string.settings_notification_control),
                subtitle = stringResource(R.string.settings_notification_control_desc),
                checked = uiState.notificationControlEnabled,
                onCheckedChange = {
                    viewModel.setNotificationControlEnabled(it)
                    val intent = Intent(context, com.example.camswap.NotificationService::class.java)
                    if (it) {
                        context.startForegroundService(intent)
                    } else {
                        context.stopService(intent)
                    }
                }
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Default.VolumeUp,
                title = stringResource(R.string.settings_play_sound),
                subtitle = stringResource(R.string.settings_play_sound_desc),
                checked = uiState.playVideoSound,
                onCheckedChange = { viewModel.setPlayVideoSound(it) }
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Default.Mic,
                title = stringResource(R.string.settings_mic_hook),
                subtitle = stringResource(R.string.settings_mic_hook_desc),
                checked = uiState.enableMicHook,
                onCheckedChange = { viewModel.setEnableMicHook(it) }
            )

            // Mic Hook mode selection (shown only when enabled)
            AnimatedVisibility(
                visible = uiState.enableMicHook,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 40.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    MicModeOption(
                        title = stringResource(R.string.mic_mode_mute),
                        description = stringResource(R.string.mic_mode_mute_desc),
                        selected = uiState.micHookMode == "mute",
                        onClick = { viewModel.setMicHookMode("mute") }
                    )
                    MicModeOption(
                        title = stringResource(R.string.mic_mode_replace),
                        description = stringResource(R.string.mic_mode_replace_desc),
                        selected = uiState.micHookMode == "replace",
                        onClick = { viewModel.setMicHookMode("replace") }
                    )
                    MicModeOption(
                        title = stringResource(R.string.mic_mode_video_sync),
                        description = stringResource(R.string.mic_mode_video_sync_desc),
                        selected = uiState.micHookMode == "video_sync",
                        onClick = { viewModel.setMicHookMode("video_sync") }
                    )
                }
            }

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Default.Shuffle,
                title = stringResource(R.string.settings_random_play),
                subtitle = stringResource(R.string.settings_random_play_desc),
                checked = uiState.enableRandomPlay,
                onCheckedChange = { viewModel.setEnableRandomPlay(it) }
            )
        }

        // ==================== Advanced Settings ====================
        SettingsSection(title = stringResource(R.string.settings_category_advanced)) {
            SettingsSwitchRow(
                icon = Icons.Outlined.PowerSettingsNew,
                title = stringResource(R.string.settings_disable_module),
                subtitle = stringResource(R.string.settings_disable_module_desc),
                checked = uiState.isModuleDisabled,
                onCheckedChange = { viewModel.setModuleDisabled(it) }
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Outlined.Warning,
                title = stringResource(R.string.settings_force_warning),
                subtitle = stringResource(R.string.settings_force_warning_desc),
                checked = uiState.forceShowWarning,
                onCheckedChange = { viewModel.setForceShowWarning(it) }
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Outlined.FolderSpecial,
                title = stringResource(R.string.settings_force_private_dir),
                subtitle = stringResource(R.string.settings_force_private_dir_desc),
                checked = uiState.forcePrivateDir,
                onCheckedChange = { viewModel.setForcePrivateDir(it) }
            )

            SettingsDivider()

            SettingsSwitchRow(
                icon = Icons.Outlined.NotificationsOff,
                title = stringResource(R.string.settings_disable_toast),
                subtitle = stringResource(R.string.settings_disable_toast_desc),
                checked = uiState.disableToast,
                onCheckedChange = { viewModel.setDisableToast(it) }
            )

            SettingsDivider()

            SettingsClickRow(
                icon = Icons.Default.Security,
                title = stringResource(R.string.settings_system_permission),
                subtitle = stringResource(R.string.settings_system_permission_desc),
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            )

            SettingsDivider()

            // Language Settings
            val currentLanguage = com.example.camswap.utils.LocaleHelper.getLanguage(context)
            var showLanguageDialog by remember { mutableStateOf(false) }

            SettingsClickRow(
                icon = Icons.Default.Language,
                title = stringResource(R.string.settings_language),
                subtitle = when (currentLanguage) {
                    "en" -> stringResource(R.string.language_en)
                    "zh" -> stringResource(R.string.language_zh)
                    else -> stringResource(R.string.language_system_default)
                },
                onClick = { showLanguageDialog = true }
            )

            if (showLanguageDialog) {
                AlertDialog(
                    onDismissRequest = { showLanguageDialog = false },
                    title = { Text(stringResource(R.string.settings_language)) },
                    text = {
                        Column {
                            LanguageOption(
                                label = stringResource(R.string.language_system_default),
                                selected = currentLanguage == "",
                                onClick = {
                                    viewModel.setLanguage(context, "")
                                    showLanguageDialog = false
                                }
                            )
                            LanguageOption(
                                label = stringResource(R.string.language_en),
                                selected = currentLanguage == "en",
                                onClick = {
                                    viewModel.setLanguage(context, "en")
                                    showLanguageDialog = false
                                }
                            )
                            LanguageOption(
                                label = stringResource(R.string.language_zh),
                                selected = currentLanguage == "zh",
                                onClick = {
                                    viewModel.setLanguage(context, "zh")
                                    showLanguageDialog = false
                                }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLanguageDialog = false }) {
                            Text(stringResource(R.string.positive))
                        }
                    }
                )
            }
        }

        // ==================== About ====================
        SettingsSection(title = stringResource(R.string.about_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_app_name),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.about_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SettingsDivider()

            InfoRow(
                label = stringResource(R.string.version_current),
                value = BuildConfig.VERSION_NAME
            )
            if (BuildConfig.BUILD_TIME.isNotEmpty()) {
                InfoRow(
                    label = "Build Time",
                    value = BuildConfig.BUILD_TIME
                )
            }

            SettingsDivider()

            SettingsClickRow(
                icon = Icons.Default.Code,
                title = "GitHub",
                subtitle = stringResource(R.string.support_github),
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zensu357/Android-CamSwap-OpenSource"))
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ==================== Reusable Components ====================

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsClickRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun MicModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsDivider() {
    @Suppress("DEPRECATION")
    Divider(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
        modifier = Modifier.padding(start = 36.dp)
    )
}
