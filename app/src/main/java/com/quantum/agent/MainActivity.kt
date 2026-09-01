package com.quantum.agent

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val terminalViewModel: TerminalViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WorkspaceContainer(
                viewModel = terminalViewModel,
                chatViewModel = chatViewModel,
                onRun = { inputPrompt, currentSwarmConfig ->
                    terminalViewModel.logSystem("Launching swarm execution command pipeline...")

                    // Route configuration data and instruction parameters to background process
                    val serviceIntent = Intent(this, BackgroundSwarmService::class.java).apply {
                        putExtra("CONFIG_KEY", currentSwarmConfig)
                        putExtra("PROMPT_KEY", inputPrompt)
                    }

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        terminalViewModel.logSystem("Execution initialization failed: ${e.message}")
                    }
                }
            )
        }
    }
}
