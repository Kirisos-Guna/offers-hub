package com.offershub.app.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offershub.app.R
import com.offershub.app.data.AlertMode
import com.offershub.app.data.AppSettings
import com.offershub.app.data.ThemeMode

private val DISCOUNT_STEPS = listOf(0, 50, 70, 80)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    dealCount: Int,
    update: UpdateState,
    installedVersion: String,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle()
    val notifications by settings.notifications.collectAsStateWithLifecycle()
    val minDiscount by settings.minDiscount.collectAsStateWithLifecycle()
    val alertMode by settings.alertMode.collectAsStateWithLifecycle()
    val keywords by settings.keywords.collectAsStateWithLifecycle()
    var newKeyword by rememberSaveable { mutableStateOf("") }
    val themeLabels = mapOf(
        ThemeMode.System to stringResource(R.string.theme_system),
        ThemeMode.Light to stringResource(R.string.theme_light),
        ThemeMode.Dark to stringResource(R.string.theme_dark),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(stringResource(R.string.section_appearance)) {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { settings.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        ) { Text(themeLabels.getValue(mode), maxLines = 1, softWrap = false) }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        title = stringResource(R.string.wallpaper_colours),
                        subtitle = stringResource(R.string.wallpaper_colours_desc),
                        checked = dynamicColor,
                        onChange = settings::setDynamicColor,
                    )
                }
                // Android 13+ has a per-app language page; it lists English, Tamil and Hindi.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp)
                            .clickable {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri())
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.language_desc), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }

            Section(stringResource(R.string.section_notifications)) {
                SwitchRow(
                    title = stringResource(R.string.alerts_title),
                    subtitle = stringResource(R.string.alerts_desc),
                    checked = notifications,
                    onChange = settings::setNotifications,
                )
                if (notifications) {
                    Text(
                        stringResource(R.string.alert_for),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        AlertMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = alertMode == mode,
                                onClick = { settings.setAlertMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, AlertMode.entries.size),
                            ) {
                                Text(
                                    stringResource(if (mode == AlertMode.AllDeals) R.string.alert_all else R.string.alert_keywords),
                                    maxLines = 1, softWrap = false,
                                )
                            }
                        }
                    }
                    if (alertMode == AlertMode.KeywordsOnly) {
                        OutlinedTextField(
                            value = newKeyword,
                            onValueChange = { newKeyword = it },
                            placeholder = { Text(stringResource(R.string.keywords_hint), maxLines = 1) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { settings.addKeyword(newKeyword); newKeyword = "" }),
                            trailingIcon = {
                                TextButton(onClick = { settings.addKeyword(newKeyword); newKeyword = "" },
                                    enabled = newKeyword.isNotBlank()) { Text(stringResource(R.string.keyword_add)) }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        )
                        if (keywords.isEmpty()) {
                            Text(stringResource(R.string.keywords_empty), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        } else {
                            FlowRow(
                                Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                keywords.forEach { word ->
                                    InputChip(
                                        selected = false,
                                        onClick = { settings.removeKeyword(word) },
                                        label = { Text(word, maxLines = 1) },
                                        trailingIcon = {
                                            Icon(Icons.Filled.Close, stringResource(R.string.cd_remove_keyword, word),
                                                Modifier.size(16.dp))
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.min_discount_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    // Wraps to a second line with large fonts or narrow screens instead of squeezing.
                    FlowRow(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        DISCOUNT_STEPS.forEach { step ->
                            FilterChip(
                                selected = minDiscount == step,
                                onClick = { settings.setMinDiscount(step) },
                                label = {
                                    Text(
                                        if (step == 0) stringResource(R.string.any) else stringResource(R.string.percent_off, step),
                                        maxLines = 1, softWrap = false,
                                    )
                                },
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(stringResource(R.string.notif_settings)) }
            }

            Section(stringResource(R.string.section_updates)) {
                InfoRow(stringResource(R.string.installed_version), installedVersion)
                val status = when (update) {
                    UpdateState.Checking -> stringResource(R.string.checking)
                    UpdateState.UpToDate -> stringResource(R.string.up_to_date)
                    is UpdateState.Failed -> update.message
                    is UpdateState.Available -> stringResource(R.string.update_status_available, update.update.version)
                    is UpdateState.Downloading -> stringResource(R.string.update_status_downloading, update.update.version)
                    is UpdateState.Ready -> stringResource(R.string.update_status_ready, update.update.version)
                    UpdateState.None -> null
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (update is UpdateState.Available || update is UpdateState.Ready) {
                        Button(onClick = onOpenUpdate) { Text(stringResource(R.string.update_now)) }
                    } else {
                        OutlinedButton(onClick = onCheckUpdate, enabled = update != UpdateState.Checking) {
                            Text(stringResource(R.string.check_updates))
                        }
                    }
                }
            }

            Section(stringResource(R.string.section_about)) {
                InfoRow(stringResource(R.string.deals_now), dealCount.toString())
                Text(
                    stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
