package com.example.osis_camareros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.osis_camareros.data.AppDatabase
import com.example.osis_camareros.util.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val database = remember { AppDatabase.getDatabase(applicationContext) }
            val sessionManager = remember { SessionManager(applicationContext) }

            var startDestination by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                val savedEmail = sessionManager.userEmail.first()
                startDestination = if (savedEmail != null) {
                    Route.Home.route
                } else {
                    Route.Login.route
                }
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (startDestination != null) {
                        AppNavigation(
                            database = database,
                            sessionManager = sessionManager,
                            startDestination = startDestination!!
                        )
                    }
                }
            }
        }
    }
}
