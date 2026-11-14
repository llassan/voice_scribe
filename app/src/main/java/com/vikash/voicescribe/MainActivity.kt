package com.vikash.voicescribe

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vikash.voicescribe.ui.DetailScreen
import com.vikash.voicescribe.ui.HomeScreen
import com.vikash.voicescribe.ui.ModelsScreen
import com.vikash.voicescribe.ui.VoiceScribeTheme
import com.vikash.voicescribe.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            val themeMode by app.settings.themeMode.collectAsState()
            // enableEdgeToEdge() picks the status/navigation icon colours from the
            // SYSTEM light-dark setting, which is wrong the moment the user pins a
            // theme the phone doesn't share: a dark app under a light phone was
            // drawing a dark clock on its own dark background. Re-apply on every
            // change so the bar icons follow the theme the app is actually showing.
            val dark = isDarkTheme(themeMode)
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            VoiceScribeTheme(mode = themeMode) {
                AppNav(app)
            }
        }
    }
}

@Composable
private fun AppNav(app: App) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                app = app,
                onOpenRecording = { nav.navigate("detail/$it") },
                onOpenModels = { nav.navigate("models") },
            )
        }
        composable("detail/{id}") { backStack ->
            val id = backStack.arguments?.getString("id") ?: return@composable
            DetailScreen(app = app, recordingId = id, onBack = { nav.popBackStack() })
        }
        composable("models") {
            ModelsScreen(app = app, onBack = { nav.popBackStack() })
        }
    }
}
