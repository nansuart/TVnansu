package com.nansu.tvnansu.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.nansu.tvnansu.data.PlayRequest
import com.nansu.tvnansu.data.db.Kind

sealed class Dest(val key: String) {
    data object Main : Dest("main")
    data class AddPlaylist(val first: Boolean) : Dest("add")
    data object Sync : Dest("sync")
    data object Profiles : Dest("profiles")
    data class ProfileEdit(val id: Long?) : Dest("profileEdit:$id")
    data class Movie(val remoteId: String) : Dest("movie:$remoteId")
    data class Series(val remoteId: String) : Dest("series:$remoteId")
    data class Grid(val kind: Kind, val categoryId: String, val title: String) : Dest("grid:${kind.name}:$categoryId")
    data class Player(val request: PlayRequest, val stamp: Long = System.nanoTime()) : Dest("player:$stamp")
}

class Navigator(start: Dest) {
    val stack = mutableStateListOf(start)

    /**
     * True right after going back. Screens only move focus to the last focused
     * item in that moment, never when switching tabs.
     */
    var restorePending: Boolean = false

    val current: Dest get() = stack.last()

    fun push(dest: Dest) {
        stack.add(dest)
    }

    fun pop(): Dest? {
        if (stack.size <= 1) return null
        restorePending = true
        return stack.removeAt(stack.lastIndex)
    }

    fun resetTo(dest: Dest) {
        stack.clear()
        stack.add(dest)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
