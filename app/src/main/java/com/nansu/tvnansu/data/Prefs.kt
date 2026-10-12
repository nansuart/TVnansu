package com.nansu.tvnansu.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tvnansu", Context.MODE_PRIVATE)

    /** Profile whose preferences are read below; set by the repository. */
    var profileId: Long = 1L

    private fun pk(key: String) = "${key}_p$profileId"

    var activeProfileId: Long
        get() = sp.getLong("active_profile", 1L)
        set(v) = sp.edit().putLong("active_profile", v).apply()

    var activePlaylistId: Long
        get() = sp.getLong("active_playlist", 0L)
        set(v) = sp.edit().putLong("active_playlist", v).apply()

    /** "ts" or "m3u8" for Xtream live streams. */
    var liveFormat: String
        get() = sp.getString("live_format", "ts") ?: "ts"
        set(v) = sp.edit().putString("live_format", v).apply()

    var reduceMotion: Boolean
        get() = sp.getBoolean(pk("reduce_motion"), sp.getBoolean("reduce_motion", false))
        set(v) = sp.edit().putBoolean(pk("reduce_motion"), v).apply()

    /** 0 small, 1 normal, 2 large, 3 extra large. */
    var subtitleSize: Int
        get() = sp.getInt(pk("subtitle_size"), sp.getInt("subtitle_size", 1))
        set(v) = sp.edit().putInt(pk("subtitle_size"), v).apply()

    /** Dark box behind subtitles instead of an outline. */
    var subtitleBackground: Boolean
        get() = sp.getBoolean(pk("subtitle_background"), sp.getBoolean("subtitle_background", false))
        set(v) = sp.edit().putBoolean(pk("subtitle_background"), v).apply()

    /** Channel logos: 0 colour (smart background), 1 white. */
    var logoStyle: Int
        get() = sp.getInt("logo_style", 0)
        set(v) = sp.edit().putInt("logo_style", v).apply()

    /** Hours between automatic refreshes of the playlist. 0 = never. */
    var autoRefreshHours: Int
        get() = sp.getInt("auto_refresh_hours", 24)
        set(v) = sp.edit().putInt("auto_refresh_hours", v).apply()
}
