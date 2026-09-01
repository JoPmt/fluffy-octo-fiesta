package com.quantum.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BackgroundSwarmService : Service() {
    private val CHANNEL_ID = "swarm_service_runtime_channel"
    private val NOTIFICATION_ID = 4001
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cpuWakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val initialNotification = buildNotification("Agent swarm framework running offline...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        acquireWakeLock()

        @Suppress("DEPRECATION")
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getSerializableExtra("CONFIG_KEY", SwarmConfig::class.java)
        } else {
            intent?.getSerializableExtra("CONFIG_KEY") as? SwarmConfig
        } ?: SwarmConfig()

        val prompt = intent?.getStringExtra("PROMPT_KEY") ?: ""

        if (prompt.isNotBlank()) {
            serviceScope.launch {
                emitBroadcastSystemStatus("Background Swarm Engine allocated in process :swarm_engine_v1")
                emitBroadcastSystemStatus("USER PROMPT: $prompt")
                emitBroadcastSystemStatus("INITIAL MEMORY STATE: ${config.globalMemoryHistory.joinToString(separator = " | ") { "${it.senderRole}:${it.content}" }}")
                val orchestrator = SwarmOrchestrator(
                    context = applicationContext,
                    config = config,
                    onTelemetryEmit = { r, th, tl, p -> emitBroadcastTelemetry(r, th, tl, p) },
                    onSystemStatusEmit = { s -> emitBroadcastSystemStatus(s) }
                )
                orchestrator.coordinateSwarmExecution(prompt)
                stopCleanupSequence()
            }
        } else {
            stopCleanupSequence()
        }

        return START_NOT_STICKY
    }

    private fun emitBroadcastTelemetry(role: String, thought: String, tool: String, params: String) {
        val intent = Intent(SwarmBroadcastContract.ACTION_SWARM_TELEMETRY).apply {
            setPackage(packageName)
            putExtra(SwarmBroadcastContract.EXTRA_ROLE, role)
            putExtra(SwarmBroadcastContract.EXTRA_THOUGHT, thought)
            putExtra(SwarmBroadcastContract.EXTRA_TOOL, tool)
            putExtra(SwarmBroadcastContract.EXTRA_PARAMS, params)
        }
        sendBroadcast(intent)
    }

    private fun emitBroadcastSystemStatus(msg: String) {
        val intent = Intent(SwarmBroadcastContract.ACTION_SWARM_TELEMETRY).apply {
            setPackage(packageName)
            putExtra(SwarmBroadcastContract.EXTRA_SYSTEM_STATUS, msg)
        }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuantumSwarm::ProcessingWakeLock").apply {
                setReferenceCounted(false)
                acquire(20 * 60 * 1000L)
            }
        } catch (e: Exception) {
            // Ignored if wake lock cannot be acquired
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Quantum Swarm Core Processing Engine")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Runtime Services Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background local LLM swarm computation and tool execution"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopCleanupSequence() {
        serviceScope.cancel()
        try {
            if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release()
        } catch (e: Exception) {}
        NativeEngine().deallocateEngine()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCleanupSequence()
        super.onDestroy()
    }
}
