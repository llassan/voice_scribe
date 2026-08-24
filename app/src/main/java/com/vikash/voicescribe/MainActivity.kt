package com.vikash.voicescribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vikash.voicescribe.ui.DetailScreen
import com.vikash.voicescribe.ui.HomeScreen
import com.vikash.voicescribe.ui.ModelsScreen
import com.vikash.voicescribe.ui.VoiceScribeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            VoiceScribeTheme {
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
