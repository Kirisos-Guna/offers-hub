package com.offershub.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/** User preferences, observable so the UI (theme, notification filters) reacts immediately. */
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("offers_hub", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _notifications = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY, true))
    val notifications: StateFlow<Boolean> = _notifications.asStateFlow()

    /** Only announce deals with at least this discount (0 = every deal). */
    private val _minDiscount = MutableStateFlow(prefs.getInt(KEY_MIN_DISCOUNT, 0))
    val minDiscount: StateFlow<Int> = _minDiscount.asStateFlow()

    /** posted_at of the newest deal already shown or announced (static-feed mode only). */
    var lastSeenPostedAt: Long
        get() = prefs.getLong(KEY_LAST_SEEN, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_SEEN, value) }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC, enabled) }
        _dynamicColor.value = enabled
    }

    fun setNotifications(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFY, enabled) }
        _notifications.value = enabled
    }

    fun setMinDiscount(percent: Int) {
        prefs.edit { putInt(KEY_MIN_DISCOUNT, percent) }
        _minDiscount.value = percent
    }

    /** Whether a deal passes the user's notification filter. */
    fun wantsNotification(discount: Int?): Boolean =
        notifications.value && (minDiscount.value == 0 || (discount ?: 0) >= minDiscount.value)

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_NOTIFY = "notifications"
        private const val KEY_MIN_DISCOUNT = "notify_min_discount"
        private const val KEY_LAST_SEEN = "last_seen_posted_at"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
