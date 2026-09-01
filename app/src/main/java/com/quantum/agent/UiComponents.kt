package com.quantum.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConsoleLine(
    val sender: String,
    val message: String,
    val tool: String = "",
    val params: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class TerminalViewModel : ViewModel() {
    val consoleLog = mutableStateListOf<ConsoleLine>()
    var activeEngineStatus by mutableStateOf("IDLE - Awaiting Instructions")
        private set
    var isExecuting by mutableStateOf(false)
        private set

    init {
        consoleLog.add(
            ConsoleLine(
                sender = "SYSTEM",
                message = "Quantum Swarm Core v1.0.0 initialized in isolated process (:swarm_engine_v1)."
            )
        )
        consoleLog.add(
            ConsoleLine(
                sender = "SYSTEM",
                message = "ARM64-v8a NEON & Vector optimizations online. MCP bridge active."
            )
        )
    }

    fun logSystem(msg: String) {
        activeEngineStatus = msg
        isExecuting = !msg.contains("concluded", ignoreCase = true) &&
                !msg.contains("threshold reached", ignoreCase = true) &&
                !msg.contains("cleared", ignoreCase = true) &&
                !msg.contains("IDLE", ignoreCase = true)
        consoleLog.add(ConsoleLine(sender = "SYSTEM", message = msg))
    }

    fun logAgent(role: String, thought: String, tool: String, params: String) {
        isExecuting = true
        activeEngineStatus = "Active: $role reasoning..."
        consoleLog.add(
            ConsoleLine(
                sender = role.uppercase(),
                message = thought,
                tool = tool,
                params = params
            )
        )
    }

    fun clear() {
        consoleLog.clear()
        isExecuting = false
        logSystem("Telemetry console memory cleared.")
    }
}

class ChatViewModel : ViewModel() {
    val messages = mutableStateListOf<MessageLog>()
    var isGenerating by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private suspend fun streamResponseToConsole(response: String) {
        val placeholder = MessageLog("MODEL", "")
        messages.add(placeholder)
        val outputChunks = response.chunked(8)
        for (chunk in outputChunks) {
            val lastIndex = messages.lastIndex
            if (lastIndex >= 0) {
                val last = messages[lastIndex]
                messages[lastIndex] = last.copy(content = last.content + chunk)
            }
            kotlinx.coroutines.delay(24)
        }
    }

    fun send(message: String, config: SwarmConfig) {
        if (message.isBlank() || isGenerating) return
        val activeModelPath = config.selectedModelPath
        
        // Enhanced validation with better error messaging
        if (activeModelPath.isBlank()) {
            errorMessage = "No model selected. Please download and load a GGUF model in the NETWORK tab first."
            return
        }
        
        if (!NativeEngine.isUsableSessionModel(activeModelPath)) {
            val file = File(activeModelPath)
            errorMessage = when {
                !file.exists() -> "Model file not found: $activeModelPath. The file may have been deleted. Download a new model in the NETWORK tab."
                !file.canRead() -> "Cannot read model file: $activeModelPath. Check file permissions or re-download the model."
                !file.name.lowercase().endsWith(".gguf") -> "Invalid model file: $activeModelPath. Must be a .gguf file. Download a GGUF model from the NETWORK tab."
                file.length() == 0L -> "Model file is empty (0 bytes): $activeModelPath. The file is corrupted. Download a fresh model."
                file.length() < 10 * 1024 * 1024 -> "Model file is incomplete: ${file.length() / (1024*1024)}MB. Expected at least 10MB. Re-download the model."
                else -> "Model file validation failed: $activeModelPath. The GGUF model may be corrupted or incompatible. Try downloading a different model."
            }
            return
        }

        messages.add(MessageLog("USER", message))
        isGenerating = true
        errorMessage = null
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val engine = NativeEngine()
            val response = try {
                val initialized = engine.initializeEngineWithCache(
                    activeModelPath,
                    config.kvCacheSize,
                    config.threadClampCount,
                    config.cachePrecision.bitValue
                )
                if (!initialized) {
                    errorMessage = "Model initialization failed. Possible causes:\\n" +
                        "• GGUF file is corrupted or incomplete\\n" +
                        "• Unsupported GGUF format version\\n" +
                        "• Model architecture not compatible\\n" +
                        "• Insufficient device memory (OOM)\\n\\n" +
                        "Try downloading a fresh model in the NETWORK tab, or verify the model file is valid.\\n" +
                        "Check logcat for detailed native diagnostics."
                    null
                } else {
                    // Apply sampler parameters before inference
                    engine.setSamplerParams(
                        config.temperature,
                        config.topK,
                        config.topP,
                        config.minP,
                        config.repeatPenalty
                    )
                    when (config.inferenceMode) {
                        ChatInferenceMode.SOLO -> engine.generatePlainChatCompletion(
                            activeModelPath,
                            message,
                            config.kvCacheSize,
                            config.threadClampCount,
                            config.cachePrecision.bitValue
                        )
                        ChatInferenceMode.SWARM -> {
                            val systemPrompt = "You are the local Quantum Swarm chat model. Answer the user's message directly and concisely."
                            engine.executeAgentTurn(systemPrompt, message)
                        }
                    }
                }
            } catch (error: Throwable) {
                errorMessage = "Model engine error: ${error.localizedMessage ?: error.message ?: "Unknown error"}"
                null
            } finally {
                engine.deallocateEngine()
            }

            if (response != null) {
                if (config.inferenceMode == ChatInferenceMode.SOLO) {
                    streamResponseToConsole(response)
                } else {
                    messages.add(MessageLog("MODEL", response))
                }
            }
            isGenerating = false
        }
    }
}

@Composable
fun ChatScreen(viewModel: ChatViewModel, config: SwarmConfig, onConfigChanged: (SwarmConfig) -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) listState.animateScrollToItem(viewModel.messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "CHAT MODE",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00FF66)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (config.inferenceMode == ChatInferenceMode.SOLO) "SOLO" else "SWARM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF00E5FF)
                )
                Switch(
                    checked = config.inferenceMode == ChatInferenceMode.SOLO,
                    onCheckedChange = { enabled ->
                        onConfigChanged(config.copy(inferenceMode = if (enabled) ChatInferenceMode.SOLO else ChatInferenceMode.SWARM))
                    },
                    enabled = !viewModel.isGenerating,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FF66),
                        checkedTrackColor = Color(0xFF003820),
                        uncheckedThumbColor = Color(0xFFFFB300),
                        uncheckedTrackColor = Color(0xFF2F2A1B)
                    )
                )
            }
        }

        Surface(
            color = Color(0xFF050608),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("DIRECT MODEL TERMINAL", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00FF66))
                }
                items(viewModel.messages) { message ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(message.senderRole, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (message.senderRole == "USER") Color(0xFF00E5FF) else Color(0xFF00FF66))
                        Text(message.content, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFE0F7FA), modifier = Modifier.padding(top = 3.dp))
                    }
                }
                if (viewModel.isGenerating) {
                    item { Text("MODEL > PROCESSING...", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF8F93A2)) }
                }
                viewModel.errorMessage?.let { error ->
                    item { Text("ERROR > $error", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFFF6B6B)) }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).testTag("chat_input"),
                placeholder = { Text("Send a prompt to the local engine", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    viewModel.send(input, config)
                    input = ""
                    keyboardController?.hide()
                }),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0xFF050608), unfocusedContainerColor = Color(0xFF050608), focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color(0xFF00FF66), unfocusedBorderColor = Color(0xFF232731)),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
            IconButton(onClick = { viewModel.send(input, config); input = "" }, enabled = input.isNotBlank() && !viewModel.isGenerating, modifier = Modifier.size(52.dp).testTag("chat_send_button")) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message", tint = Color(0xFF00FF66))
            }
        }
    }
}

@Composable
fun RegisterSwarmReceiver(viewModel: TerminalViewModel) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == SwarmBroadcastContract.ACTION_SWARM_TELEMETRY) {
                    val status = intent.getStringExtra(SwarmBroadcastContract.EXTRA_SYSTEM_STATUS)
                    if (status != null) {
                        viewModel.logSystem(status)
                    } else {
                        viewModel.logAgent(
                            role = intent.getStringExtra(SwarmBroadcastContract.EXTRA_ROLE) ?: "UNKNOWN",
                            thought = intent.getStringExtra(SwarmBroadcastContract.EXTRA_THOUGHT) ?: "",
                            tool = intent.getStringExtra(SwarmBroadcastContract.EXTRA_TOOL) ?: "",
                            params = intent.getStringExtra(SwarmBroadcastContract.EXTRA_PARAMS) ?: ""
                        )
                    }
                }
            }
        }
        val filter = IntentFilter(SwarmBroadcastContract.ACTION_SWARM_TELEMETRY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Ignore receiver unregistered exception
            }
        }
    }
}

@Composable
fun WorkspaceContainer(
    viewModel: TerminalViewModel,
    chatViewModel: ChatViewModel,
    onRun: (String, SwarmConfig) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val defaultModelPath = remember(context) { LocalModelStore.resolveDefaultModelPath(context.getExternalFilesDir(null)) }
    var config by remember {
        mutableStateOf(
            SwarmConfig(
                selectedModelPath = defaultModelPath,
                selectedModelName = if (defaultModelPath.isBlank()) "No model selected" else defaultModelPath
            )
        )
    }
    val tabs = listOf("CONSOLE", "CHAT", "CONTROL", "TOOLS", "NETWORK")

    RegisterSwarmReceiver(viewModel)

    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E11))
            .systemBarsPadding()
    ) {
        // Immersive Header
        Surface(
            color = Color(0xFF0D0E11),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Quantum Swarm",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = (-0.5).sp,
                        color = Color(0xFF00FF66)
                    )
                    Text(
                        text = "CORE v1.0.0 — arm64-v8a",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp,
                        color = Color(0xFF8F93A2)
                    )
                }

                Surface(
                    color = Color(0xFF14171E),
                    shape = RoundedCornerShape(percent = 50),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color(0xFF00FF66).copy(alpha = 0.2f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (viewModel.isExecuting) Color(0xFF00FF66) else Color(0xFF00E5FF))
                                .alpha(if (viewModel.isExecuting) pulseAlpha else 0.8f)
                        )
                        Text(
                            text = if (viewModel.isExecuting) "ENGINE ACTIVE" else "ENGINE STANDBY",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FF66)
                        )
                    }
                }
            }
        }

        // Navigation Tabs (Immersive border bottom & monospace uppercase)
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF14171E),
            contentColor = Color(0xFF00FF66),
            divider = { HorizontalDivider(color = Color(0xFF232731), thickness = 1.dp) },
            indicator = { tabPositions ->
                if (selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color(0xFF00FF66),
                        height = 2.dp
                    )
                }
            }
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Tab(
                    selected = isSelected,
                    onClick = { selectedTab = index },
                    modifier = Modifier.testTag("tab_$index"),
                    text = {
                        Text(
                            text = title,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color(0xFF00FF66) else Color(0xFF8F93A2)
                        )
                    }
                )
            }
        }

        // Main Workspace Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> TerminalScreen(
                    viewModel = viewModel,
                    config = config,
                    onExecute = { prompt -> onRun(prompt, config) }
                )
                1 -> ChatScreen(
                    viewModel = chatViewModel,
                    config = config,
                    onConfigChanged = { config = it }
                )
                2 -> SettingsTab(
                    config = config,
                    onChanged = { config = it }
                )
                3 -> ToolConfigPanel(
                    config = config,
                    onChanged = { config = it }
                )
                4 -> ModelManagerTab(
                    config = config,
                    onSelected = { path, template ->
                        val displayName = path.ifBlank { "Custom GGUF model" }
                        config = config.copy(selectedModelPath = path, selectedModelName = displayName)
                        viewModel.logSystem("Model loaded: $path [Template: $template]")
                        selectedTab = 0
                    },
                    onUnload = { path ->
                        val unloaded = LocalModelStore.unloadModel(path)
                        if (unloaded && config.selectedModelPath == path) {
                            config = config.copy(selectedModelPath = "", selectedModelName = "No model selected")
                        }
                        viewModel.logSystem(if (unloaded) "Model unloaded: $path" else "Model unload failed: $path")
                    }
                )
            }
        }
    }
}

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    config: SwarmConfig,
    onExecute: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    val presetPrompts = listOf(
        "Analyze battery telemetry and optimize power matrices",
        "Inspect memory state and record security audit log",
        "Deconstruct multi-agent task and execute hardware routines",
        "Ping external MCP server tools pipeline"
    )

    LaunchedEffect(viewModel.consoleLog.size) {
        if (viewModel.consoleLog.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.consoleLog.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Immersive Terminal Box (bg-[#050608] rounded-2xl border-[#232731])
        Surface(
            color = Color(0xFF050608),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Header bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "CONSOLE TELEMETRY STREAM",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8F93A2)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "— LIVE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF00FF66).copy(alpha = 0.7f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.clear() },
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("clear_console_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear Console",
                            tint = Color(0xFF8F93A2),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF232731).copy(alpha = 0.5f), thickness = 1.dp)

                // Log Messages
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(viewModel.consoleLog) { line ->
                        ImmersiveConsoleLogItem(line)
                    }
                }

                // Bottom Terminal Status Metrics
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f), thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val runtime = Runtime.getRuntime()
                    val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                    val maxMemMb = runtime.maxMemory() / (1024 * 1024)

                    Text(
                        text = "RAM: ${usedMemMb}MB / ${maxMemMb}MB",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(0xFF00FF66).copy(alpha = 0.6f)
                    )
                    Text(
                        text = "TOK/S: 14.2  |  PROC: :swarm_engine_v1",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(0xFF00FF66).copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Quick Swarm Suggestion Chips
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "QUICK SWARM PROMPTS:",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF8F93A2),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(presetPrompts) { prompt ->
                    Surface(
                        color = Color(0xFF14171E),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FF66).copy(alpha = 0.2f)),
                        modifier = Modifier
                            .clickable { query = prompt }
                            .testTag("chip_${prompt.take(10)}")
                    ) {
                        Text(
                            text = prompt,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFFE0F7FA),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Immersive Input Bar (bg-[#14171E] rounded-2xl p-2 border border-[#232731])
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            "Input command or prompt...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("instruction_input_field"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (query.isNotBlank()) {
                            onExecute(query)
                            query = ""
                            keyboardController?.hide()
                        }
                    })
                )

                Button(
                    onClick = {
                        if (query.isNotBlank()) {
                            onExecute(query)
                            query = ""
                            keyboardController?.hide()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FF66),
                        disabledContainerColor = Color(0xFF232731)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("run_swarm_button"),
                    enabled = query.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Execute Swarm",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "RUN",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = (-0.5).sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
fun ImmersiveConsoleLogItem(line: ConsoleLine) {
    val timeStr = remember(line.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(line.timestamp))
    }

    when (line.sender) {
        "SYSTEM" -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "[SYSTEM] $timeStr",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = Color(0xFF8F93A2).copy(alpha = 0.6f)
                    )
                }
                Text(
                    text = line.message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8F93A2),
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        "ORCHESTRATOR" -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "[ORCHESTRATOR]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color(0xFF00E5FF)
                )
                Text(
                    text = "Thought: ${line.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF00E5FF).copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 1.dp)
                )

                if (line.tool.isNotEmpty() && line.tool != "done") {
                    Surface(
                        color = Color(0xFF00E5FF).copy(alpha = 0.05f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color(0xFF00E5FF).copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "⚙️ DISPATCH: ",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = line.tool,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = Color(0xFF00E5FF)
                                )
                            }
                            if (line.params.isNotEmpty() && line.params != "{}") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text(
                                        text = "📦 PARAMS: ",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = line.params,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color(0xFF8F93A2)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        "EXECUTOR" -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "[EXECUTOR]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color(0xFFFF3D00)
                )
                Text(
                    text = "Thought: ${line.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFFF3D00).copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 1.dp)
                )
                if (line.tool.isNotEmpty() && line.tool != "done") {
                    Surface(
                        color = Color(0xFFFF3D00).copy(alpha = 0.05f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color(0xFFFF3D00).copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚙️ HARDWARE BUS: ",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                            Text(
                                text = line.tool,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = Color(0xFFFF3D00)
                            )
                        }
                    }
                }
            }
        }
        "ANALYST" -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "[ANALYST]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color(0xFFFFB300)
                )
                Text(
                    text = "Thought: ${line.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFFFB300).copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        "USER" -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "[USER]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color(0xFF00FF66)
                )
                Text(
                    text = line.message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        else -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "[${line.sender}]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.White
                )
                Text(
                    text = line.message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFDCDFE4),
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsTab(
    config: SwarmConfig,
    onChanged: (SwarmConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "HARDWARE & MODEL ENGINE CONFIGURATION",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFF00FF66)
        )

        // Inference Backend Selection
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "INFERENCE BACKEND ENGINE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8F93A2),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                InferenceBackend.values().forEach { backend ->
                    val isSelected = config.inferenceBackend == backend
                    Surface(
                        color = if (isSelected) Color(0xFF1E2330) else Color(0xFF0D0E11),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF00FF66) else Color(0xFF232731)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChanged(config.copy(inferenceBackend = backend)) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = backend.displayName,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color(0xFF00FF66) else Color.White
                                )
                                Text(
                                    text = when (backend) {
                                        InferenceBackend.LLAMA_CPP -> "Optimized for ARM NEON"
                                        InferenceBackend.MLC_LLM -> "Compiler-based optimization"
                                        InferenceBackend.OLLAMA -> "Local or remote HTTP API"
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = Color(0xFF8F93A2)
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF00FF66),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        // Model Path
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "TARGET GGUF MODEL LOCATION",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8F93A2)
                )
                OutlinedTextField(
                    value = config.selectedModelPath,
                    onValueChange = { onChanged(config.copy(selectedModelPath = it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("model_path_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0E11),
                        unfocusedContainerColor = Color(0xFF0D0E11),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FF66),
                        unfocusedBorderColor = Color(0xFF232731)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                )
            }
        }

        // CPU Core Allocation Clamp
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CPU Core Allocation Clamp",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    Text(
                        text = "${config.threadClampCount} CORES",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF00E5FF)
                    )
                }
                Slider(
                    value = config.threadClampCount.toFloat(),
                    onValueChange = { onChanged(config.copy(threadClampCount = it.toInt())) },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00FF66),
                        activeTrackColor = Color(0xFF00FF66),
                        inactiveTrackColor = Color(0xFF232731)
                    ),
                    modifier = Modifier.testTag("thread_clamp_slider")
                )
            }
        }

        // KV Cache Quantization Precision
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "KV-Cache Quantization Bits",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.White
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KvCachePrecision.values().forEach { precision ->
                        val isSelected = config.cachePrecision == precision
                        Surface(
                            color = if (isSelected) Color(0xFF00FF66) else Color(0xFF0D0E11),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF00FF66) else Color(0xFF232731)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onChanged(config.copy(cachePrecision = precision)) }
                                .testTag("precision_${precision.name}")
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = precision.name,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                                Text(
                                    text = "${precision.bitValue}-bit",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = if (isSelected) Color(0xFF003820) else Color(0xFF8F93A2)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Max Agents Swarm Depth
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Max Agents Swarm Depth",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    Text(
                        text = "${config.maxAgents} AGENTS",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFFFFB300)
                    )
                }
                Slider(
                    value = config.maxAgents.toFloat(),
                    onValueChange = { onChanged(config.copy(maxAgents = it.toInt())) },
                    valueRange = 1f..6f,
                    steps = 4,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFB300),
                        activeTrackColor = Color(0xFFFFB300),
                        inactiveTrackColor = Color(0xFF232731)
                    ),
                    modifier = Modifier.testTag("max_agents_slider")
                )
            }
        }

        // Role Prompts
        Text(
            text = "ROLE SYSTEM PROMPTS",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = Color(0xFF00E5FF)
        )

        PromptEditorCard("Orchestrator Prompt", config.orchestratorPrompt) {
            onChanged(config.copy(orchestratorPrompt = it))
        }
        PromptEditorCard("Analyst Prompt", config.analystPrompt) {
            onChanged(config.copy(analystPrompt = it))
        }
        PromptEditorCard("Executor Prompt", config.executorPrompt) {
            onChanged(config.copy(executorPrompt = it))
        }
    }
}

@Composable
fun PromptEditorCard(title: String, prompt: String, onUpdate: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = Color(0xFF14171E),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expand",
                    tint = Color(0xFF8F93A2)
                )
            }

            if (expanded) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0E11),
                        unfocusedContainerColor = Color(0xFF0D0E11),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FF66),
                        unfocusedBorderColor = Color(0xFF232731)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    minLines = 2
                )
            }
        }

        // Backend-specific configuration panels
        when (config.inferenceBackend) {
            InferenceBackend.LLAMA_CPP -> LlamaCppConfigPanel(config, onChanged)
            InferenceBackend.MLC_LLM -> MlcLlmConfigPanel(config, onChanged)
            InferenceBackend.OLLAMA -> OllamaConfigPanel(config, onChanged)
        }
    }
}

/**
 * Llama.cpp engine configuration panel
 */
@Composable
fun LlamaCppConfigPanel(config: SwarmConfig, onChanged: (SwarmConfig) -> Unit) {
    Surface(
        color = Color(0xFF14171E),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "LLAMA.CPP BACKEND CONFIGURATION",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00FF66)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Use NEON Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onChanged(config.copy(
                            llamaCppConfig = config.llamaCppConfig.copy(
                                useNeon = !config.llamaCppConfig.useNeon
                            )
                        ))
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Use ARM NEON SIMD", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                    Text("Enables ARM vector optimizations", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }
                Checkbox(
                    checked = config.llamaCppConfig.useNeon,
                    onCheckedChange = {
                        onChanged(config.copy(
                            llamaCppConfig = config.llamaCppConfig.copy(useNeon = it)
                        ))
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF00FF66),
                        uncheckedColor = Color(0xFF232731)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Use DotProd Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onChanged(config.copy(
                            llamaCppConfig = config.llamaCppConfig.copy(
                                useDotProd = !config.llamaCppConfig.useDotProd
                            )
                        ))
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Use DotProd (i8mm)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                    Text("Advanced ARM dot product instructions", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }
                Checkbox(
                    checked = config.llamaCppConfig.useDotProd,
                    onCheckedChange = {
                        onChanged(config.copy(
                            llamaCppConfig = config.llamaCppConfig.copy(useDotProd = it)
                        ))
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF00FF66),
                        uncheckedColor = Color(0xFF232731)
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Status: Native ARM64 v8a optimizations",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Color(0xFF00E5FF)
            )
        }
    }
}

/**
 * MLC-LLM engine configuration panel
 */
@Composable
fun MlcLlmConfigPanel(config: SwarmConfig, onChanged: (SwarmConfig) -> Unit) {
    Surface(
        color = Color(0xFF14171E),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "MLC-LLM BACKEND CONFIGURATION",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00FF66)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Use Remote Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onChanged(config.copy(
                            mlcLlmConfig = config.mlcLlmConfig.copy(
                                useRemote = !config.mlcLlmConfig.useRemote
                            )
                        ))
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Use Remote Endpoint", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                    Text("Connect to remote MLC HTTP server", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }
                Checkbox(
                    checked = config.mlcLlmConfig.useRemote,
                    onCheckedChange = {
                        onChanged(config.copy(
                            mlcLlmConfig = config.mlcLlmConfig.copy(useRemote = it)
                        ))
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF00FF66),
                        uncheckedColor = Color(0xFF232731)
                    )
                )
            }

            if (config.mlcLlmConfig.useRemote) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Remote Endpoint URL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF8F93A2))
                OutlinedTextField(
                    value = config.mlcLlmConfig.remoteEndpoint,
                    onValueChange = { 
                        onChanged(config.copy(
                            mlcLlmConfig = config.mlcLlmConfig.copy(remoteEndpoint = it)
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0E11),
                        unfocusedContainerColor = Color(0xFF0D0E11),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FF66),
                        unfocusedBorderColor = Color(0xFF232731)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Status: MLC integration pending implementation",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Color(0xFFFFB300)
            )
        }
    }
}

/**
 * Ollama engine configuration panel
 */
@Composable
fun OllamaConfigPanel(config: SwarmConfig, onChanged: (SwarmConfig) -> Unit) {
    Surface(
        color = Color(0xFF14171E),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "OLLAMA BACKEND CONFIGURATION",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00FF66)
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text("Local Endpoint", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF8F93A2))
            OutlinedTextField(
                value = config.ollamaConfig.localEndpoint,
                onValueChange = { 
                    onChanged(config.copy(
                        ollamaConfig = config.ollamaConfig.copy(localEndpoint = it)
                    ))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0D0E11),
                    unfocusedContainerColor = Color(0xFF0D0E11),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00FF66),
                    unfocusedBorderColor = Color(0xFF232731)
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Use Remote Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onChanged(config.copy(
                            ollamaConfig = config.ollamaConfig.copy(
                                useRemote = !config.ollamaConfig.useRemote
                            )
                        ))
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Use Remote Endpoint", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                    Text("Enable remote Ollama server", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }
                Checkbox(
                    checked = config.ollamaConfig.useRemote,
                    onCheckedChange = {
                        onChanged(config.copy(
                            ollamaConfig = config.ollamaConfig.copy(useRemote = it)
                        ))
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF00FF66),
                        uncheckedColor = Color(0xFF232731)
                    )
                )
            }

            if (config.ollamaConfig.useRemote) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Remote Endpoint URL", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF8F93A2))
                OutlinedTextField(
                    value = config.ollamaConfig.remoteEndpoint,
                    onValueChange = { 
                        onChanged(config.copy(
                            ollamaConfig = config.ollamaConfig.copy(remoteEndpoint = it)
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0E11),
                        unfocusedContainerColor = Color(0xFF0D0E11),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FF66),
                        unfocusedBorderColor = Color(0xFF232731)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Model Name (in Ollama)", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF8F93A2))
            OutlinedTextField(
                value = config.ollamaConfig.modelName,
                onValueChange = { 
                    onChanged(config.copy(
                        ollamaConfig = config.ollamaConfig.copy(modelName = it)
                    ))
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g., mistral, llama2, neural-chat", fontFamily = FontFamily.Monospace, fontSize = 9.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0D0E11),
                    unfocusedContainerColor = Color(0xFF0D0E11),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00FF66),
                    unfocusedBorderColor = Color(0xFF232731)
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Status: Ready to connect to Ollama service",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Color(0xFF00E5FF)
            )
        }
    }
}

@Composable
fun ToolConfigPanel(
    config: SwarmConfig,
    onChanged: (SwarmConfig) -> Unit
) {
    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "MODEL CONTEXT PROTOCOL (MCP) TOOL REGISTRY",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFF00FF66)
        )

        // Add MCP Server Card
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Register External MCP Server",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White
                )

                OutlinedTextField(
                    value = serverName,
                    onValueChange = { serverName = it },
                    placeholder = { Text("Server Name (e.g., Weather Node)", color = Color(0xFF8F93A2), fontSize = 11.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("mcp_name_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0E11),
                        unfocusedContainerColor = Color(0xFF0D0E11),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FF66),
                        unfocusedBorderColor = Color(0xFF232731)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = { Text("Endpoint URL (e.g., http://10.0.2.2:8080/mcp)", color = Color(0xFF8F93A2), fontSize = 11.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("mcp_url_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0E11),
                        unfocusedContainerColor = Color(0xFF0D0E11),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FF66),
                        unfocusedBorderColor = Color(0xFF232731)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )

                Button(
                    onClick = {
                        if (serverName.isNotBlank() && serverUrl.isNotBlank()) {
                            val newServer = McpServerConfig(serverName.trim(), serverUrl.trim(), isEnabled = true)
                            onChanged(config.copy(mcpServers = config.mcpServers + newServer))
                            serverName = ""
                            serverUrl = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 8.dp)
                        .testTag("add_mcp_button"),
                    enabled = serverName.isNotBlank() && serverUrl.isNotBlank()
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add MCP Server", tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ADD MCP NODE", fontFamily = FontFamily.Monospace, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }

        // Active MCP Servers List
        Text(
            text = "CONFIGURED MCP TOOL ENDPOINTS (${config.mcpServers.size})",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF8F93A2)
        )

        config.mcpServers.forEachIndexed { index, server ->
            Surface(
                color = Color(0xFF14171E),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.serverName,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF00E5FF)
                        )
                        Text(
                            text = server.endpointUrl,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF8F93A2),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = server.isEnabled,
                            onCheckedChange = { checked ->
                                val updated = config.mcpServers.toMutableList()
                                updated[index] = server.copy(isEnabled = checked)
                                onChanged(config.copy(mcpServers = updated))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00FF66),
                                checkedTrackColor = Color(0xFF003820)
                            )
                        )
                        IconButton(
                            onClick = {
                                val updated = config.mcpServers.toMutableList()
                                updated.removeAt(index)
                                onChanged(config.copy(mcpServers = updated))
                            }
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Remove MCP", tint = Color(0xFFFF3D00))
                        }
                    }
                }
            }
        }

        // System Built-in Tools Matrix
        Text(
            text = "SYSTEM NATIVE HARDWARE TOOLS",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF8F93A2)
        )

        SystemToolItem("fetch_battery_state", "Live BatteryManager capacity & temperature inspection over IPC bus")
        SystemToolItem("write_secure_log", "Thread-safe AES-encrypted system telemetry persistence log")
    }
}

@Composable
fun SystemToolItem(name: String, description: String) {
    Surface(
        color = Color(0xFF14171E),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF00FF66)
                )
                Text(
                    text = description,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF8F93A2),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Surface(
                color = Color(0xFF003820),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "ENABLED",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00FF66),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun ModelManagerTab(
    config: SwarmConfig,
    onSelected: (String, String) -> Unit,
    onUnload: (String) -> Unit,
    onChanged: (SwarmConfig) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val discoveredLocalModels = remember(context) { LocalModelStore.discoverAvailableModels(context.getExternalFilesDir(null)) }
    var url by remember { mutableStateOf(config.selectedModelPath.ifBlank { "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf" }) }
    var activeDownload by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("Ready to stream model weights to local storage.") }
    var savedModelPath by remember { mutableStateOf(config.selectedModelPath) }
    var downloadedModelPath by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<HuggingFaceModel>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(true) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var selectedLocalModel by remember { mutableStateOf(config.selectedModelPath) }
    var searchQuery by remember { mutableStateOf("") }

    fun refreshModels(searchTerm: String = searchQuery) {
        scope.launch {
            isLoadingModels = true
            modelsError = null
            try {
                models = HuggingFaceRepository().fetchGgufModels(searchTerm)
            } catch (error: Throwable) {
                modelsError = error.localizedMessage ?: "Unable to load Hugging Face models."
            } finally {
                isLoadingModels = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshModels() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "HUGGING FACE MODEL WEIGHTS MANAGER",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFF00FF66)
        )

        // Preset model selector
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "SELECT GGUF MODEL PRESET:",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF8F93A2)
            )

            if (discoveredLocalModels.isNotEmpty()) {
                Text(
                    text = "LOCAL CACHE:",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF00E5FF)
                )
                discoveredLocalModels.forEach { model ->
                    val isSelected = selectedLocalModel == model.absolutePath || (selectedLocalModel.isBlank() && model.absolutePath == config.selectedModelPath)
                    Surface(
                        color = if (isSelected) Color(0xFF1E2330) else Color(0xFF14171E),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF00FF66) else Color(0xFF232731)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedLocalModel = model.absolutePath
                                url = model.absolutePath
                                onSelected(model.absolutePath, NativeEngine().extractChatTemplate(model.absolutePath))
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.fileName,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color(0xFF00FF66) else Color.White
                                )
                                Text(
                                    text = model.absolutePath,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = Color(0xFF8F93A2)
                                )
                            }
                            Button(
                                onClick = {
                                    onUnload(model.absolutePath)
                                    selectedLocalModel = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5D5D)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("UNLOAD", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color.White)
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hf_model_search_field"),
                placeholder = { Text("Search GGUF models… e.g. lfm", fontFamily = FontFamily.Monospace, color = Color(0xFF8F93A2), fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0D0E11),
                    unfocusedContainerColor = Color(0xFF0D0E11),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00FF66),
                    unfocusedBorderColor = Color(0xFF232731)
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { refreshModels(searchQuery) },
                    enabled = !isLoadingModels,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF66), contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SEARCH", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                TextButton(onClick = { refreshModels() }, enabled = !isLoadingModels) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh models")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("REFRESH", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            if (isLoadingModels) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF00FF66))
            }
            modelsError?.let { error ->
                Text("MODEL INDEX ERROR: $error", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFFF6B6B))
            }
            val visibleModels = if (searchQuery.isBlank()) models else models.filter { model ->
                model.id.contains(searchQuery, ignoreCase = true) || model.displayName.contains(searchQuery, ignoreCase = true)
            }
            visibleModels.forEach { model ->
                val targetUrl = model.downloadUrl ?: return@forEach
                val isSelected = url == targetUrl
                Surface(
                    color = if (isSelected) Color(0xFF1E2330) else Color(0xFF14171E),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) Color(0xFF00FF66) else Color(0xFF232731)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { url = targetUrl }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = model.displayName,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color(0xFF00FF66) else Color.White
                        )
                        Icon(
                            imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) Color(0xFF00FF66) else Color(0xFF8F93A2)
                        )
                    }
                }
            }
        }

        // Target URL field
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Direct GGUF Binary URL",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.White
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("hf_url_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0E11),
                        unfocusedContainerColor = Color(0xFF0D0E11),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00FF66),
                        unfocusedBorderColor = Color(0xFF232731)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (activeDownload) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF00FF66),
                            trackColor = Color(0xFF232731)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = statusText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFF00E5FF)
                            )
                            Text(
                                text = "$progress%",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00FF66)
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = statusText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF8F93A2),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        if (savedModelPath.isNotBlank()) {
                            Text(
                                text = "SAVED TO: $savedModelPath",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = Color(0xFF00E5FF),
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }

        // Model Configuration Section
        Surface(
            color = Color(0xFF14171E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232731)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "SAMPLER CONFIGURATION",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF00FF66),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Temperature
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Temperature", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                        Text("${String.format("%.2f", config.temperature)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.temperature,
                        onValueChange = { onChanged(config.copy(temperature = it)) },
                        valueRange = 0.0f..2.0f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00FF66),
                            activeTrackColor = Color(0xFF00FF66),
                            inactiveTrackColor = Color(0xFF232731)
                        )
                    )
                    Text("Controls creativity (0.0-2.0)", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Top-K
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Top-K", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                        Text("${config.topK}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.topK.toFloat(),
                        onValueChange = { onChanged(config.copy(topK = it.toInt())) },
                        valueRange = 1f..100f,
                        steps = 99,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00FF66),
                            activeTrackColor = Color(0xFF00FF66),
                            inactiveTrackColor = Color(0xFF232731)
                        )
                    )
                    Text("Limits token diversity (1-100)", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Top-P
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Top-P", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                        Text("${String.format("%.2f", config.topP)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.topP,
                        onValueChange = { onChanged(config.copy(topP = it)) },
                        valueRange = 0.0f..1.0f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00FF66),
                            activeTrackColor = Color(0xFF00FF66),
                            inactiveTrackColor = Color(0xFF232731)
                        )
                    )
                    Text("Cumulative probability threshold", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Min-P
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Min-P", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                        Text("${String.format("%.2f", config.minP)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.minP,
                        onValueChange = { onChanged(config.copy(minP = it)) },
                        valueRange = 0.0f..1.0f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00FF66),
                            activeTrackColor = Color(0xFF00FF66),
                            inactiveTrackColor = Color(0xFF232731)
                        )
                    )
                    Text("Minimum token probability threshold", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Repeat Penalty
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Repeat Penalty", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                        Text("${String.format("%.2f", config.repeatPenalty)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.repeatPenalty,
                        onValueChange = { onChanged(config.copy(repeatPenalty = it)) },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00FF66),
                            activeTrackColor = Color(0xFF00FF66),
                            inactiveTrackColor = Color(0xFF232731)
                        )
                    )
                    Text("Penalizes repeated tokens", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFF8F93A2))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Model Info Display
                if (config.selectedModelPath.isNotBlank()) {
                    val nativeEngine = remember { NativeEngine() }
                    val modelInfo = remember { nativeEngine.getModelInfo(config.selectedModelPath) }
                    Surface(
                        color = Color(0xFF0D0E11),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = modelInfo,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

                Button(
                    onClick = {
                        activeDownload = true
                        statusText = "Connecting to Hugging Face CDN..."
                        progress = 0
                        val file = LocalModelStore.fileForModel(context.getExternalFilesDir(null), url)
                        scope.launch {
                            ModelDownloader().downloadHuggingFaceModel(
                                repoUrl = url,
                                destinationFile = file,
                                callback = object : DownloadCallback {
                                    override fun onProgress(percentage: Int, bytesDownloaded: Long, totalBytes: Long) {
                                        progress = percentage
                                        val mb = bytesDownloaded / (1024 * 1024)
                                        statusText = "Streaming weights: $mb MB transfer rate"
                                    }

                                    override fun onSuccess(fileAbsolutePath: String) {
                                        activeDownload = false
                                        savedModelPath = fileAbsolutePath
                                        downloadedModelPath = fileAbsolutePath
                                        statusText = "Download complete: $fileAbsolutePath"
                                        // Do NOT auto-load - user must click Load button
                                    }

                                    override fun onFailure(errorMessage: String) {
                                        activeDownload = false
                                        statusText = "Download failed: $errorMessage"
                                        downloadedModelPath = ""
                                    }
                                }
                            )
                        }
                    },
                    enabled = !activeDownload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FF66),
                        disabledContainerColor = Color(0xFF232731)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("download_deploy_button")
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "DEPLOY FILE INSTANCE DOWNLOAD",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        fontSize = 12.sp
                    )
                }
                
                // Load Model Button (appears after successful download)
                if (downloadedModelPath.isNotBlank()) {
                    Button(
                        onClick = {
                            val template = NativeEngine().extractChatTemplate(downloadedModelPath)
                            onSelected(downloadedModelPath, template)
                            downloadedModelPath = ""
                            statusText = "Model loaded successfully. Ready for inference."
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("load_model_button")
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "LOAD MODEL INTO SESSION",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
