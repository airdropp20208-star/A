package com.example.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.HermesState
import com.example.model.ChatAttachment
import com.example.model.ChatArtifact
import com.example.model.ChatMessage
import com.example.model.ChatThread
import com.example.model.ProjectItem
import com.example.service.GeminiClient
import com.example.service.HermesAccessibilityService
import com.example.service.MacroStep
import com.example.service.StepType
import com.example.core.agent.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClaudeAgentScreen(onThemeToggle: () -> Unit = {}, isDarkTheme: Boolean = false) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Instantiate Super-Agent components
    val memoryStore = remember { MemoryStore(context) }
    val skillsEngine = remember { SkillsEngine(context) }
    val llmRouter = remember { LLMRouter(context) }
    val toolRegistry = remember { ToolRegistry(context, memoryStore, skillsEngine) }
    val orchestrator = remember { AgentOrchestrator(context, toolRegistry, llmRouter, memoryStore, skillsEngine) }

    // Navigation and global tabs state
    var selectedTab by remember { mutableStateOf(0) } // 0 = Chats, 1 = Search, 2 = Settings
    var activeThreadId by remember { mutableStateOf<String?>(null) } // Non-null when within a active chat screen
    var isVoiceModeActive by remember { mutableStateOf(false) }
    var voiceModeType by remember { mutableStateOf("hands_free") } // hands_free vs push_to_talk
    var selectedVoiceOption by remember { mutableStateOf(1) } // 1 to 5
    var isVoiceMuted by remember { mutableStateOf(false) }
    var activeArtifactPanel by remember { mutableStateOf<ChatArtifact?>(null) }

    // Dialog state management
    var showRenameDialogForThreadId by remember { mutableStateOf<String?>(null) }
    var renameDialogText by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var newProjectTitle by remember { mutableStateOf("") }
    var newProjectDesc by remember { mutableStateOf("") }
    var newProjectInstructions by remember { mutableStateOf("") }

    // Subscription & billing simulator
    var showProUpgradeDialog by remember { mutableStateOf(false) }

    // Core inputs and execution HUD details
    var userInputText by remember { mutableStateOf("") }
    val isAiThinking = HermesState.isAiThinking.value
    val isAiControlGranted = HermesState.isAiControlGranted.value

    var activeActionSteps by remember { mutableStateOf<List<MacroStep>>(emptyList()) }
    var countdownSeconds by remember { mutableStateOf(-1) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    // Search query state
    var searchQueryText by remember { mutableStateOf("") }

    // Mock attachments picking list
    val pendingAttachments = remember { mutableStateListOf<ChatAttachment>() }

    // Format timestamps beautifully
    fun formatTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60 * 1000 -> "Vừa xong"
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} phút trước"
            diff < 24 * 3600 * 1000 -> "${diff / (3600 * 1000)} giờ trước"
            else -> {
                val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }

    // Scroll with newest message updates
    val activeThread = HermesState.chatThreads.find { it.id == activeThreadId }
    LaunchedEffect(activeThread?.messages?.size, isAiThinking) {
        if (activeThread != null && activeThread.messages.isNotEmpty()) {
            listState.animateScrollToItem(activeThread.messages.size - 1)
        }
    }

    // Agent plan execution callbacks
    val executeManualActions: (List<MacroStep>) -> Unit = { steps ->
        val service = HermesAccessibilityService.instance
        if (service != null) {
            service.activeMacroSteps.clear()
            service.activeMacroSteps.addAll(steps)
            HermesState.addLog("[AI-CORE] Bắt đầu chạy chuỗi ${steps.size} bước được lập kế hoạch...")
            service.runMacroSequence(
                onStepChanged = { idx ->
                    Toast.makeText(context, "👉 Thực thi bước ${idx + 1}/${steps.size}", Toast.LENGTH_SHORT).show()
                },
                onFinished = { success, msg ->
                    Toast.makeText(context, if (success) "✅ $msg" else "❌ $msg", Toast.LENGTH_SHORT).show()
                    HermesState.addLog("[AI-CORE] Kết quả: ${if (success) "Thành công" else "Lỗi"} - $msg")
                }
            )
        } else {
            Toast.makeText(context, "Vui lòng kích hoạt Dịch vụ Trợ Năng để thực thi!", Toast.LENGTH_LONG).show()
        }
    }

    val startAutopilotCountdown: (List<MacroStep>) -> Unit = { steps ->
        countdownJob?.cancel()
        activeActionSteps = steps
        countdownSeconds = 3
        countdownJob = coroutineScope.launch {
            while (countdownSeconds > 0) {
                delay(1000)
                countdownSeconds--
            }
            executeManualActions(activeActionSteps)
            activeActionSteps = emptyList()
            countdownSeconds = -1
        }
    }

    // Create New empty chat
    val createNewChat: () -> String = {
        val newId = "t_${System.currentTimeMillis()}"
        val thread = ChatThread(
            id = newId,
            title = "Cuộc hội thoại mới",
            lastUpdated = System.currentTimeMillis(),
            messages = listOf(
                ChatMessage(
                    id = "welcome",
                    content = "Xin chào! Giao diện này được đồng bộ tối ưu theo phong cách **Claude Mobile**. Tôi đã sẵn sàng phân tích câu lệnh, xử lý tệp tin hoặc tự động hóa cử chỉ điện thoại giúp bạn.\n\n" +
                            "👉 **Nút (+) bên dưới** cho phép đính kèm PDF, dữ liệu CSV hoặc hình ảnh.\n" +
                            "🎙️ **Nút Micro** sẽ kích hoạt phòng hội thoại Voice Mode cao cấp.",
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
            )
        )
        HermesState.chatThreads.add(0, thread)
        activeThreadId = newId
        HermesState.activeThreadId.value = newId
        HermesState.syncWithActiveThread()
        HermesState.saveThreads(context)
        newId
    }

    // Trigger AI response routing
    val sendChatContent: (String) -> Unit = { textToSend ->
        if (textToSend.trim().isNotEmpty() || pendingAttachments.isNotEmpty()) {
            val targetThId = activeThreadId ?: createNewChat()
            val currTh = HermesState.chatThreads.find { it.id == targetThId }
            if (currTh != null) {
                val updatedList = currTh.messages.toMutableList()
                val userMsgId = "m_user_${System.currentTimeMillis()}"

                val attachmentsCopy = pendingAttachments.toList()
                pendingAttachments.clear()

                updatedList.add(
                    ChatMessage(
                        id = userMsgId,
                        content = textToSend,
                        isUser = true,
                        timestamp = System.currentTimeMillis(),
                        attachments = attachmentsCopy
                    )
                )

                // Render in history
                val updatedTh = currTh.copy(
                    messages = updatedList,
                    lastUpdated = System.currentTimeMillis(),
                    title = if (currTh.title == "Cuộc hội thoại mới" && textToSend.length > 5) {
                        if (textToSend.length > 25) textToSend.take(25) + "..." else textToSend
                    } else currTh.title
                )
                
                val idx = HermesState.chatThreads.indexOfFirst { it.id == targetThId }
                if (idx != -1) {
                    HermesState.chatThreads[idx] = updatedTh
                }
                userInputText = ""
                HermesState.syncWithActiveThread()
                HermesState.saveThreads(context)

                // Start AI Engine reasoning
                HermesState.isAiThinking.value = true
                coroutineScope.launch {
                    try {
                        // Gather details from Custom Styles and active Project context
                        val activeProject = HermesState.projects.find { it.id == updatedTh.projectFolderId }
                        val projectCustomInstructions = activeProject?.customInstructions ?: ""
                        
                        val activeStyleStr = when (HermesState.selectedStyle.value) {
                            "concise" -> "Hãy trả lời cực kỳ ngắn gọn, súc tích và mạch lạc nhất có thể."
                            "formal" -> "Hãy sử dụng ngôn từ chuyên nghiệp, trang trọng và lịch thiệp."
                            "custom" -> HermesState.customStyleDescription.value
                            else -> "Hãy trả lời giải thích chi tiết, thân thiện và sinh động."
                        }

                        val systemInstructionPrompt = buildString {
                            append("Bạn là mô hình Claude Mobile Clone được đồng bộ hệ thống ngoại tuyến.\n")
                            append("Chỉ dẫn phong cách: $activeStyleStr\n")
                            if (projectCustomInstructions.isNotEmpty()) {
                                append("Chỉ dẫn bổ sung theo dự án: $projectCustomInstructions\n")
                            }
                        }

                        // Run AI agent core
                        val responseText = orchestrator.runLoop(
                            goal = "$systemInstructionPrompt\nYêu cầu người dùng:\n$textToSend",
                            maxIterations = 5
                        )

                        // Visual Artifact injects depending on query
                        var artifact: ChatArtifact? = null
                        val queryLower = textToSend.lowercase()
                        if (queryLower.contains("thiết kế") || queryLower.contains("sơ đồ") || queryLower.contains("biểu đồ") || queryLower.contains("pin")) {
                            artifact = ChatArtifact(
                                title = "Sơ đồ Đo lường Điện năng lượng",
                                subtitle = "Dữ liệu Mô phỏng Băng thông và Hiệu suất Pin Nền",
                                type = "svg",
                                content = "<svg viewBox=\"0 0 400 200\">\n" +
                                        "  <rect width=\"400\" height=\"200\" rx=\"15\" fill=\"#1B1B19\" stroke=\"#D97756\" stroke-width=\"1\" />\n" +
                                        "  <path d=\"M50,150 Q100,50 150,110 T250,70 T350,130\" fill=\"none\" stroke=\"#D97756\" stroke-width=\"3\" />\n" +
                                        "  <text x=\"200\" y=\"30\" text-anchor=\"middle\" fill=\"#E6DFD5\" font-size=\"14\" font-weight=\"bold\">Hiệu năng đồng bộ hóa Pin</text>\n" +
                                        "  <circle cx=\"100\" cy=\"100\" r=\"5\" fill=\"#00FF99\" />\n" +
                                        "  <circle cx=\"250\" cy=\"70\" r=\"5\" fill=\"#00FF99\" />\n" +
                                        "</svg>"
                            )
                        } else if (queryLower.contains("web") || queryLower.contains("giao diện") || queryLower.contains("tài liệu") || queryLower.contains("code")) {
                            artifact = ChatArtifact(
                                title = "Cổng thông tin Landing Page Claude",
                                subtitle = "Mã nguồn Mockup UI/CSS Tối giản",
                                type = "website",
                                content = "<html><body style='background:#FAF8F5;color:#191919;font-family:serif;text-align:center;'><h2>Chào mừng tới Claude Mobile Clone</h2><p>Ứng dụng tối giản, tinh tế tập trung vào cuộc trò chuyện.</p><button style='background:#D97756;color:white;border:none;padding:10px 20px;border-radius:20px;font-weight:bold;'>Đồng ý hợp tác</button></body></html>"
                            )
                        }

                        val aiMsgId = "m_ai_${System.currentTimeMillis()}"
                        val aiMsg = ChatMessage(
                            id = aiMsgId,
                            content = responseText,
                            isUser = false,
                            timestamp = System.currentTimeMillis(),
                            artifact = artifact
                        )

                        val completeTh = updatedTh.copy(
                            messages = updatedTh.messages + aiMsg,
                            lastUpdated = System.currentTimeMillis()
                        )
                        
                        val newThIdx = HermesState.chatThreads.indexOfFirst { it.id == targetThId }
                        if (newThIdx != -1) {
                            HermesState.chatThreads[newThIdx] = completeTh
                        }
                        HermesState.syncWithActiveThread()
                        HermesState.saveThreads(context)

                        // Trigger Autopilot execution if code macro steps detected
                        val parsedActions = GeminiClient.parseActions(responseText)
                        if (parsedActions.isNotEmpty()) {
                            HermesState.addLog("[AI-AUTOPILOT] Chế độ gán hành động tự động hóa phát hiện!")
                            if (HermesState.isAiControlGranted.value) {
                                startAutopilotCountdown(parsedActions)
                            } else {
                                activeActionSteps = parsedActions
                                countdownSeconds = -1
                            }
                        }

                    } catch (e: Exception) {
                        val failMsg = ChatMessage(
                            id = "m_err_${System.currentTimeMillis()}",
                            content = "Có lỗi truyền dữ liệu: ${e.message}\nVui lòng thử lại hoặc kiểm tra khóa kết nối AI.",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        )
                        val failedTh = updatedTh.copy(messages = updatedTh.messages + failMsg)
                        val thIdx = HermesState.chatThreads.indexOfFirst { it.id == targetThId }
                        if (thIdx != -1) {
                            HermesState.chatThreads[thIdx] = failedTh
                        }
                        HermesState.syncWithActiveThread()
                    } finally {
                        HermesState.isAiThinking.value = false
                    }
                }
            }
        }
    }

    // Delete confirm action
    if (showDeleteConfirmDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetId = showDeleteConfirmDialog
                        if (targetId != null) {
                            HermesState.chatThreads.removeAll { it.id == targetId }
                            if (activeThreadId == targetId) activeThreadId = null
                            HermesState.saveThreads(context)
                            Toast.makeText(context, "Đã xóa hội thoại thành công", Toast.LENGTH_SHORT).show()
                        }
                        showDeleteConfirmDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("XÓA NGAY")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("HỦY BỎ")
                }
            },
            title = { Text("Xác nhận xóa") },
            text = { Text("Bạn có chắc muốn xóa cuộc trò chuyện này vĩnh viễn khỏi lịch sử thiết bị?") }
        )
    }

    // Rename dialog
    if (showRenameDialogForThreadId != null) {
        val targetId = showRenameDialogForThreadId
        AlertDialog(
            onDismissRequest = { showRenameDialogForThreadId = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameDialogText.trim().isNotEmpty() && targetId != null) {
                            val thIdx = HermesState.chatThreads.indexOfFirst { it.id == targetId }
                            if (thIdx != -1) {
                                val original = HermesState.chatThreads[thIdx]
                                HermesState.chatThreads[thIdx] = original.copy(title = renameDialogText.trim())
                                HermesState.saveThreads(context)
                                Toast.makeText(context, "Đã đổi tên hội thoại", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showRenameDialogForThreadId = null
                        renameDialogText = ""
                    }
                ) {
                    Text("ĐỒNG Ý")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialogForThreadId = null }) {
                    Text("BỎ QUA")
                }
            },
            title = { Text("Đổi tên cuộc trò chuyện") },
            text = {
                OutlinedTextField(
                    value = renameDialogText,
                    onValueChange = { renameDialogText = it },
                    placeholder = { Text("Nhập tiêu đề mới...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    // Create custom project dialog
    if (showCreateProjectDialog) {
        AlertDialog(
            onDismissRequest = { showCreateProjectDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProjectTitle.trim().isNotEmpty()) {
                            val newProject = ProjectItem(
                                id = "pj_${System.currentTimeMillis()}",
                                title = newProjectTitle.trim(),
                                description = newProjectDesc.trim().ifEmpty { "Không có mô tả" },
                                customInstructions = newProjectInstructions.trim().ifEmpty { "Hãy phản hồi như bình thường." }
                            )
                            HermesState.projects.add(newProject)
                            HermesState.saveProjects(context)
                            Toast.makeText(context, "Đã khởi tạo dự án mới: ${newProject.title}", Toast.LENGTH_SHORT).show()
                        }
                        showCreateProjectDialog = false
                        newProjectTitle = ""
                        newProjectDesc = ""
                        newProjectInstructions = ""
                    }
                ) {
                    Text("TẠO MỚI")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateProjectDialog = false }) {
                    Text("HỦY BỎ")
                }
            },
            title = { Text("Tạo dự án mới") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProjectTitle,
                        onValueChange = { newProjectTitle = it },
                        label = { Text("Tên Dự án") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newProjectDesc,
                        onValueChange = { newProjectDesc = it },
                        label = { Text("Mô tả ngắn") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newProjectInstructions,
                        onValueChange = { newProjectInstructions = it },
                        label = { Text("Cấu hình Hướng dẫn (Custom Instructions)") },
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    )
                }
            }
        )
    }

    // Billing premium simulation dialog
    if (showProUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showProUpgradeDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        HermesState.isProUser.value = true
                        HermesState.saveSettings(context)
                        Toast.makeText(context, "🌟 Nâng cấp Pro Thành Công! Trải nghiệm không giới hạn.", Toast.LENGTH_LONG).show()
                        showProUpgradeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97756))
                ) {
                    Text("ĐỒNG Ý ĐĂNG KÝ", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showProUpgradeDialog = false }) {
                    Text("XEM LẠI SAU")
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Star, "Pro", tint = Color(0xFFD97756))
                    Text("Đăng ký Claude Pro", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Chỉ với $20/tháng, bạn sẽ nâng tầm hiệu năng lên tối đa:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    val proBenefits = listOf(
                        "Dùng mô hình Claude 3.5 Sonnet mạnh mẽ nhất",
                        "Khởi tạo không giới hạn Projects tùy biến kiến thức",
                        "Upload các tệp tài liệu dung lượng siêu lớn (lên tới 30MB)",
                        "Quyền ưu tiên phản hồi nhanh hơn gấp 5 lần vào giờ cao điểm"
                    )
                    proBenefits.forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Check, "Benefit", tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                            Text(b, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (activeThreadId != null) {
            // ==========================================
            // DETAILED CHAT SCREEN (FULLSCREEN)
            // ==========================================
            val currentTh = HermesState.chatThreads.find { it.id == activeThreadId }!!
            
            Column(modifier = Modifier.fillMaxSize()) {
                // Header of Chat Screen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.background),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = {
                                activeThreadId = null
                                HermesState.activeThreadId.value = null
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Quay về",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        Column(
                            modifier = Modifier
                                .clickable {
                                    renameDialogText = currentTh.title
                                    showRenameDialogForThreadId = currentTh.id
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = currentTh.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 200.dp)
                                )
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Sửa",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                text = if (HermesState.isProUser.value) "Claude 3.5 Sonnet • Pro" else "Claude 3 Haiku • Free",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = {
                                val isNowPinned = !currentTh.isPinned
                                val idx = HermesState.chatThreads.indexOfFirst { it.id == currentTh.id }
                                if (idx != -1) {
                                    HermesState.chatThreads[idx] = currentTh.copy(isPinned = isNowPinned)
                                    HermesState.saveThreads(context)
                                    Toast.makeText(context, if (isNowPinned) "Đã ghim hội thoại" else "Đã bỏ ghim", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (currentTh.isPinned) Icons.Filled.Star else Icons.Filled.Refresh,
                                contentDescription = "Ghim",
                                tint = if (currentTh.isPinned) Color(0xFFD97756) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        IconButton(
                            onClick = { showDeleteConfirmDialog = currentTh.id }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Xóa",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                // Scrollable Chat Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(currentTh.messages) { msg ->
                            val isUser = msg.isUser
                            
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                            ) {
                                // Senders signature
                                if (!isUser) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    ) {
                                        ClaudeStarLogo(modifier = Modifier.size(11.dp))
                                        Text(
                                            "Claude",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                // Bubble chat frame
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 310.dp)
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 16.dp,
                                                topEnd = 16.dp,
                                                bottomStart = if (isUser) 16.dp else 4.dp,
                                                bottomEnd = if (isUser) 4.dp else 16.dp
                                            )
                                        )
                                        .background(
                                            if (isUser) Color(0xFFD97756)
                                            else MaterialTheme.colorScheme.surface
                                        )
                                        .border(
                                            1.dp,
                                            if (isUser) Color(0xFFCC5A37)
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            RoundedCornerShape(
                                                topStart = 16.dp,
                                                topEnd = 16.dp,
                                                bottomStart = if (isUser) 16.dp else 4.dp,
                                                bottomEnd = if (isUser) 4.dp else 16.dp
                                            )
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        // Text parsing content
                                        RichMessageText(
                                            text = msg.content,
                                            textColor = if (isUser) Color.White else MaterialTheme.colorScheme.onBackground,
                                            clipboardManager = clipboardManager,
                                            context = context
                                        )

                                        // Render Any Attachments inside User chat bubble
                                        if (msg.attachments.isNotEmpty()) {
                                            for (att in msg.attachments) {
                                                Card(
                                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.2f)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 4.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = when (att.type) {
                                                                "image" -> Icons.Filled.Face
                                                                "csv" -> Icons.Filled.List
                                                                else -> Icons.Filled.Info
                                                            },
                                                            contentDescription = "File icon",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(att.name, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                            if (att.contentPreview != null) {
                                                                Text(att.contentPreview, fontSize = 7.5.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Interactive responses feedback bar
                                if (!isUser) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.padding(start = 6.dp, top = 4.dp)
                                    ) {
                                        // Copy Action
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(msg.content))
                                                Toast.makeText(context, "Đã sao chép phản hồi", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(Icons.Filled.Share, "Copy", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f), modifier = Modifier.size(11.dp))
                                        }

                                        // Retry Action (🔄)
                                        IconButton(
                                            onClick = {
                                                Toast.makeText(context, "🔄 Đang yêu cầu sinh lại phản hồi khác...", Toast.LENGTH_SHORT).show()
                                                sendChatContent(currentTh.messages.findLast { it.isUser }?.content ?: "Hãy sinh lại câu trả lời")
                                            },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(Icons.Filled.Refresh, "Retry", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f), modifier = Modifier.size(12.dp))
                                        }

                                        // Likes Rating
                                        IconButton(
                                            onClick = {
                                                msg.score = if (msg.score == 1) 0 else 1
                                                HermesState.saveThreads(context)
                                                Toast.makeText(context, "👍 Cám ơn bạn đã đóng góp phản hồi!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.ThumbUp,
                                                "Good",
                                                tint = if (msg.score == 1) Color(0xFFD97756) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                                modifier = Modifier.size(11.dp)
                                            )
                                        }

                                        // Dislikes rating
                                        IconButton(
                                            onClick = {
                                                msg.score = if (msg.score == -1) 0 else -1
                                                HermesState.saveThreads(context)
                                                Toast.makeText(context, "👎 Xin lỗi về bất tiện, chúng tôi sẽ cải thiện mô hình phản hồi.", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.ThumbUp,
                                                "Bad",
                                                tint = if (msg.score == -1) Color.Red else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                                modifier = Modifier.size(11.dp)
                                            )
                                        }
                                    }
                                }

                                // Render any associated interactive artifacts below bubble
                                if (msg.artifact != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .widthIn(max = 280.dp)
                                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                            .clickable { activeArtifactPanel = msg.artifact }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (msg.artifact.type == "svg") Icons.Filled.Star else Icons.Filled.Home,
                                                    contentDescription = "Visual",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(msg.artifact.title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                                Text(msg.artifact.subtitle, fontSize = 8.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Icon(Icons.Filled.PlayArrow, "Open", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }

                        // Thinking placeholder
                        if (isAiThinking) {
                            item {
                                Row(
                                    modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 1.2.dp
                                    )
                                    Text(
                                        "Claude đang suy nghĩ...",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Autopilot Active action counts banner
                if (activeActionSteps.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1C1A)),
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.Build, "Macro", tint = Color(0xFFD97756), modifier = Modifier.size(14.dp))
                                Text("LẬP CHẠY TỰ ĐỘNG (${activeActionSteps.size} bước)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFamily = FontFamily.Monospace)
                            }
                            if (countdownSeconds > 0) {
                                Text("Khởi tạo cử chỉ trong ${countdownSeconds}s", fontSize = 9.sp, color = Color(0xFFD97756), fontWeight = FontWeight.Bold)
                            } else {
                                Button(
                                    onClick = {
                                        executeManualActions(activeActionSteps)
                                        activeActionSteps = emptyList()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues()
                                ) {
                                    Text("CHẠY NGAY", fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Attachments queue badges display
                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (att in pendingAttachments) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Filled.Face, "Attachment type", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
                                    Text(att.name, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    IconButton(
                                        onClick = { pendingAttachments.remove(att) },
                                        modifier = Modifier.size(12.dp)
                                    ) {
                                        Icon(Icons.Filled.Refresh, "Remove", tint = Color.Red, modifier = Modifier.size(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Composer nhập liệu dưới cùng
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Attach Action clip (📎 / +)
                    var isMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { isMenuExpanded = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Đính kèm file",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        DropdownMenu(
                            expanded = isMenuExpanded,
                            onDismissRequest = { isMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Đính ảnh từ thư viện (30MB Limit)", fontSize = 11.sp) },
                                leadingIcon = { Icon(Icons.Filled.Face, "Image", modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    pendingAttachments.add(
                                        ChatAttachment(
                                            id = "att_img_${System.currentTimeMillis()}",
                                            name = "screenshot_mock.png",
                                            type = "image",
                                            contentPreview = "[JPEG Image Asset Preview]"
                                        )
                                    )
                                    isMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Đính tài liệu PDF nghiên cứu", fontSize = 11.sp) },
                                leadingIcon = { Icon(Icons.Outlined.Info, "PDF", modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    pendingAttachments.add(
                                        ChatAttachment(
                                            id = "att_doc_${System.currentTimeMillis()}",
                                            name = "study_performance.pdf",
                                            type = "pdf",
                                            contentPreview = "[PDF Document, size 3.4MB, containing thread logs]"
                                        )
                                    )
                                    isMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Đính bảng tọa độ CSV", fontSize = 11.sp) },
                                leadingIcon = { Icon(Icons.Filled.List, "CSV", modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    pendingAttachments.add(
                                        ChatAttachment(
                                            id = "att_csv_${System.currentTimeMillis()}",
                                            name = "automation_points.csv",
                                            type = "csv",
                                            contentPreview = "PointID,X,Y,Action\np1,120,450,CLICK\np2,500,1200,SWIPE"
                                        )
                                    )
                                    isMenuExpanded = false
                                }
                            )
                        }
                    }

                    // Auto expandable Text Box up to 6 lines max
                    OutlinedTextField(
                        value = userInputText,
                        onValueChange = { if (it.length <= 1500) userInputText = it },
                        placeholder = { Text("Nhập nội dung...", fontSize = 11.5.sp) },
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .shadow(0.5.dp, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        textStyle = TextStyle(fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onBackground)
                    )

                    // Micro Voice mode Trigger or Send Option
                    if (userInputText.trim().isEmpty() && pendingAttachments.isEmpty()) {
                        IconButton(
                            onClick = { isVoiceModeActive = true },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Voice Mode Trực tiếp",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { sendChatContent(userInputText) },
                            enabled = !isAiThinking,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = "Send",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

        } else {
            // ==========================================
            // MAIN APPLICATION LAYOUT with BOTTOM NAVIGATION TABS
            // ==========================================
            Column(modifier = Modifier.fillMaxSize()) {
                
                // HEADER (Universal Top Action Bar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .background(MaterialTheme.colorScheme.background),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ClaudeStarLogo(modifier = Modifier.size(24.dp))
                        Text(
                            text = "Claude Mobile",
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // User Account/Pro Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (HermesState.isProUser.value) Color(0xFFD97756)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    if (!HermesState.isProUser.value) showProUpgradeDialog = true
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (HermesState.isProUser.value) "Claude Pro 🌟" else "Gói Miễn Phí",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (HermesState.isProUser.value) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Theme Toggle icon button
                        IconButton(
                            onClick = onThemeToggle,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Filled.Refresh else Icons.Filled.Star,
                                contentDescription = "Đổi theme",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                // MID BODY AREA based on active tab
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        0 -> {
                            // -------------------------
                            // TAB 0: CHATS DIALOGUE LIST
                            // -------------------------
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (HermesState.chatThreads.isEmpty()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        ClaudeStarLogo(modifier = Modifier.size(50.dp))
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text("Hãy bắt đầu hành trình của bạn", fontFamily = FontFamily.Serif, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                                        Text("Chưa có bất cứ lịch sử cuộc chuyện nào.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                    }
                                } else {
                                    val sortedThreads = HermesState.chatThreads.sortedWith(
                                        compareByDescending<ChatThread> { it.isPinned }
                                            .thenByDescending { it.lastUpdated }
                                    )

                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(sortedThreads) { th ->
                                            val lastMsg = th.messages.lastOrNull()?.content ?: "Chưa có tin nhắn."
                                            val lastTimeStr = formatTime(th.lastUpdated)

                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .border(
                                                        1.dp,
                                                        if (th.isPinned) Color(0xFFD97756).copy(alpha = 0.4f)
                                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                                        RoundedCornerShape(14.dp)
                                                    )
                                                    .combinedClickable(
                                                        onClick = {
                                                            activeThreadId = th.id
                                                            HermesState.activeThreadId.value = th.id
                                                            HermesState.syncWithActiveThread()
                                                        },
                                                        onLongClick = {
                                                            renameDialogText = th.title
                                                            showRenameDialogForThreadId = th.id
                                                        }
                                                    )
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            if (th.isPinned) {
                                                                Icon(
                                                                    imageVector = Icons.Filled.Star,
                                                                    contentDescription = "Pinned",
                                                                    tint = Color(0xFFD97756),
                                                                    modifier = Modifier.size(13.dp)
                                                                )
                                                            }
                                                            Text(
                                                                text = th.title,
                                                                fontSize = 12.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onBackground,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        Text(
                                                            text = lastTimeStr,
                                                            fontSize = 8.5.sp,
                                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.height(4.dp))

                                                    Text(
                                                        text = lastMsg,
                                                        fontSize = 10.5.sp,
                                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )

                                                    Spacer(modifier = Modifier.height(6.dp))

                                                    // Side operations for mobile touch help
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.End,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        IconButton(
                                                            onClick = {
                                                                val isNowPinned = !th.isPinned
                                                                val idx = HermesState.chatThreads.indexOfFirst { it.id == th.id }
                                                                if (idx != -1) {
                                                                    HermesState.chatThreads[idx] = th.copy(isPinned = isNowPinned)
                                                                    HermesState.saveThreads(context)
                                                                }
                                                            },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = if (th.isPinned) Icons.Filled.Star else Icons.Filled.Refresh,
                                                                contentDescription = "Pin toggle",
                                                                tint = if (th.isPinned) Color(0xFFD97756) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                                                modifier = Modifier.size(12.dp)
                                                            )
                                                        }

                                                        Spacer(modifier = Modifier.width(6.dp))

                                                        IconButton(
                                                            onClick = {
                                                                renameDialogText = th.title
                                                                showRenameDialogForThreadId = th.id
                                                            },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Filled.Edit,
                                                                contentDescription = "Rename",
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(11.dp)
                                                            )
                                                        }

                                                        Spacer(modifier = Modifier.width(6.dp))

                                                        IconButton(
                                                            onClick = { showDeleteConfirmDialog = th.id },
                                                            modifier = Modifier.size(24.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Filled.Delete,
                                                                contentDescription = "Delete",
                                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                                modifier = Modifier.size(11.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // (+) Floating Action Button to initiate new chat
                                FloatingActionButton(
                                    onClick = { createNewChat() },
                                    containerColor = Color(0xFFD97756),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(20.dp)
                                        .size(54.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "Tạo chat mới",
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        1 -> {
                            // -------------------------
                            // TAB 1: SEARCH WITHIN DISCUSSION HISTORY
                            // -------------------------
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp)
                            ) {
                                OutlinedTextField(
                                    value = searchQueryText,
                                    onValueChange = { searchQueryText = it },
                                    placeholder = { Text("Tìm tên hoặc từ khóa tin nhắn...") },
                                    leadingIcon = { Icon(Icons.Filled.Search, "Kính lúp") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                )

                                val matches = remember(searchQueryText) {
                                    if (searchQueryText.trim().isEmpty()) emptyList<Pair<ChatThread, ChatMessage>>()
                                    else {
                                        val filtered = mutableListOf<Pair<ChatThread, ChatMessage>>()
                                        for (th in HermesState.chatThreads) {
                                            for (m in th.messages) {
                                                if (m.content.lowercase().contains(searchQueryText.lowercase()) || th.title.lowercase().contains(searchQueryText.lowercase())) {
                                                    filtered.add(Pair(th, m))
                                                }
                                            }
                                        }
                                        filtered
                                    }
                                }

                                if (searchQueryText.trim().isEmpty()) {
                                    Column(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(Icons.Filled.Search, "Search status", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f), modifier = Modifier.size(40.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Tìm kiếm nội dung lịch sử", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                        Text("Mọi từ khóa nằm trong các gói tin nhắn sẽ hiển thị tại đây.", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                                    }
                                } else if (matches.isEmpty()) {
                                    Column(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("Không tìm thấy kết quả phù hợp", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                                    }
                                } else {
                                    LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        items(matches) { (thread, message) ->
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        activeThreadId = thread.id
                                                        HermesState.activeThreadId.value = thread.id
                                                        HermesState.syncWithActiveThread()
                                                    }
                                                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(thread.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                                        Text(if (message.isUser) "Bạn" else "Claude", fontSize = 8.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(message.content, fontSize = 9.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // -------------------------
                            // TAB 2: ADVANCED SETTINGS & ACCOUNT WORKSPACE
                            // -------------------------
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Section Account
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("TÀI KHOẢN CLAUDE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Box(
                                                    modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFD97756).copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Filled.Face, "Profile", tint = Color(0xFFD97756), modifier = Modifier.size(16.dp))
                                                }
                                                Column {
                                                    Text(HermesState.userEmail.value, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                                    Text(if (HermesState.isProUser.value) "Claude Pro 🌟 Active" else "Hồ sơ cá nhân", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            
                                            if (!HermesState.isProUser.value) {
                                                Button(
                                                    onClick = { showProUpgradeDialog = true },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97756)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.height(28.dp),
                                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                                ) {
                                                    Text("LÊN PRO", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Section Style
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("PHONG CÁCH PHẢN HỒI (STYLES)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                                        
                                        val stylesMap = listOf(
                                            Pair("explanatory", "Chi tiết (Explanatory)"),
                                            Pair("concise", "Súc tích (Concise)"),
                                            Pair("formal", "Trang trọng (Formal)"),
                                            Pair("custom", "Tùy biến nhân vật (Custom Style)")
                                        )
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            stylesMap.forEach { (key, label) ->
                                                val isSelected = HermesState.selectedStyle.value == key
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .background(
                                                            if (isSelected) MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.background
                                                        )
                                                        .border(
                                                            0.5.dp,
                                                            if (isSelected) Color.Transparent
                                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                                            RoundedCornerShape(14.dp)
                                                        )
                                                        .clickable {
                                                            HermesState.selectedStyle.value = key
                                                            HermesState.saveSettings(context)
                                                            Toast.makeText(context, "Chọn style: $label", Toast.LENGTH_SHORT).show()
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                                ) {
                                                    Text(
                                                        text = label,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onBackground
                                                    )
                                                }
                                            }
                                        }

                                        if (HermesState.selectedStyle.value == "custom") {
                                            OutlinedTextField(
                                                value = HermesState.customStyleDescription.value,
                                                onValueChange = {
                                                    HermesState.customStyleDescription.value = it
                                                    HermesState.saveSettings(context)
                                                },
                                                placeholder = { Text("Mô tả phong cách mong muốn...") },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                                ),
                                                textStyle = TextStyle(fontSize = 11.sp)
                                            )
                                        }
                                    }
                                }

                                // Section Projects
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("DỰ ÁN (PROJECTS WORKSPACE)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                                            IconButton(
                                                onClick = { showCreateProjectDialog = true },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Filled.Add, "add project", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            }
                                        }

                                        for (proj in HermesState.projects) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(proj.title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        IconButton(
                                                            onClick = {
                                                                HermesState.projects.remove(proj)
                                                                HermesState.saveProjects(context)
                                                            },
                                                            modifier = Modifier.size(16.dp)
                                                        ) {
                                                            Icon(Icons.Filled.Delete, "Delete proj", tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
                                                        }
                                                    }
                                                    Text(proj.description, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                                                            .padding(6.dp)
                                                    ) {
                                                        Text("Chỉ thị: ${proj.customInstructions}", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Autopilot Switches Configuration
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("TỰ ĐỘNG CHẠY (AUTOPILOT)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                                Text("Kích hoạt Autopilot", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text("Khi sinh ra kế hoạch thao tác, tự đông chạy sau 3 giây mà không đợi bấm tay phím hỗ trợ.", fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(
                                                checked = isAiControlGranted,
                                                onCheckedChange = { HermesState.saveAiControlPermission(context, it) },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = Color(0xFFD97756),
                                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    uncheckedTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        3 -> {
                            Breakthroughs2026Screen(
                                voiceModeType = voiceModeType,
                                onVoiceModeTypeChange = { voiceModeType = it },
                                selectedVoiceOption = selectedVoiceOption,
                                onSelectedVoiceOptionChange = { selectedVoiceOption = it }
                            )
                        }
                    }
                }

                // BOTTOM TAB BAR (Navigation Rail for Portable Screen heights)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.shadow(12.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Filled.Home, "Chats") },
                        label = { Text("Chats", fontSize = 9.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFD97756),
                            selectedTextColor = Color(0xFFD97756),
                            unselectedIconColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            unselectedTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Filled.Search, "Search") },
                        label = { Text("Tìm kiếm", fontSize = 9.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFD97756),
                            selectedTextColor = Color(0xFFD97756),
                            unselectedIconColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            unselectedTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Filled.Settings, "Settings") },
                        label = { Text("Cài đặt", fontSize = 9.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFD97756),
                            selectedTextColor = Color(0xFFD97756),
                            unselectedIconColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            unselectedTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Filled.Star, "Lab 2026") },
                        label = { Text("Mới 2026", fontSize = 9.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFD97756),
                            selectedTextColor = Color(0xFFD97756),
                            unselectedIconColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            unselectedTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }

        // ==========================================
        // VOICE MODE OVERLAY (Simulated Call interface)
        // ==========================================
        AnimatedVisibility(
            visible = isVoiceModeActive,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1D1B19)) // Beautiful Night Obsidian deep coffee
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Title Contact header
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 40.dp)
                    ) {
                        Text("Claude Voice", fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Kênh hội thoại trực tiếp • Đang truyền tải", fontSize = 11.sp, color = Color(0xFFD97756), fontWeight = FontWeight.Bold)
                    }

                    // Pulsating Glowing audio sphere in the center
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(240.dp)
                    ) {
                        // Drawing custom animated wave circles
                        val infiniteTransition = rememberInfiniteTransition()
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 0.85f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFD97756).copy(alpha = 0.15f * pulseScale),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        // Outer wave
                        Box(
                            modifier = Modifier
                                .size((160 * pulseScale).dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD97756).copy(alpha = 0.25f))
                        )

                        // Core sphere
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD97756))
                                .shadow(8.dp, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            ClaudeStarLogo(modifier = Modifier.size(44.dp), starColor = Color.White)
                        }
                    }

                    // Floating text transcript guidelines
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Text(
                            text = "Claude đang lắng nghe...",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(12.dp)
                        ) {
                            Text(
                                "Tôi đang mô phỏng dữ liệu tối ưu hóa từ cuộc trao đổi của bạn nhằm rút ngắn thời gian phản hồi theo dạng thoại truyền thanh.",
                                fontSize = 9.5.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    // Mute / Speaker and Hang-up controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 30.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            Icon(Icons.Filled.Share, "Mute", tint = Color.White)
                        }

                        // Hangup button
                        IconButton(
                            onClick = { isVoiceModeActive = false },
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.Red.copy(alpha = 0.9f))
                        ) {
                            Icon(Icons.Filled.Delete, "Hangup", tint = Color.White, modifier = Modifier.size(28.dp))
                        }

                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            Icon(Icons.Filled.Refresh, "Speaker", tint = Color.White)
                        }
                    }
                }
            }
        }

        // ==========================================
        // ARTIFACT VIEWER WINDOW (FULLSCREEN OVERLAY)
        // ==========================================
        AnimatedVisibility(
            visible = activeArtifactPanel != null,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            val art = activeArtifactPanel
            if (art != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(enabled = false) {}
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 10.dp)
                    ) {
                        // Header panel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = { activeArtifactPanel = null }) {
                                    Icon(Icons.Filled.Close, "Đóng", tint = MaterialTheme.colorScheme.primary)
                                }
                                Column {
                                    Text(art.title, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                    Text(art.subtitle, fontSize = 9.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                }
                            }
                            
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(art.content))
                                Toast.makeText(context, "Đã sao chép mã nguồn Artifact", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Filled.Share, "Copy code source", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                        // Render Canvas or Visual layout frame of active premium artifact
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            if (art.type == "svg") {
                                // Draw actual vector diagram via Canvas drawing elements
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(260.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color(0xFF1B1B19))
                                            .border(1.dp, Color(0xFFD97756).copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(20.dp)
                                        ) {
                                            // Render graphic diagram dynamically
                                            val w = size.width
                                            val h = size.height
                                            
                                            val path = androidx.compose.ui.graphics.Path().apply {
                                                moveTo(40f, h * 0.7f)
                                                quadraticBezierTo(w * 0.25f, h * 0.2f, w * 0.45f, h * 0.5f)
                                                quadraticBezierTo(w * 0.7f, h * 0.1f, w * 0.85f, h * 0.6f)
                                            }
                                            
                                            // Path stroke
                                            drawPath(
                                                path = path,
                                                color = Color(0xFFD97756),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                                            )

                                            // Draw glowing nodes points
                                            drawCircle(Color(0xFF00FF99), radius = 14f, center = androidx.compose.ui.geometry.Offset(w * 0.45f, h * 0.5f))
                                            drawCircle(Color(0xFF00FF99), radius = 14f, center = androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.23f))
                                            
                                            // Base anchor grid
                                            drawLine(
                                                color = Color.Gray.copy(alpha = 0.2f),
                                                start = androidx.compose.ui.geometry.Offset(20f, h * 0.85f),
                                                end = androidx.compose.ui.geometry.Offset(w - 20f, h * 0.85f),
                                                strokeWidth = 3f
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text("⚡ Biểu Đồ Hiệu Năng Pin Đồng bộ Hóa Live", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                                    Text("Thời lượng dùng pin tăng lên 14.5% nhờ việc nhóm WorkManager hạn chế đánh thức xung CPU của nền.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 14.dp))
                                }
                            } else {
                                // Render formatted document webpage mockups
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFFAF8F5))
                                            .border(1.dp, Color(0xFF191919).copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                            .padding(20.dp)
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("🔮 Claude Mobile Clone", fontFamily = FontFamily.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF191919))
                                            Text("Sứ mệnh của chúng tôi là mang lại giao diện tối giản tập trung tuyệt đối vào giá trị chất lượng trong đối thoại.", fontSize = 10.5.sp, textAlign = TextAlign.Center, color = Color(0xFF191919).copy(alpha = 0.7f))
                                            
                                            Button(
                                                onClick = {},
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97756)),
                                                shape = RoundedCornerShape(20.dp)
                                            ) {
                                                Text("Bắt Đầu Ngay", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    
                                    Text("MÃ NGUỒN HTML/CSS:", fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF111111))
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            art.content,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp,
                                            color = Color(0xFF00FF99),
                                            lineHeight = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ClaudeStarLogo(modifier: Modifier = Modifier, starColor: Color? = null) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val colorSelected = starColor ?: Color(0xFFD97756)
            val sizeMin = size.minDimension
            val radius = sizeMin / 2
            val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            
            // Draw a beautiful elegant Anthropic/Claude style geometric star
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(centerOffset.x, centerOffset.y - radius)
                quadraticBezierTo(centerOffset.x, centerOffset.y, centerOffset.x + radius, centerOffset.y)
                quadraticBezierTo(centerOffset.x, centerOffset.y, centerOffset.x, centerOffset.y + radius)
                quadraticBezierTo(centerOffset.x, centerOffset.y, centerOffset.x - radius, centerOffset.y)
                quadraticBezierTo(centerOffset.x, centerOffset.y, centerOffset.x, centerOffset.y - radius)
            }
            drawPath(path, color = colorSelected)
        }
    }
}

// Full rich text parsing supporting markdown blocks, inline code, copy, and clean typography
@Composable
fun RichMessageText(
    text: String,
    textColor: Color,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context
) {
    // Check if contains a code block represented by ```
    val isCodeBlock = text.startsWith("```") && text.endsWith("```")
    if (isCodeBlock) {
        val cleanCodeAndLabels = text.trim().removePrefix("```").removeSuffix("```").trim()
        val split = cleanCodeAndLabels.split("\n", limit = 2)
        val label = if (split.size > 1) split[0] else "code"
        val codeSource = if (split.size > 1) split[1] else split[0]

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111111))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label.uppercase(Locale.getDefault()),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray
                )
                Text(
                    "SAO CHÉP",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD97756),
                    modifier = Modifier
                        .clickable {
                            clipboardManager.setText(AnnotatedString(codeSource))
                            Toast.makeText(context, "Đã sao chép mã nguồn", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            Text(
                codeSource,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF00FF99),
                lineHeight = 14.sp
            )
        }
    } else {
        // Standard styled text parsing **bold**, *italics*, and backtick `inline code`
        val parts = text.split("**")
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                parts.forEachIndexed { idx, part ->
                    if (idx % 2 == 1) {
                        withStyle(style = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = if (textColor == Color.White) Color.White else Color(0xFFD97756))) {
                            append(part)
                        }
                    } else {
                        // Check for italics or inline codes next
                        val italicParts = part.split("*")
                        italicParts.forEachIndexed { iIdx, iPart ->
                            if (iIdx % 2 == 1) {
                                withStyle(style = androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic)) {
                                    append(iPart)
                                }
                            } else {
                                // Check inline backtick code `code`
                                val inlineParts = iPart.split("`")
                                inlineParts.forEachIndexed { cIdx, cPart ->
                                    if (cIdx % 2 == 1) {
                                        withStyle(style = androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic, background = Color.Gray.copy(alpha = 0.25f))) {
                                            append(" $cPart ")
                                        }
                                    } else {
                                        append(cPart)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            fontSize = 11.5.sp,
            color = textColor,
            lineHeight = 16.sp
        )
    }
}

// =========================================================================
// BREAKTHROUGH 2026 LAB COMPOSABLE SCREEN (UPGRADED CLAUDE MOBILE FEATS)
// =========================================================================
@Composable
fun Breakthroughs2026Screen(
    voiceModeType: String,
    onVoiceModeTypeChange: (String) -> Unit,
    selectedVoiceOption: Int,
    onSelectedVoiceOptionChange: (Int) -> Unit
) {
    val context = LocalContext.current
    var activeSection by remember { mutableStateOf(1) } // 1 to 6
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TOP 2026 GRAND PRE-MAX BANNER
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFD97756).copy(alpha = 0.08f)),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFD97756))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "PHÁT HÀNH 2026",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        "Claude Breakthrough Labs 🚀", 
                        fontSize = 14.sp, 
                        fontWeight = FontWeight.Bold, 
                        color = Color(0xFFD97756),
                        fontFamily = FontFamily.Serif
                    )
                }
                Text(
                    "Môi trường mô phỏng & cấu hình các công cụ sáng tạo, làm việc chuyên nghiệp thế hệ mới của Anthropic ngay trên di động.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    lineHeight = 14.sp
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

        // HORIZONTAL SCROLLABLE TAB CHIPS BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val chips = listOf(
                1 to "🖥️ Apps Tương tác",
                2 to "🎙️ Thoại Mở rộng",
                3 to "📄 Tài liệu Pro",
                4 to "🔌 Tích hợp Gốc",
                5 to "🏥 Phân tích Sức khỏe",
                6 to "🎛️ Claude Code Remote"
            )

            for ((idx, label) in chips) {
                val isSelected = activeSection == idx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(40.dp))
                        .background(
                            if (isSelected) Color(0xFFD97756)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFFD97756)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            RoundedCornerShape(40.dp)
                        )
                        .clickable { activeSection = idx }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 10.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        // MAIN DYNAMIC SECTION CONTAINER
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (activeSection) {
                    1 -> SectionInteractiveApps()
                    2 -> SectionVoiceExpanded(voiceModeType, onVoiceModeTypeChange, selectedVoiceOption, onSelectedVoiceOptionChange)
                    3 -> SectionDocsPro()
                    4 -> SectionNativeApps()
                    5 -> SectionHealthConnect()
                    6 -> SectionClaudeCodeRemote()
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// -------------------------------------------------------------------------
// SECTION 1 Composable: 🖥️ Interactive Apps (Ứng dụng Tương tác)
// -------------------------------------------------------------------------
@Composable
fun SectionInteractiveApps() {
    var selectedAppType by remember { mutableStateOf(1) } // 1: Chart, 2: Schema Draw, 3: CSS Template
    
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "ỨNG DỤNG TƯƠNG TÁC (INTERACTIVE APPS • THÁNG 3/2026)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD97756),
                fontFamily = FontFamily.Monospace
            )
            
            Text(
                "Cho phép liên kết và làm việc trực tiếp với các ứng dụng nhỏ (min-apps) đầy đủ chức năng kéo thả trực quan ngay trong khung chat Claude.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Dynamic mini-tab buttons inside the card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    1 to "Biểu đồ Phân tích",
                    2 to "Sơ đồ Lô gíc",
                    3 to "Landing Page"
                ).forEach { (idx, label) ->
                    val isSel = selectedAppType == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Color(0xFFD97756).copy(alpha = 0.12f) else Color.Transparent)
                            .border(1.dp, if (isSel) Color(0xFFD97756) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .clickable { selectedAppType = idx }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, 
                            fontSize = 9.sp, 
                            fontWeight = FontWeight.Bold,
                            color = if (isSel) Color(0xFFD97756) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // SIMULATED INTERACTIVE VIEWPORTS
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                when (selectedAppType) {
                    1 -> {
                        // 1. Interactive Spline Chart Engine
                        var peakVal by remember { mutableStateOf(55f) }
                        var targetVal by remember { mutableStateOf(75f) }
                        
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("MÔ PHỎNG BIỂU ĐỒ BẰNG TẦN", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Đỉnh: ${peakVal.toInt()}% | Đích: ${targetVal.toInt()}%",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFD97756)
                                )
                            }
                            
                            // High Fidelity Custom Drawing Canvas
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .background(Color.Black.copy(alpha = 0.05f))
                            ) {
                                val w = size.width
                                val h = size.height
                                
                                val p1Y = h * (1f - peakVal / 100f)
                                val p2Y = h * (1f - targetVal / 100f)
                                
                                // Draw beautiful Spline Curve path
                                val path = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(0f, h)
                                    quadraticBezierTo(w * 0.25f, p1Y, w * 0.5f, (p1Y + p2Y) / 2)
                                    quadraticBezierTo(w * 0.75f, p2Y, w, h * 0.9f)
                                    lineTo(w, h)
                                    close()
                                }
                                
                                drawPath(
                                    path = path,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFFD97756).copy(alpha = 0.4f),
                                            Color(0xFFD97756).copy(alpha = 0.02f)
                                        )
                                    )
                                )
                                
                                // Outline stroke path
                                val linePath = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(0f, h)
                                    quadraticBezierTo(w * 0.25f, p1Y, w * 0.5f, (p1Y + p2Y) / 2)
                                    quadraticBezierTo(w * 0.75f, p2Y, w, h * 0.9f)
                                }
                                drawPath(
                                    path = linePath,
                                    color = Color(0xFFD97756),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                                )
                                
                                // Draw specific peak points
                                drawCircle(color = Color(0xFFD97756), radius = 6f, center = androidx.compose.ui.geometry.Offset(w * 0.25f, p1Y))
                                drawCircle(color = Color(0xFF4CAF50), radius = 6f, center = androidx.compose.ui.geometry.Offset(w * 0.75f, p2Y))
                            }
                            
                            // Interactive sliders
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Điều chỉnh thông số Đỉnh tải (Drag to adjust peaks):", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Slider(
                                    value = peakVal,
                                    onValueChange = { peakVal = it },
                                    valueRange = 10f..95f,
                                    colors = SliderDefaults.colors(thumbColor = Color(0xFFD97756), activeTrackColor = Color(0xFFD97756))
                                )
                                Text("Điều chỉnh thông số Đường Đích (Target line):", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Slider(
                                    value = targetVal,
                                    onValueChange = { targetVal = it },
                                    valueRange = 10f..95f,
                                    colors = SliderDefaults.colors(thumbColor = Color(0xFF4CAF50), activeTrackColor = Color(0xFF4CAF50))
                                )
                            }
                        }
                    }
                    
                    2 -> {
                        // 2. Interactive Schema Nodes Draw
                        var schemaTitle1 by remember { mutableStateOf("Nhập liệu") }
                        var schemaTitle2 by remember { mutableStateOf("Tính toán") }
                        var schemaTitle3 by remember { mutableStateOf("Báo cáo") }
                        var activePulseStep by remember { mutableStateOf(0) }
                        val scope = rememberCoroutineScope()
                        
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("PHÁC THẢO SƠ ĐỒ LÔ GÍC", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFD97756).copy(alpha = 0.15f))
                                        .clickable {
                                            activePulseStep = 1
                                            // Simulated sequence pipeline highlight pulses
                                            val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                            handler.postDelayed({ activePulseStep = 2 }, 1000)
                                            handler.postDelayed({ activePulseStep = 3 }, 2000)
                                            handler.postDelayed({ activePulseStep = 0 }, 3200)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text("Chạy Thử Luồng", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97756))
                                }
                            }
                            
                            // Editable fields
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = schemaTitle1,
                                    onValueChange = { schemaTitle1 = it },
                                    label = { Text("Bước 1", fontSize = 7.sp) },
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(fontSize = 9.sp)
                                )
                                OutlinedTextField(
                                    value = schemaTitle2,
                                    onValueChange = { schemaTitle2 = it },
                                    label = { Text("Bước 2", fontSize = 7.sp) },
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(fontSize = 9.sp)
                                )
                                OutlinedTextField(
                                    value = schemaTitle3,
                                    onValueChange = { schemaTitle3 = it },
                                    label = { Text("Bước 3", fontSize = 7.sp) },
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                    textStyle = TextStyle(fontSize = 9.sp)
                                )
                            }
                            
                            // Graph diagram canvas connectors representation
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val nodeColors = @Composable { step: Int ->
                                    val act = activePulseStep == step
                                    if (act) Color(0xFFD97756) else MaterialTheme.colorScheme.primaryContainer
                                }
                                val nodeTextColors = @Composable { step: Int ->
                                    val act = activePulseStep == step
                                    if (act) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                                }
                                
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(nodeColors(1))
                                        .padding(8.dp)
                                ) {
                                    Text(schemaTitle1, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = nodeTextColors(1))
                                }
                                Text("➜", color = Color.Gray, fontSize = 12.sp)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(nodeColors(2))
                                        .padding(8.dp)
                                ) {
                                    Text(schemaTitle2, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = nodeTextColors(2))
                                }
                                Text("➜", color = Color.Gray, fontSize = 12.sp)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(nodeColors(3))
                                        .padding(8.dp)
                                ) {
                                    Text(schemaTitle3, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = nodeTextColors(3))
                                }
                            }
                        }
                    }
                    
                    3 -> {
                        // 3. Mini CSS Web Builder sandbox mockup
                        var webPalette by remember { mutableStateOf("warm") } // warm, cyber, dark
                        var webHeaderFont by remember { mutableStateOf("Serif") } // Serif, Sans, Monospace
                        var sandboxHeadline by remember { mutableStateOf("Khởi nghiệp tinh gọn") }
                        
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("THIẾT KẾ LANDING PAGE WEB SANDBOX", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            
                            OutlinedTextField(
                                value = sandboxHeadline,
                                onValueChange = { sandboxHeadline = it },
                                label = { Text("Tiêu đề trang (Headline text)", fontSize = 7.sp) },
                                textStyle = TextStyle(fontSize = 10.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { webPalette = if (webPalette == "warm") "cyber" else "warm" },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(30.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Đổi Màu Sắc: $webPalette", fontSize = 8.sp)
                                }
                                Button(
                                    onClick = { webHeaderFont = if (webHeaderFont == "Serif") "Sans" else "Serif" },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(30.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Phông Chữ: $webHeaderFont", fontSize = 8.sp)
                                }
                            }
                            
                            // Visual frame box
                            val bgClr = if (webPalette == "warm") Color(0xFFFAF6F0) else Color(0xFFE0F2F1)
                            val textClr = if (webPalette == "warm") Color(0xFF3E2723) else Color(0xFF004D40)
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bgClr)
                                    .border(1.dp, textClr.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = sandboxHeadline,
                                        fontFamily = if (webHeaderFont == "Serif") FontFamily.Serif else FontFamily.SansSerif,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = textClr
                                    )
                                    Text(
                                        "Trang đích được sinh bản ngữ tự động bởi Claude 2026. Hỗ trợ hiển thị phản hồi tức thì và kéo thả tài sản.",
                                        fontSize = 8.5.sp,
                                        color = textClr.copy(alpha = 0.75f),
                                        lineHeight = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// SECTION 2 Composable: 🎙️ Expanded Voice Mode Suite
// -------------------------------------------------------------------------
@Composable
fun SectionVoiceExpanded(
    voiceModeType: String,
    onVoiceModeTypeChange: (String) -> Unit,
    selectedVoiceOption: Int,
    onSelectedVoiceOptionChange: (Int) -> Unit
) {
    var isVoicePlaying by remember { mutableStateOf(false) }
    var waveModifierScale by remember { mutableStateOf(1f) }
    
    // Waveform simulation effect
    LaunchedEffect(isVoicePlaying) {
        if (isVoicePlaying) {
            while (true) {
                waveModifierScale = (7..14).random() / 10f
                kotlinx.coroutines.delay(120)
            }
        } else {
            waveModifierScale = 1f
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "CHẾ ĐỘ GIỌNG NÓI MỞ RỘNG (EXPANDED VOICE MODE • MIỄN PHÍ)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD97756),
                fontFamily = FontFamily.Monospace
            )

            // Select capture mode
            Text("1. Chọn Chế Độ Tương Tác Thoại:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    "hands_free" to "🎙️ Hands-Free (Rảnh tay)",
                    "push_to_talk" to "🔘 Push-to-Talk"
                )
                modes.forEach { (modeVal, label) ->
                    val isSel = voiceModeType == modeVal
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Color(0xFFD97756).copy(alpha = 0.12f) else MaterialTheme.colorScheme.background)
                            .border(1.dp, if (isSel) Color(0xFFD97756) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .clickable { onVoiceModeTypeChange(modeVal) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = if (isSel) Color(0xFFD97756) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Text(
                text = if (voiceModeType == "hands_free") 
                    "💡 Ở chế độ Rảnh tay, Claude sẽ tự động lắng nghe và phản hồi khi bạn dừng nói. Phù hợp khi rảnh tay (ăn uống, đi xe)."
                    else "💡 Ở chế độ Push-to-Talk, bạn cần nhấn giữ nút Micro để nói chuyện. Hoàn toàn phù hợp ở văn phòng ồn ào.",
                fontSize = 8.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Select Voice (1 to 5)
            Text("2. Chọn Cá Nhân Hóa Giọng Nói (5 tùy chọn):", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            
            val voices = listOf(
                1 to "Thanh nhã (Elegant) • Ấm áp, sâu lắng",
                2 to "Chuyên nghiệp (Exec) • Trang trọng",
                3 to "Năng động (Energetic) • Vui tươi",
                4 to "Sâu sắc (Sage) • Trí tuệ",
                5 to "Thân thiện (Cozy) • Gần gũi"
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                voices.forEach { (voiceIdx, desc) ->
                    val isSel = selectedVoiceOption == voiceIdx
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) Color(0xFFD97756).copy(alpha = 0.08f) else MaterialTheme.colorScheme.background)
                            .border(1.dp, if (isSel) Color(0xFFD97756) else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                            .clickable { 
                                onSelectedVoiceOptionChange(voiceIdx)
                                isVoicePlaying = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isSel) Color(0xFFD97756) else Color.Gray.copy(alpha = 0.5f))
                            )
                            Text(desc, fontSize = 10.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                        }
                        
                        if (isSel) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFD97756))
                                    .clickable { isVoicePlaying = !isVoicePlaying }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (isVoicePlaying) "Dừng thử" else "Thử giọng", 
                                    fontSize = 8.sp, 
                                    color = Color.White, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Subtitle preview bar
            if (isVoicePlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1F1D1C))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Visual Wave indicator
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(24.dp)
                        ) {
                            for (i in 1..8) {
                                val scale = if (i % 2 == 0) waveModifierScale else (waveModifierScale * 0.7f)
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height((16 * scale).dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0xFFD97756))
                                )
                            }
                        }
                        
                        val subtitle = when (selectedVoiceOption) {
                            1 -> "Chào bạn, tôi là Claude. Tôi sẽ đồng hành cùng bạn bằng ngôn từ nhẹ nhàng, tinh tế và sáng rõ nhất."
                            2 -> "Kính chào quý khách. Mọi phân tích dữ liệu, báo cáo tài chính và lập hoạch đều đã sẵn sàng thực thi."
                            3 -> "Alo! Bạn đã sẵn sàng bứt phá mục tiêu hôm nay chưa nào? Hãy cùng bắt đầu ngay thôi!"
                            4 -> "Mọi vấn đề đều có tầng nghĩa ẩn sâu. Tôi ở đây để cùng bạn đàm đạo, kiến giải tri thức mới."
                            5 -> "Hi! Mình nói chuyện tự nhiên như hai người bạn nhé. Cứ hỏi mình bất kỳ khó khăn nào nhé."
                            else -> "Đang phát giọng nói cá nhân hóa..."
                        }
                        
                        Text(
                            text = subtitle,
                            fontSize = 10.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// SECTION 3 Composable: 📄 Document Creator Pro
// -------------------------------------------------------------------------
@Composable
fun SectionDocsPro() {
    val context = LocalContext.current
    var activeDocTask by remember { mutableStateOf<String?>(null) } // xlsx, docx, pptx, pdf
    
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "TẠO & BIÊN TẬP TÀI LIỆU CHUYÊN NGHIỆP (PRO/MAX)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD97756),
                fontFamily = FontFamily.Monospace
            )
            
            Text(
                "Công cụ văn phòng chuyên nghiệp cho người dùng Pro/Max: Tạo mới và chỉnh sửa đầy đủ tệp Excel, PowerPoint, Word và PDF.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            if (activeDocTask == null) {
                // Documents List
                Text("Chọn tệp tài sản công việc để mở trong Trình biên tập:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                
                val docFiles = listOf(
                    "xlsx" to "📈 Báo cáo Doanh thu dự án Q2.xlsx",
                    "pptx" to "🖥️ Slide thuyết trình sản phẩm Claude.pptx",
                    "docx" to "📝 Đề cương Kế hoạch phát triển.docx",
                    "pdf" to "🔏 Tài liệu cam kết cổ đông.pdf"
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    docFiles.forEach { (type, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .clickable { activeDocTask = type }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Text("Mở biên tập ➜", fontSize = 8.5.sp, color = Color(0xFFD97756), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // DOCUMENT EDITOR SCREEN BACKDROP
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, Color(0xFFD97756), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TRÌNH BIÊN TẬP ${activeDocTask!!.uppercase()}", 
                                fontSize = 11.sp, 
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD97756)
                            )
                            IconButton(
                                onClick = { activeDocTask = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Close, "Đóng", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            }
                        }

                        // SWITCH DETAILS BASE ON DOCUMENT EXTENSION
                        when (activeDocTask) {
                            "xlsx" -> {
                                val cells = remember { mutableStateMapOf(
                                    "A1" to "150", "A2" to "320", "A3" to "250",
                                    "B1" to "200", "B2" to "110", "B3" to "340"
                                ) }
                                
                                var selectedCell by remember { mutableStateOf("A1") }
                                var editingVal by remember { mutableStateOf("") }
                                
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Bảng Tính Minh họa (Excel grid):", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    
                                    // 3x3 table grid
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        // Header Col
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Box(modifier = Modifier.weight(1f).border(1.dp, Color.Gray.copy(alpha = 0.3f)).padding(2.dp), contentAlignment = Alignment.Center) {
                                                Text("Ô", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Box(modifier = Modifier.weight(1f).border(1.dp, Color.Gray.copy(alpha = 0.3f)).padding(2.dp), contentAlignment = Alignment.Center) {
                                                Text("Cột A", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Box(modifier = Modifier.weight(1f).border(1.dp, Color.Gray.copy(alpha = 0.3f)).padding(2.dp), contentAlignment = Alignment.Center) {
                                                Text("Cột B", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        for (row in 1..3) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Box(modifier = Modifier.weight(1f).border(1.dp, Color.Gray.copy(alpha = 0.3f)).padding(4.dp), contentAlignment = Alignment.Center) {
                                                    Text("Dòng $row", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                                }
                                                listOf("A", "B").forEach { col ->
                                                    val cellKey = "$col$row"
                                                    val value = cells[cellKey] ?: "0"
                                                    val isSelected = selectedCell == cellKey
                                                    
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .background(if (isSelected) Color(0xFFD97756).copy(alpha = 0.15f) else Color.Transparent)
                                                            .border(1.dp, if (isSelected) Color(0xFFD97756) else Color.Gray.copy(alpha = 0.2f))
                                                            .clickable { 
                                                                selectedCell = cellKey
                                                                editingVal = value
                                                            }
                                                            .padding(4.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(value, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Calc calculations
                                    val sumA = (cells["A1"]?.toIntOrNull() ?: 0) + (cells["A2"]?.toIntOrNull() ?: 0) + (cells["A3"]?.toIntOrNull() ?: 0)
                                    val sumB = (cells["B1"]?.toIntOrNull() ?: 0) + (cells["B2"]?.toIntOrNull() ?: 0) + (cells["B3"]?.toIntOrNull() ?: 0)
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.05f)).padding(6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Hàm SUM A: $sumA", fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                        Text("Hàm SUM B: $sumB", fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                                        Text("TỔNG CỘNG: ${sumA + sumB}", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97756))
                                    }

                                    // Quick Input edit selected cell
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = editingVal,
                                            onValueChange = { editingVal = it },
                                            placeholder = { Text("Nhập trị số cho ô $selectedCell") },
                                            modifier = Modifier.weight(1f),
                                            textStyle = TextStyle(fontSize = 10.sp),
                                            maxLines = 1
                                        )
                                        Button(
                                            onClick = { 
                                                if (editingVal.toIntOrNull() != null) {
                                                    cells[selectedCell] = editingVal
                                                    Toast.makeText(context, "Đã lưu ô $selectedCell", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Vui lòng nhập số nguyên", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97756)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp)
                                        ) {
                                            Text("Sửa", fontSize = 9.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                            
                            "pptx" -> {
                                var currentPptPage by remember { mutableStateOf(1) }
                                var slideTitle by remember { mutableStateOf("Bản giới thiệu Sản phẩm AI") }
                                var slideSlogan by remember { mutableStateOf("Tăng cường hiệu suất x3 doanh nghiệp di dộng") }
                                var pptDesignPalette by remember { mutableStateOf(Color(0xFF673AB7)) } // PowerPoint violet profile
                                
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Thiết lập slide Powerpoint master:", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    
                                    // Quick Slide Preview display
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(90.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(pptDesignPalette)
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                                            Text("SLIDE CARD SỐ $currentPptPage", fontSize = 7.sp, color = Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(slideTitle, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                                Text(slideSlogan, fontSize = 8.sp, color = Color.White.copy(alpha = 0.8f))
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            onClick = { 
                                                currentPptPage = if (currentPptPage == 1) 2 else 1
                                                if (currentPptPage == 1) {
                                                    slideTitle = "Giới thiệu Claude 2026"
                                                    slideSlogan = "Cơ chế quản lý luồng dữ liệu độc lập"
                                                } else {
                                                    slideTitle = "Báo cáo Tác vụ Hạt Nhân"
                                                    slideSlogan = "Hiệu chỉnh tài chính an toàn tuyệt đối"
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f).height(28.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Đổi Slide (Xem $currentPptPage)", fontSize = 8.sp)
                                        }
                                        Button(
                                            onClick = { 
                                                pptDesignPalette = when (pptDesignPalette) {
                                                    Color(0xFF673AB7) -> Color(0xFF00796B)
                                                    Color(0xFF00796B) -> Color(0xFFE64A19)
                                                    else -> Color(0xFF673AB7)
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f).height(28.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Đổi giao diện slide", fontSize = 8.sp)
                                        }
                                    }
                                }
                            }

                            "docx" -> {
                                var docTextContent by remember { mutableStateOf("Claude đã cải thiện quy trình chỉnh sửa văn bản Word (.docx) thông minh nhờ bộ phân tích cú pháp Markdown trực tiếp...") }
                                var isTextBold by remember { mutableStateOf(false) }
                                var isTextItalic by remember { mutableStateOf(false) }
                                
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isTextBold) Color(0xFFD97756).copy(alpha = 0.2f) else Color.Transparent)
                                                .border(1.dp, Color.Gray.copy(alpha = 0.3f))
                                                .clickable { isTextBold = !isTextBold }
                                                .padding(6.dp)
                                        ) {
                                            Text("B", fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isTextItalic) Color(0xFFD97756).copy(alpha = 0.2f) else Color.Transparent)
                                                .border(1.dp, Color.Gray.copy(alpha = 0.3f))
                                                .clickable { isTextItalic = !isTextItalic }
                                                .padding(6.dp)
                                        ) {
                                            Text("I", fontStyle = FontStyle.Italic, fontSize = 9.sp)
                                        }
                                        Text(
                                            "Số từ: ${docTextContent.split(" ").size} | Chế độ: Word Editor Pro",
                                            fontSize = 8.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    OutlinedTextField(
                                        value = docTextContent,
                                        onValueChange = { docTextContent = it },
                                        textStyle = TextStyle(
                                            fontSize = 10.sp,
                                            fontWeight = if (isTextBold) FontWeight.Bold else FontWeight.Normal,
                                            fontStyle = if (isTextItalic) FontStyle.Italic else FontStyle.Normal
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(80.dp)
                                    )
                                }
                            }

                            "pdf" -> {
                                var digitalStamp by remember { mutableStateOf("") }
                                var signatureState by remember { mutableStateOf("") }
                                
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Xem trước trang PDF & Đóng dấu điện tử:", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(80.dp)
                                            .background(Color.White)
                                            .border(1.dp, Color.LightGray)
                                            .padding(10.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                                            Text("ĐIỀU KHOẢN CAM KẾT BẢO MẬT PHÁT HÀNH PHÂN MỀM CLAUDE", fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (signatureState.isNotEmpty()) {
                                                    Text("Chữ ký: $signatureState", fontSize = 7.sp, color = Color.Blue, fontStyle = FontStyle.Italic)
                                                } else {
                                                    Text("[Chưa có chữ ký]", fontSize = 7.sp, color = Color.Red)
                                                }
                                                
                                                if (digitalStamp.isNotEmpty()) {
                                                    Box(
                                                        modifier = Modifier
                                                            .border(1.dp, Color.Red)
                                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                                    ) {
                                                        Text(digitalStamp, fontSize = 7.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { digitalStamp = "APPROVED ✅" },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f).height(28.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Đóng dấu APPROVED", fontSize = 7.5.sp)
                                        }
                                        Button(
                                            onClick = { signatureState = "Ký số: BUXUANBANG_2026_MAX" },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f).height(28.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Ký Tên Điện Tử", fontSize = 7.5.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { 
                                Toast.makeText(context, "Đã lưu tài liệu thành công!", Toast.LENGTH_SHORT).show()
                                activeDocTask = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97756)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("Hoàn Thành & Lưu Lại Văn Bản", fontSize = 9.5.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// SECTION 4 Composable: 🔌 Native App Integration Tracker
// -------------------------------------------------------------------------
@Composable
fun SectionNativeApps() {
    val context = LocalContext.current
    var isTimerRunning by remember { mutableStateOf(false) }
    var timerSecondsRemaining by remember { mutableStateOf(5) }
    
    // Countdown Timer Simulator Effect
    LaunchedEffect(isTimerRunning, timerSecondsRemaining) {
        if (isTimerRunning && timerSecondsRemaining > 0) {
            kotlinx.coroutines.delay(1000)
            timerSecondsRemaining -= 1
        } else if (isTimerRunning && timerSecondsRemaining == 0) {
            isTimerRunning = false
            Toast.makeText(context, "🔊 [ĐỒNG HỒ CƠ HỆ THỐNG] Đã hết giờ!", Toast.LENGTH_LONG).show()
            timerSecondsRemaining = 5
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "TÍCH HỢP ỨNG DỤNG HỆ THỐNG GỐC (NATIVE INTEGRATION)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD97756),
                fontFamily = FontFamily.Monospace
            )
            Text(
                "Claude liên kết hệ thống lịch, đồng hồ báo thức, SMS và ứng dụng email để tự động sắp xếp và cập nhật thông tin rảnh tay.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Dynamic interactive panels
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                
                // Panel 1: Alarm & Timer countdown setup
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ĐỒNG HỒ BÁO THỨC & TIMER (COUNTDOWN)", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isTimerRunning) Color.Red.copy(alpha = 0.15f) else Color(0xFF4CAF50).copy(alpha = 0.15f))
                                    .clickable { isTimerRunning = !isTimerRunning }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(if (isTimerRunning) "Dừng Timer" else "Chạy Timer 5s", fontSize = 8.sp, color = if (isTimerRunning) Color.Red else Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = "Đếm ngược thời gian giả lập báo thức hệ thống: $timerSecondsRemaining giây",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Panel 2: Inbox SMS scanning simulation
                var scanLogs by remember { mutableStateOf<List<String>>(emptyList()) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("SMS & EMAIL NATIVES SCANNER", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFD97756).copy(alpha = 0.15f))
                                    .clickable { 
                                        scanLogs = listOf(
                                            "📨 [SMS] Mã OTP giao dịch ngân hàng: 582194",
                                            "📧 [Email] Chào mừng ra mắt Claude Code Remote Server",
                                            "🗓️ [Calendar] Cuộc họp với nhà quản lý vào 10:00 AM"
                                        )
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Lấy tin mới", fontSize = 8.sp, color = Color(0xFFD97756), fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        if (scanLogs.isEmpty()) {
                            Text("Chưa quét tin nhắn hệ thống.", fontSize = 8.5.sp, color = Color.Gray)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                scanLogs.forEach { log ->
                                    Text(log, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// SECTION 5 Composable: 🏥 Health Analytics Portal (Phân tích sức khỏe sâu)
// -------------------------------------------------------------------------
@Composable
fun SectionHealthConnect() {
    var walkingSteps by remember { mutableStateOf(6820) }
    var userWeightGoal by remember { mutableStateOf(70f) }
    var sleepValueHours by remember { mutableStateOf(6.8f) }
    var showHealthAnalysisReport by remember { mutableStateOf(false) }
    var isAnalyzingHealth by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "PHÂN TÍCH SỨC KHỎE CHUYÊN SÂU (HEALTH CONNECT / APPLE HEALTH)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD97756),
                fontFamily = FontFamily.Monospace
            )
            
            Text(
                "Bản phân tích xu hướng hoạt động thể chất, giấc ngủ và các chỉ số tim mạch thông qua thư viện Android Health Connect và Apple Health (Pro/Max).",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Step Progress tracker Ring representation in Jetpack Compose
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .clip(RoundedCornerShape(10.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular arc
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
                    val progress = walkingSteps.toFloat() / 10000f
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawArc(
                            color = Color.LightGray.copy(alpha = 0.3f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f)
                        )
                        drawArc(
                            color = Color(0xFF4CAF50),
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8f)
                        )
                    }
                    Text("${(progress * 100).toInt()}%", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                // Steps indicators details
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("SỐ BƯỚC ĐI TRONG NGÀY", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Text("$walkingSteps / 10,000 bước", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Năng lượng tiêu hao: ${(walkingSteps * 0.04f).toInt()} Kcal", fontSize = 9.sp)
                }

                Button(
                    onClick = { 
                        walkingSteps += 1200 
                        Toast.makeText(context, "Mô phỏng đi bộ thêm +1200 bước", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text("+1.2K Bước", fontSize = 8.sp, color = Color.White)
                }
            }

            // Sleep staging slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Cấu hình Giờ ngủ (Biometric Simulator):", fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
                    Text("${String.format("%.1f", sleepValueHours)} Giờ", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97756))
                }
                Slider(
                    value = sleepValueHours,
                    onValueChange = { sleepValueHours = it },
                    valueRange = 3f..11f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFD97756), activeTrackColor = Color(0xFFD97756))
                )
            }

            // Deep Advice Report Generator Button
            Button(
                onClick = {
                    isAnalyzingHealth = true
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({
                        isAnalyzingHealth = false
                        showHealthAnalysisReport = true
                    }, 1200)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97756)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                if (isAnalyzingHealth) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Claude đang phân tích dữ liệu...", fontSize = 9.5.sp, color = Color.White)
                } else {
                    Text("Phân Tích Sức Khỏe Bằng Claude AI Coach 🧬", fontSize = 9.5.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            // Report panel
            if (showHealthAnalysisReport) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFE8F5E9)) // Healthy soft green
                        .border(1.dp, Color(0xFF81C784), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🗒️ LỜI KHUYÊN SỨC KHỎE CÁ NHÂN HÓA TỪ CLAUDE:", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        Text(
                            text = "💡 Số bước đi hiện tại của bạn ($walkingSteps) đạt tốt chỉ tiêu vận động trung bình. Giờ ngủ sâu đạt khoảng ${String.format("%.1f", sleepValueHours)} giờ • Để tái sinh thể chất tốt nhất, Claude khuyên bạn nên duy trì bù nước sau hoạt động thể chất, hạn chế sử dụng thiết bị phát sáng trước 10:00 PM tối nay.",
                            fontSize = 9.5.sp,
                            color = Color(0xFF1B5E20),
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// SECTION 6 Composable: 🎛️ Claude Code Remote Control
// -------------------------------------------------------------------------
@Composable
fun SectionClaudeCodeRemote() {
    val context = LocalContext.current
    var isDevConnected by remember { mutableStateOf(true) }
    var terminalHistory = remember { mutableStateListOf(
        "claude-code:~$ ssh -t oauth_tunnel@agent-laptop",
        "Connecting to Claude Code Desktop Environment...",
        "Connection Established. Secure tunnel active (SHA-256).",
        "claude-code:remote-host$ "
    ) }
    
    var repoFilesTypeSelected by remember { mutableStateOf("server.js") }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "ĐIỀU KHIỂN TỪ XA CHO LẬP TRÌNH VIÊN (CLAUDE CODE REMOTE)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD97756),
                fontFamily = FontFamily.Monospace
            )
            
            Text(
                "Kết nối và gửi lệnh điều khiển trực tiếp một phiên làm việc Claude Code đang chạy trên máy tính lập trình cá nhân của bạn.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Host connection information metadata
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Máy chủ: buixuanbang-pc", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("IP: 192.168.1.144 | Cổng: 8080 (TSL)", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isDevConnected) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.15f))
                        .clickable { isDevConnected = !isDevConnected }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (isDevConnected) "Trực tuyến 🟢 14ms" else "Ngoại tuyến 🔴 Offline", 
                        fontSize = 8.sp, 
                        fontWeight = FontWeight.Bold,
                        color = if (isDevConnected) Color(0xFF4CAF50) else Color.Red
                    )
                }
            }

            if (isDevConnected) {
                // Interactive Shell terminal screen emulator
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .padding(10.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(terminalHistory) { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.5.sp,
                                color = if (line.startsWith("claude-code")) Color(0xFF00FF99) else Color.White
                            )
                        }
                    }
                }

                // Term Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "git status" to "Git Status",
                        "npm test" to "Npm Test",
                        "git commit" to "Git Commit"
                    ).forEach { (command, label) ->
                        Button(
                            onClick = {
                                terminalHistory.add("claude-code:remote-host$ $command")
                                when (command) {
                                    "git status" -> {
                                        terminalHistory.add(" On branch main")
                                        terminalHistory.add(" Your branch is up to date with 'origin/main'.")
                                        terminalHistory.add(" Changes not staged for commit:")
                                        terminalHistory.add("   modified:   server.js")
                                    }
                                    "npm test" -> {
                                        terminalHistory.add(" > dev-build@1.0.0 test")
                                        terminalHistory.add(" > jest")
                                        terminalHistory.add(" PASS  tests/auth.test.js (5.83 s)")
                                        terminalHistory.add(" PASS  tests/api.test.js (3.11 s)")
                                        terminalHistory.add(" Tests:       2 passed, 2 total")
                                    }
                                    "git commit" -> {
                                        terminalHistory.add(" [main a8e3e4f] Auto updated backup desktop files")
                                        terminalHistory.add("  1 file changed, 14 insertions(+), 2 deletions(-)")
                                    }
                                }
                                terminalHistory.add("claude-code:remote-host$ ")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f).height(28.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSecondary)
                        }
                    }
                }

                // File tree Explorer navigator preview
                Text("Chọn tệp máy tính để xem trước mã nguồn:", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("server.js", "auth.py", "index.html").forEach { filename ->
                        val isSel = repoFilesTypeSelected == filename
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) Color(0xFFD97756).copy(alpha = 0.15f) else Color.Transparent)
                                .border(1.dp, if (isSel) Color(0xFFD97756) else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .clickable { repoFilesTypeSelected = filename }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(filename, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (isSel) Color(0xFFD97756) else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                // High fidelity code displayer box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2D2D2D))
                        .padding(8.dp)
                ) {
                    val codeContent = when (repoFilesTypeSelected) {
                        "server.js" -> "const express = require('express');\nconst app = express();\n\napp.get('/', (req, res) => {\n  res.send('Claude Code remote active!');\n});"
                        "auth.py" -> "def verify_ssl_tunnel(token):\n    if len(token) > 16:\n        return True\n    return False"
                        "index.html" -> "<div class='container'>\n  <h1>Claude Engine 2026</h1>\n  <p>Thao tác rảnh tay di động hoàn hảo</p>\n</div>"
                        else -> ""
                    }
                    Text(
                        text = codeContent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = Color(0xFFFFCC00),
                        lineHeight = 11.sp
                    )
                }
            } else {
                Text(
                    "🔴 Kết nối tới máy chủ lập trình của bạn đã bị ngắt. Hãy bảo đảm phiên Claude Code đang chạy trên máy tính cá nhân và bật kết nối internet.",
                    fontSize = 10.sp,
                    color = Color.Red,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

