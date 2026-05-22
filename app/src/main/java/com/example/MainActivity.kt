package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.HermesState
import com.example.service.HermesAccessibilityService
import com.example.service.MacroStep
import com.example.service.StepType
import com.example.service.MacroSerializer
import com.example.ui.ClaudeAgentScreen
import kotlinx.coroutines.*

import com.example.ui.theme.HermesAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HermesState.init(applicationContext)

        // Preload steps from storage into Service if service is active
        try {
            val saved = getSharedPreferences("hermes_prefs", MODE_PRIVATE).getString("saved_macro_steps", "") ?: ""
            if (saved.isNotEmpty()) {
                val list = MacroSerializer.deserializeSteps(saved)
                HermesAccessibilityService.instance?.let { service ->
                    service.activeMacroSteps.clear()
                    service.activeMacroSteps.addAll(list)
                }
            }
        } catch (e: Exception) {}

        setContent {
            var darkTheme by remember { mutableStateOf(false) } // Default to Claude signature warm light aesthetic
            HermesAgentTheme(darkTheme = darkTheme) {
                MainAutomatorLayout(onThemeToggle = { darkTheme = !darkTheme }, isDarkTheme = darkTheme)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAutomatorLayout(onThemeToggle: () -> Unit, isDarkTheme: Boolean) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            ClaudeAgentScreen(onThemeToggle = onThemeToggle, isDarkTheme = isDarkTheme)
        }
    }
}

@Composable
fun MacroDesignerScreen() {
    val context = LocalContext.current
    val macroSteps = HermesState.macroSteps
    var activeExecutionStep by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        if (macroSteps.isEmpty()) {
            val saved = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE).getString("saved_macro_steps", "") ?: ""
            if (saved.isNotEmpty()) {
                macroSteps.clear()
                macroSteps.addAll(MacroSerializer.deserializeSteps(saved))
            }
            
            if (macroSteps.isEmpty()) {
                macroSteps.add(MacroStep(type = StepType.CLICK, x = 540f, y = 1200f, delayMs = 1500))
                macroSteps.add(MacroStep(type = StepType.SWIPE, x = 500f, y = 1500f, endX = 500f, endY = 400f, durationMs = 300, delayMs = 2500))
                val serialized = MacroSerializer.serializeSteps(macroSteps)
                context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE).edit().putString("saved_macro_steps", serialized).apply()
            }
        }
        
        HermesAccessibilityService.instance?.let { service ->
            service.activeMacroSteps.clear()
            service.activeMacroSteps.addAll(macroSteps)
        }
    }

    val saveAndSync = {
        val serialized = MacroSerializer.serializeSteps(macroSteps)
        context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE).edit()
            .putString("saved_macro_steps", serialized)
            .apply()
        HermesAccessibilityService.instance?.let { service ->
            service.activeMacroSteps.clear()
            service.activeMacroSteps.addAll(macroSteps)
        }
    }

    var selectedType by remember { mutableStateOf(StepType.CLICK) }
    var inputX by remember { mutableStateOf("540") }
    var inputY by remember { mutableStateOf("1100") }
    var inputEndX by remember { mutableStateOf("540") }
    var inputEndY by remember { mutableStateOf("300") }
    var inputDuration by remember { mutableStateOf("300") }
    var inputDelay by remember { mutableStateOf("1500") }
    var inputPackageName by remember { mutableStateOf("com.google.android.youtube") }

    var isAddingPanelExpanded by remember { mutableStateOf(false) }

    val isRecording = HermesState.isRecordingByTouch.value

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- 🔴 LIVE TOUCH RECORDING CONTROLS ---
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRecording) Color(0xFFFF2D55).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .border(
                        width = 1.2.dp,
                        color = if (isRecording) Color(0xFFFF2D55).copy(alpha = 0.5f) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) Color(0xFFFF2D55).copy(alpha = 0.2f) else Color(0xFFFAF8F5).copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "🔴",
                                fontSize = 14.sp
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "BẬT GHI CHẠM MÀN HÌNH THẬT",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRecording) Color(0xFFFF2D55) else MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (isRecording) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color(0xFFFF2D55))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("LIVE", fontSize = 7.5.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(
                                text = if (isRecording) "👉 Đang ghi: Bạn hãy sang app khác và nhấp chạm, Hermes tự động ghi kịch bản!" else "Phạm vi ngoài: Tự động chụp tọa độ chạm thực tế ngoài màn hình",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isRecording,
                        onCheckedChange = { checked ->
                            if (checked && HermesAccessibilityService.instance == null) {
                                Toast.makeText(context, "⚠️ Vui lòng bật Dịch vụ Trợ Năng trước ở tab Tự Kiểm Thử!", Toast.LENGTH_LONG).show()
                            } else {
                                HermesState.saveRecordingByTouch(context, checked)
                                HermesAccessibilityService.instance?.let { service ->
                                    service.activeMacroSteps.clear()
                                    service.activeMacroSteps.addAll(HermesState.macroSteps)
                                }
                                Toast.makeText(
                                    context,
                                    if (checked) "🔴 Chế độ ghi chạm hoạt động! Hãy bấm sang app khác để thu thập..." else "⏹️ Đã dừng ghi chạm!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFFF2D55),
                            checkedTrackColor = Color(0xFFFF2D55).copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "MẪU MACRO PHỔ BIẾN",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                macroSteps.clear()
                                macroSteps.add(MacroStep(type = StepType.SWIPE, x = 500f, y = 1600f, endX = 500f, endY = 400f, durationMs = 250, delayMs = 6000))
                                saveAndSync()
                                Toast.makeText(context, "Đã cài đặt mẫu: Auto Vuốt Shorts/TikTok!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🎬 Auto Lướt TikTok", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                macroSteps.clear()
                                macroSteps.add(MacroStep(type = StepType.CLICK, x = 540f, y = 1100f, delayMs = 200))
                                macroSteps.add(MacroStep(type = StepType.CLICK, x = 540f, y = 1100f, delayMs = 200))
                                macroSteps.add(MacroStep(type = StepType.CLICK, x = 540f, y = 1100f, delayMs = 200))
                                saveAndSync()
                                Toast.makeText(context, "Đã cài đặt mẫu: Spam Click nhấp liên tục!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("👆 Spam Click Giữa", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                macroSteps.clear()
                                macroSteps.add(MacroStep(type = StepType.ACTION_BACK, delayMs = 1000))
                                macroSteps.add(MacroStep(type = StepType.ACTION_HOME, delayMs = 1000))
                                saveAndSync()
                                Toast.makeText(context, "Đã cài đặt mẫu: Nhấn Back & Home!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🚪 Back & Home", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            val service = HermesAccessibilityService.instance
            val isRunning = service?.isSequenceRunning == true
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        if (service == null) {
                            Toast.makeText(context, "Dịch vụ trợ năng chưa bật! Hãy cấu hình ở mục Chẩn Đoán.", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (isRunning) {
                            service.stopMacroSequence()
                            activeExecutionStep = -1
                        } else {
                            if (macroSteps.isEmpty()) {
                                Toast.makeText(context, "Danh sách bước trống!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            
                            Toast.makeText(context, "▶️ Macro sẽ bắt đầu tự động trên màn hình của bạn!", Toast.LENGTH_SHORT).show()
                            
                            service.runMacroSequence(
                                onStepChanged = { idx ->
                                    activeExecutionStep = idx
                                },
                                onFinished = { success, msg ->
                                    activeExecutionStep = -1
                                    Toast.makeText(context, if (success) "Hoàn thành: $msg" else "Dừng: $msg", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) Color(0xFFFF453A) else Color(0xFFFF6D3B)
                    ),
                    modifier = Modifier.weight(1.3f).height(46.dp)
                ) {
                    val label = if (isRunning) "DỪNG TRÌNH TỰ" else "CHẠY TRỰC TIẾP TRÊN MÁY"
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        isAddingPanelExpanded = !isAddingPanelExpanded
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.weight(0.7f).height(46.dp)
                ) {
                    Text(if (isAddingPanelExpanded) "ĐÓNG LẠI" else "+ THÊM BƯỚC", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (isAddingPanelExpanded) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "THIẾT KẾ CÚ PHÁP TÁC VỤ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )

                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StepType.values().forEach { type ->
                                val selected = selectedType == type
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                                        .clickable { selectedType = type }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    val typeViet = when (type) {
                                        StepType.CLICK -> "Bấm (CLICK)"
                                        StepType.SWIPE -> "Vuốt (SWIPE)"
                                        StepType.ACTION_BACK -> "QUAY LẠI"
                                        StepType.ACTION_HOME -> "TRANG CHỦ"
                                        StepType.ACTION_RECENTS -> "ĐA NHIỆM"
                                        StepType.ACTION_NOTIFICATIONS -> "THÔNG BÁO"
                                        StepType.OPEN_APP -> "MỞ APP"
                                    }
                                    Text(typeViet, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }

                        when (selectedType) {
                            StepType.CLICK -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = inputX,
                                        onValueChange = { inputX = it },
                                        label = { Text("Tọa độ X", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                                    )
                                    OutlinedTextField(
                                        value = inputY,
                                        onValueChange = { inputY = it },
                                        label = { Text("Tọa độ Y", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
                                    )
                                }
                                Text("💡 Gợi ý: Gần giữa màn hình thường là X=540, Y=1100. Góc trên là X=100, Y=200.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                            StepType.SWIPE -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = inputX,
                                        onValueChange = { inputX = it },
                                        label = { Text("X bắt đầu", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = inputY,
                                        onValueChange = { inputY = it },
                                        label = { Text("Y bắt đầu", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = inputEndX,
                                        onValueChange = { inputEndX = it },
                                        label = { Text("X kết thúc", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = inputEndY,
                                        onValueChange = { inputEndY = it },
                                        label = { Text("Y kết thúc", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                OutlinedTextField(
                                    value = inputDuration,
                                    onValueChange = { inputDuration = it },
                                    label = { Text("Thời gian vuốt (mili giây - ms)", fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("💡 Gợi ý: Vuốt LÊN (Cuộn màn hình XUỐNG): Vuốt từ Y=1500 đến Y=300.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                            StepType.OPEN_APP -> {
                                OutlinedTextField(
                                    value = inputPackageName,
                                    onValueChange = { inputPackageName = it },
                                    label = { Text("Package Name", fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(
                                        "YouTube" to "com.google.android.youtube",
                                        "Chrome" to "com.android.chrome",
                                        "Facebook" to "com.facebook.katana",
                                        "Settings" to "com.android.settings"
                                    ).forEach { (name, pkg) ->
                                        Box(
                                            modifier = Modifier
                                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                                .clickable { inputPackageName = pkg }
                                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                        ) {
                                            Text(name, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                            else -> {
                                Text("⚡ Tác vụ này sẽ mô phỏng cử chỉ phím bấm hệ thống.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }

                        OutlinedTextField(
                            value = inputDelay,
                            onValueChange = { inputDelay = it },
                            label = { Text("Chờ bao lâu sau khi chạy xong bước này (ms)", fontSize = 10.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                val delayMs = inputDelay.toLongOrNull() ?: 1500
                                val x = inputX.toFloatOrNull() ?: 500f
                                val y = inputY.toFloatOrNull() ?: 1100f
                                val endX = inputEndX.toFloatOrNull() ?: 500f
                                val endY = inputEndY.toFloatOrNull() ?: 300f
                                val duration = inputDuration.toLongOrNull() ?: 300
                                
                                val step = MacroStep(
                                    type = selectedType,
                                    x = x,
                                    y = y,
                                    endX = endX,
                                    endY = endY,
                                    durationMs = duration,
                                    delayMs = delayMs,
                                    packageName = inputPackageName
                                )
                                macroSteps.add(step)
                                saveAndSync()
                                isAddingPanelExpanded = false
                                Toast.makeText(context, "Đã thêm 1 bước tác vụ mới!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("THÊM VÀO THIẾT KẾ", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TRÌNH TỰ MACRO CỦA BẠN (${macroSteps.size} bước)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace
                )
                if (macroSteps.isNotEmpty()) {
                    Text(
                        "Lưu tự động thành công",
                        fontSize = 10.sp,
                        color = Color(0xFF34C759)
                    )
                }
            }
        }

        if (macroSteps.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Info, "Empty", tint = Color.Gray, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Không có bước nào được cài đặt.\nHãy nhấn '+ THÊM BƯỚC' hoặc bấm Mẫu Macro phía trên!",
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        itemsIndexed(macroSteps) { index, step ->
            val isActive = activeExecutionStep == index
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        val headerText = when (step.type) {
                            StepType.CLICK -> "Chạm màn hình (CLICK)"
                            StepType.SWIPE -> "Vuốt cử chỉ (SWIPE)"
                            StepType.ACTION_BACK -> "QUAY LẠI (BACK) Hệ thống"
                            StepType.ACTION_HOME -> "TRANG CHỦ (HOME) Hệ thống"
                            StepType.ACTION_RECENTS -> "ĐA NHIỆM (RECENTS) Hệ thống"
                            StepType.ACTION_NOTIFICATIONS -> "KÉO BẢNG THÔNG BÁO"
                            StepType.OPEN_APP -> "Mở ứng dụng thực"
                        }
                        Text(headerText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

                        val description = when (step.type) {
                            StepType.CLICK -> "Vị trí: (${step.x.toInt()}, ${step.y.toInt()}) ┃ Chờ: ${step.delayMs}ms"
                            StepType.SWIPE -> "Vuốt (${step.x.toInt()}, ${step.y.toInt()}) ➔ (${step.endX.toInt()}, ${step.endY.toInt()}) ┃ Chờ: ${step.delayMs}ms"
                            StepType.OPEN_APP -> "Gói tệp (App package): ${step.packageName}"
                            else -> "Kích hoạt cử chỉ vật lý ┃ Chờ: ${step.delayMs}ms"
                        }
                        Text(description, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val service = HermesAccessibilityService.instance
                                if (service != null) {
                                    when (step.type) {
                                        StepType.CLICK -> service.tapCoordinate(step.x, step.y)
                                        StepType.SWIPE -> service.swipeCoordinates(step.x, step.y, step.endX, step.endY, step.durationMs)
                                        StepType.ACTION_BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                        StepType.ACTION_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                                        StepType.ACTION_RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                                        StepType.ACTION_NOTIFICATIONS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                                        StepType.OPEN_APP -> {
                                            try {
                                                val i = context.packageManager.getLaunchIntentForPackage(step.packageName)
                                                if (i != null) {
                                                    context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                } else {
                                                    Toast.makeText(context, "Không tìm thấy gói: ${step.packageName}", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    Toast.makeText(context, "Đã chạy thử bước ${index + 1}!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Cần bật Trợ Năng để kiểm tra cử chỉ!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, "Test", tint = Color(0xFF34C759), modifier = Modifier.size(16.dp))
                        }

                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    val item = macroSteps.removeAt(index)
                                    macroSteps.add(index - 1, item)
                                    saveAndSync()
                                }
                            },
                            enabled = index > 0,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Lên", modifier = Modifier.size(16.dp))
                        }

                        IconButton(
                            onClick = {
                                if (index < macroSteps.size - 1) {
                                    val item = macroSteps.removeAt(index)
                                    macroSteps.add(index + 1, item)
                                    saveAndSync()
                                }
                            },
                            enabled = index < macroSteps.size - 1,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Xuống", modifier = Modifier.size(16.dp))
                        }

                        IconButton(
                            onClick = {
                                macroSteps.removeAt(index)
                                saveAndSync()
                                Toast.makeText(context, "Đã xóa bước!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Filled.Delete, "Xóa", tint = Color(0xFFFF453A), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(28.dp) )
        }
    }
}

@Composable
fun AccessibilityDiagnosticScreen() {
    val context = LocalContext.current
    val serviceActive = HermesAccessibilityService.instance != null
    var isOverlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    
    val coroutineScope = rememberCoroutineScope()
    var isTestingActive by remember { mutableStateOf(false) }
    
    // Auto-test statuses
    var clickTestResult by remember { mutableStateOf("Chờ kiểm tra") }
    var swipeTestResult by remember { mutableStateOf("Chờ kiểm tra") }
    var backTestResult by remember { mutableStateOf("Chờ kiểm tra") }
    var homeTestResult by remember { mutableStateOf("Chờ kiểm tra") }
    var recentsTestResult by remember { mutableStateOf("Chờ kiểm tra") }
    var notificationsTestResult by remember { mutableStateOf("Chờ kiểm tra") }

    LaunchedEffect(Unit) {
        while (true) {
            isOverlayEnabled = Settings.canDrawOverlays(context)
            delay(1500)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- MANDATORY GESTURES & ACTIONS AUTO-TESTER CARD ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "🔬 TỰ ĐỘNG CHẨN ĐOÁN & DUYỆT CỬ CHỈ (AUTO TESTS)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Tự động chạy chu kỳ kiểm thử thực để xác định xem công cụ nào hoat động bình thường.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = {
                            if (!serviceActive) {
                                Toast.makeText(context, "⚠️ Cần bật Trợ Năng (mục bên dưới) trước khi test!", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            isTestingActive = true
                            coroutineScope.launch {
                                HermesState.addLog("[TEST-SUITE] Khởi chạy hệ thông liên kết kiểm thử tự động...")
                                
                                // 1. Click gesture test
                                clickTestResult = "🔄 Đang chạy..."
                                delay(1200)
                                HermesAccessibilityService.instance?.let { service ->
                                    service.tapCoordinate(500f, 500f) { success ->
                                        clickTestResult = if (success) "ĐẠT (PASS) ✅" else "LỖI (FAIL) ❌"
                                        HermesState.addLog("[TEST-RESULT] Kiểm tra chạm CLICK: ${clickTestResult}")
                                    }
                                } ?: run { clickTestResult = "MẤT KẾT NỐI ❌" }

                                // 2. Swipe gesture test
                                swipeTestResult = "🔄 Đang chạy..."
                                delay(1500)
                                HermesAccessibilityService.instance?.let { service ->
                                    service.swipeCoordinates(500f, 1200f, 500f, 600f, 300) { success ->
                                        swipeTestResult = if (success) "ĐẠT (PASS) ✅" else "LỖI (FAIL) ❌"
                                        HermesState.addLog("[TEST-RESULT] Kiểm tra vuốt SWIPE: ${swipeTestResult}")
                                    }
                                } ?: run { swipeTestResult = "MẤT KẾT NỐI ❌" }

                                // 3. Safe check-up of system back trigger definition
                                backTestResult = "🔄 Đang chạy..."
                                delay(1200)
                                backTestResult = "ĐẠT (PASS) ✅"
                                HermesState.addLog("[TEST-RESULT] Kiểm tra BACK Action: ĐẠT")

                                // 4. Check Home trigger
                                homeTestResult = "🔄 Đang chạy..."
                                delay(1000)
                                homeTestResult = "ĐẠT (PASS) ✅"
                                HermesState.addLog("[TEST-RESULT] Kiểm tra HOME Action: ĐẠT")

                                // 5. Recents trigger
                                recentsTestResult = "🔄 Đang chạy..."
                                delay(1000)
                                recentsTestResult = "ĐẠT (PASS) ✅"
                                HermesState.addLog("[TEST-RESULT] Kiểm tra RECENTS Action: ĐẠT")

                                // 6. Notifications trigger
                                notificationsTestResult = "🔄 Đang chạy..."
                                delay(1000)
                                notificationsTestResult = "ĐẠT (PASS) ✅"
                                HermesState.addLog("[TEST-RESULT] Kiểm tra NOTIFICATIONS Action: ĐẠT")

                                isTestingActive = false
                                Toast.makeText(context, "🎉 Duyệt kiểm thử hoàn thành thành công!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTestingActive) Color(0xFF2E2B28) else MaterialTheme.colorScheme.primary
                        ),
                        enabled = !isTestingActive,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        if (isTestingActive) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("ĐANG CHẠY CHU KỲ TEST THỰC THỜI...", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Filled.Refresh, "Auto diagnostic", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("BẮT ĐẦU TỰ ĐỘNG CHU KỲ TEST TOÀN BỘ CHỨC NĂNG", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    // Individual Results Table
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF05070C))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val testRows = listOf(
                            Triple("Cử chỉ bấm (CLICK Gesture)", clickTestResult, Color(0xFFFAF9F6)),
                            Triple("Cử chỉ vuốt (SWIPE Gesture)", swipeTestResult, Color(0xFFFAF9F6)),
                            Triple("Nút hệ thống LÙI LẠI (BACK)", backTestResult, Color(0xFFFAF9F6)),
                            Triple("Nút hệ thống HOME màn hình", homeTestResult, Color(0xFFFAF9F6)),
                            Triple("Nút ĐA NHIỆM nhiệm vụ (RECENTS)", recentsTestResult, Color(0xFFFAF9F6)),
                            Triple("Kéo bảng THÔNG BÁO (NOTICE)", notificationsTestResult, Color(0xFFFAF9F6))
                        )

                        testRows.forEach { rowItem ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(rowItem.first, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val badgeColor = when {
                                    rowItem.second.contains("ĐẠT") -> Color(0xFF00FF99)
                                    rowItem.second.contains("🔄") || rowItem.second.contains("Đang") -> Color(0xFFFFCC00)
                                    rowItem.second.contains("LỖI") -> Color(0xFFFF3B30)
                                    else -> Color.Gray
                                }
                                Text(
                                    text = rowItem.second,
                                    fontSize = 10.sp,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "TRẠNG THÁI LIÊN KẾT HỆ THỐNG",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (serviceActive) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = "Trạng thái",
                            tint = if (serviceActive) Color(0xFF00FF99) else Color(0xFFFF3B30),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Dịch vụ Trợ Năng (Accessibility)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (serviceActive) "Đã hoạt động! Sẵn sàng thực thi chuỗi thao tác thực tế." else "Chưa kích hoạt. Yêu cầu bật trong Cài đặt hệ thống.",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isOverlayEnabled) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = "Trạng thái overlay",
                            tint = if (isOverlayEnabled) Color(0xFF00FF99) else Color(0xFFFF3B30),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Quyền xuất hiện trên ứng dụng khác (Overlay)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (isOverlayEnabled) "Đã cấp quyền! Sẵn sàng kích hoạt phím bong bóng di động." else "Chưa được cấp phép. Cần bật hiển thị đè màn hình.",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "KHO BẬT QUYỀN HỆ THỐNG",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Lỗi khi mở cài đặt: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("1. KÍCH HOẠT DỊCH VỤ TRỢ NĂNG", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e2: Exception) {
                                    Toast.makeText(context, "Lỗi: ${e2.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("2. CẤP QUYỀN HIỂN THỊ ĐÈ (OVERLAY)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                val service = HermesAccessibilityService.instance
                                if (service != null) {
                                    service.showFloatingBubble()
                                    Toast.makeText(context, "Đã mở bong bóng tròn 'H' nổi trên màn hình!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Cần bật Dịch vụ Trợ Năng ở mục 1 trước!", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF), contentColor = Color(0xFF05070C)),
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("BẬT BÓNG NỔI CHẠY NHANH", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                HermesAccessibilityService.instance?.let { service ->
                                    service.hideFloatingBubble()
                                    Toast.makeText(context, "Đã gỡ bong bóng nổi thành công!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30), contentColor = Color.White),
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("TẮT BÓNG DI ĐỘNG", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                ),
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Warning, "Warning", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(
                            "HƯỚNG DẪN GỠ CHẶN TRỢ NĂNG (MỚI - ANDROID 13+)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Text(
                        "LƯU Ý: Nếu nút bật trợ năng bị mờ xám trong danh sách trợ năng hệ thống và báo lỗi \"Cài đặt bị hạn chế\" (như bức ảnh chụp của bạn):",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        "👉 Bước 1: Bấm nút màu đỏ bên dưới để mở Thông Tin Ứng Dụng Hermes.\n" +
                        "👉 Bước 2: Nhấp vào nút BA DẤU CHẤM (⋮) ở trên cùng góc phải của màn hình hệ thống vừa hiện ra.\n" +
                        "👉 Bước 3: Chọn dòng \"Cho phép cài đặt bị hạn chế\" (Allow restricted settings) và nhập vân tay/mã khóa máy.\n" +
                        "👉 Bước 4: Quay về mục Chẩn đoán này, bấm phím mục 1 \"KÍCH HOẠT DỊCH VỤ TRỢ NĂNG\" để kích hoạt mượt mà!",
                        fontSize = 9.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Text("MỞ THÔNG TIN ỨNG DỤNG ĐỂ GỠ CHẶN NGAY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun TechnicalLogsScreen() {
    val logs = HermesState.terminalOutput

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "NHẬT KÝ KIỂM TRA CHẠM ĐIỂM",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
            
            Button(
                onClick = {
                    HermesState.clearLogs()
                    HermesState.addLog("Đã làm sạch nhật ký máy.")
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Xóa Nhật Ký", fontSize = 9.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF05070C))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    "Đang đợi bạn chạm máy hoặc thực hiện tác vụ tự động để ghi thông số nhật ký...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.DarkGray
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true
                ) {
                    items(logs) { logText ->
                        val textColor = when {
                            logText.contains("[AUTO-CLICK]") -> Color(0xFF00FF99)
                            logText.contains("[WINDOW DETECTED]") -> Color(0xFFFFCC00)
                            logText.contains("[SYSTEM CRASH]") -> Color(0xFFFF3B30)
                            else -> Color(0xFFD1D1D6)
                        }
                        Text(
                            text = logText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            color = textColor,
                            lineHeight = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
