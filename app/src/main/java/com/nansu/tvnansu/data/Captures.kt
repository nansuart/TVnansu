package com.nansu.tvnansu.data

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream

/** A frame of a live channel saved when the user watched it, shown on its card later. */
data class Capture(val file: File, val takenAt: Long)

/**
 * Small JPEG snapshots of live channels (480 px wide, about 25 KB each), one per
 * channel, so "recent channels" can show what was on screen instead of a logo.
 */
class Captures {
    var dir: File? = null

    /** Bumped after every saved frame so cards reload. */
    val version = MutableStateFlow(0)

    private fun fileFor(playlistId: Long, remoteId: String): File? =
        dir?.let { File(it, "${playlistId}_${Repository.stableId(remoteId)}.jpg") }

    fun get(playlistId: Long, remoteId: String): Capture? {
        val f = fileFor(playlistId, remoteId) ?: return null
        if (!f.exists()) return null
        return Capture(f, f.lastModified())
    }

    /** Saves [bitmap] unless it is (almost) black, e.g. taken while the channel was loading. */
    fun save(playlistId: Long, remoteId: String, bitmap: Bitmap): Boolean {
        val f = fileFor(playlistId, remoteId) ?: return false
        if (isDark(bitmap)) return false
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        prune()
        version.value++
        return true
    }

    private fun prune(keep: Int = 80) {
        val files = dir?.listFiles { x -> x.name.endsWith(".jpg") } ?: return
        if (files.size <= keep) return
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    private fun isDark(b: Bitmap): Boolean {
        var sum = 0L
        var n = 0
        val stepX = (b.width / 16).coerceAtLeast(1)
        val stepY = (b.height / 9).coerceAtLeast(1)
        var y = stepY / 2
        while (y < b.height) {
            var x = stepX / 2
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                n++
                x += stepX
            }
            y += stepY
        }
        return n == 0 || sum / (n * 3) < 10
    }
}
