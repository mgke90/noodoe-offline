package com.noodoe.offline.data

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("noodoe_offline", Context.MODE_PRIVATE)

    /** 0 = 12-hour, 1 = 24-hour. */
    var timeFormat: Int
        get() = prefs.getInt("time_format", 1)
        set(value) = prefs.edit().putInt("time_format", value).apply()

    var showLiveLogs: Boolean
        get() = prefs.getBoolean("show_live_logs", true)
        set(value) = prefs.edit().putBoolean("show_live_logs", value).apply()

    /** Default off: reopening the app must not overwrite a custom clock. */
    var autoSyncClock: Boolean
        get() = prefs.getBoolean("auto_sync_clock", false)
        set(value) = prefs.edit().putBoolean("auto_sync_clock", value).apply()

    var lastClockText: String?
        get() = prefs.getString("last_clock_text", null)
        set(value) = prefs.edit().putString("last_clock_text", value).apply()

    /** True only when the saved clock came from SET CUSTOM. */
    var lastClockWasCustom: Boolean
        get() = prefs.getBoolean("last_clock_custom", false)
        set(value) = prefs.edit().putBoolean("last_clock_custom", value).apply()

    /** Wall-clock time when lastClockText was stored, for custom-clock drift. */
    var lastClockSetAt: Long
        get() = prefs.getLong("last_clock_set_at", 0L)
        set(value) = prefs.edit().putLong("last_clock_set_at", value).apply()

    /** 0 = km, 1 = miles. */
    var distanceUnit: Int
        get() = prefs.getInt("distance_unit", 0)
        set(value) = prefs.edit().putInt("distance_unit", value).apply()

    /** 0 = metric, 1 = imperial. */
    var temperatureUnit: Int
        get() = prefs.getInt("temperature_unit", 0)
        set(value) = prefs.edit().putInt("temperature_unit", value).apply()

    /** 0..100. */
    var brightness: Int
        get() = prefs.getInt("brightness", 50)
        set(value) = prefs.edit().putInt("brightness", value).apply()

    /** SUPPORT_LANGUAGE value; 255 = do not change. */
    var language: Int
        get() = prefs.getInt("language", 255)
        set(value) = prefs.edit().putInt("language", value).apply()

    var lastDeviceAddress: String?
        get() = prefs.getString("last_device_address", null)
        set(value) = prefs.edit().putString("last_device_address", value).apply()

    var lastDeviceName: String?
        get() = prefs.getString("last_device_name", null)
        set(value) = prefs.edit().putString("last_device_name", value).apply()

    var notifyApps: Boolean
        get() = prefs.getBoolean("notify_apps", false)
        set(value) = prefs.edit().putBoolean("notify_apps", value).apply()

    var notifyCalls: Boolean
        get() = prefs.getBoolean("notify_calls", false)
        set(value) = prefs.edit().putBoolean("notify_calls", value).apply()

    var notifySms: Boolean
        get() = prefs.getBoolean("notify_sms", false)
        set(value) = prefs.edit().putBoolean("notify_sms", value).apply()

    /** Off by default: some dashboards never report key-on in RIDING_STATUS. */
    var notifyOnlyKeyOn: Boolean
        get() = prefs.getBoolean("notify_only_key_on", false)
        set(value) = prefs.edit().putBoolean("notify_only_key_on", value).apply()

    /** Foreground service keeps the SPP link ready for notifications. */
    var keepAlive: Boolean
        get() = prefs.getBoolean("keep_alive", false)
        set(value) = prefs.edit().putBoolean("keep_alive", value).apply()
}
