package com.nansu.tvnansu.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tvnansu", Context.MODE_PRIVATE)

    var activePlaylistId: Long
        get() = sp.getLong("active_playlist", 0L)
        set(v) = sp.edit().putLong("active_playlist", v).apply()

    /** "ts" or "m3u8" for Xtream live streams. */
    var liveFormat: String
        get() = sp.getString("live_format", "ts") ?: "ts"
        set(v) = sp.edit().putString("live_format", v).apply()

    var reduceMotion: Boolean
        get() = sp.getBoolean("reduce_motion", false)
        set(v) = sp.edit().putBoolean("reduce_motion", v).apply()

    /** Hours between automatic refreshes of the playlist. 0 = never. */
    var autoRefreshHours: Int
        get() = sp.getInt("auto_refresh_hours", 24)
        set(v) = sp.edit().putInt("auto_refresh_hours", v).apply()
}
