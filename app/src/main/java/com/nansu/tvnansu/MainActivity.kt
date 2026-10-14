package com.nansu.tvnansu

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.nansu.tvnansu.ui.AppRoot
import com.nansu.tvnansu.ui.LocalRepository
import com.nansu.tvnansu.ui.components.UserActivity
import com.nansu.tvnansu.ui.theme.TVnansuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as TVnansuApp).repository
        setContent {
            CompositionLocalProvider(LocalRepository provides repository) {
                TVnansuTheme {
                    AppRoot()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        UserActivity.touch()
        return super.dispatchKeyEvent(event)
    }
}
