package com.hispanoiptv.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.hispanoiptv.tv.ui.AppRoot
import com.hispanoiptv.tv.ui.LocalRepository
import com.hispanoiptv.tv.ui.components.UserActivity
import com.hispanoiptv.tv.ui.theme.HispanoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as HispanoApp).repository
        setContent {
            CompositionLocalProvider(LocalRepository provides repository) {
                HispanoTheme {
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
