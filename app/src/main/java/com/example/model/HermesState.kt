package com.example.model

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.example.service.MacroStep
import com.example.service.MacroSerializer
import org.json.JSONArray
import org.json.JSONObject

// ==========================================
// Claude Mobile Core Data Models
// ==========================================

data class ChatThread(
    val id: String,
    val title: String,
    val lastUpdated: Long,
    val messages: List<ChatMessage> = emptyList(),
    val isPinned: Boolean = false,
    val projectFolderId: String? = null
)

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long,
    val attachments: List<ChatAttachment> = emptyList(),
    val artifact: ChatArtifact? = null,
    var score: Int = 0 // 1 = Thumbs up, -1 = Thumbs down, 0 = None
)

data class ChatAttachment(
    val id: String,
    val name: String,
    val type: String, // image, csv, pdf, etc.
    val contentPreview: String? = null
)

data class ChatArtifact(
    val title: String,
    val subtitle: String,
    val type: String, // "svg" or "website" / "code"
    val content: String
)

data class ProjectItem(
    val id: String,
    val title: String,
    val description: String = "",
    val customInstructions: String = "",
    val documents: List<String> = emptyList()
)

object HermesState {
    private var prefs: SharedPreferences? = null

    val currentStatus = mutableStateOf("Hermes đang chờ mục tiêu...")
    val terminalOutput = mutableStateListOf<String>()
    
    // Shared macro steps and touch recording state
    val macroSteps = mutableStateListOf<MacroStep>()
    val isRecordingByTouch = mutableStateOf(false)

    fun addLog(log: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (terminalOutput.size > 200) {
                terminalOutput.removeAt(0)
            }
            terminalOutput.add(log)
        } else {
            handler.post {
                if (terminalOutput.size > 200) {
                    terminalOutput.removeAt(0)
                }
                terminalOutput.add(log)
            }
        }
    }

    fun clearLogs() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            terminalOutput.clear()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                terminalOutput.clear()
            }
        }
    }

    // Claude AI under-the-hood parameters
    val isAiControlGranted = mutableStateOf(false)
    val chatMessages = mutableStateListOf<Pair<String, Boolean>>() // backward compatibility
    val isAiThinking = mutableStateOf(false)

    // Upgraded Model Storage for Multiple Chat Threads
    val chatThreads = mutableStateListOf<ChatThread>()
    val activeThreadId = mutableStateOf<String?>(null)

    // Projects list
    val projects = mutableStateListOf<ProjectItem>()

    // Current Style selection
    val selectedStyle = mutableStateOf("explanatory") // concise, explanatory, formal, custom
    val customStyleDescription = mutableStateOf("Hãy trả lời thân thiện và lôi cuốn.")

    // User account metadata
    val userEmail = mutableStateOf("user@example.com")
    val isProUser = mutableStateOf(false) // Upgradeable state!

    fun saveThreads(context: Context) {
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        for (th in chatThreads) {
            val thObj = JSONObject().apply {
                put("id", th.id)
                put("title", th.title)
                put("lastUpdated", th.lastUpdated)
                put("isPinned", th.isPinned)
                put("projectFolderId", th.projectFolderId ?: "")
                
                val msgArr = JSONArray()
                for (m in th.messages) {
                    val mObj = JSONObject().apply {
                        put("id", m.id)
                        put("content", m.content)
                        put("isUser", m.isUser)
                        put("timestamp", m.timestamp)
                        put("score", m.score)
                        
                        val attArr = JSONArray()
                        for (a in m.attachments) {
                            val aObj = JSONObject().apply {
                                put("id", a.id)
                                put("name", a.name)
                                put("type", a.type)
                                put("preview", a.contentPreview ?: "")
                            }
                            attArr.put(aObj)
                        }
                        put("attachments", attArr)
                        
                        m.artifact?.let { art ->
                            val artObj = JSONObject().apply {
                                put("title", art.title)
                                put("subtitle", art.subtitle)
                                put("type", art.type)
                                put("content", art.content)
                            }
                            put("artifact", artObj)
                        }
                    }
                    msgArr.put(mObj)
                }
                put("messages", msgArr)
            }
            array.put(thObj)
        }
        p.edit().putString("chat_threads_json", array.toString()).apply()
    }

    private fun addDefaultMockThreads(context: Context) {
        val th1 = ChatThread(
            id = "t1",
            title = "Tối ưu hóa Pin & Bộ nhớ",
            lastUpdated = System.currentTimeMillis() - 24 * 3600 * 1000,
            messages = listOf(
                ChatMessage(
                    id = "msg1_1",
                    content = "Làm sao để tôi tối ưu thời lượng pin cho ứng dụng Android chạy chế độ nền liên tục vậy?",
                    isUser = true,
                    timestamp = System.currentTimeMillis() - 24 * 3600 * 1000 + 1000
                ),
                ChatMessage(
                    id = "msg1_2",
                    content = "Để tối ưu thời lượng pin khi chạy nền lâu dài, bạn cần áp dụng các biện pháp sau:\n\n" +
                            "1. **Bó nhóm các tác vụ mạng (Network Batching)** thông qua `WorkManager`.\n" +
                            "2. **Sử dụng Foreground Services** đúng quy chuẩn và giải phóng WakeLocks ngay khi hoàn tất.\n" +
                            "3. **Hạn chế dùng AlarmManager** định thời quá khít, chuyển sang lặp không chính xác.\n\n" +
                            "Dưới đây là sơ đồ kiến trúc dòng năng lượng tiết kiệm pin tối ưu mà tôi vẽ thiết kế riêng cho hệ thống của bạn.",
                    isUser = false,
                    timestamp = System.currentTimeMillis() - 24 * 3600 * 1000 + 5000,
                    artifact = ChatArtifact(
                        title = "Sơ đồ Kiến trúc Sử dụng Pin",
                        subtitle = "Bằng Jetpack WorkManager & Chạy Nền An Toàn",
                        type = "svg",
                        content = "<svg viewBox=\"0 0 400 200\" width=\"100%\" height=\"100%\">\n" +
                                "  <rect width=\"400\" height=\"200\" rx=\"15\" fill=\"#111\" stroke=\"#D97756\" stroke-width=\"1\" />\n" +
                                "  <circle cx=\"80\" cy=\"100\" r=\"25\" fill=\"#D97756\" opacity=\"0.2\" />\n" +
                                "  <circle cx=\"80\" cy=\"100\" r=\"15\" fill=\"#D97756\" />\n" +
                                "  <text x=\"80\" y=\"145\" text-anchor=\"middle\" fill=\"#FFF\" font-size=\"11\">App Trigger</text>\n" +
                                "  <line x1=\"110\" y1=\"100\" x2=\"170\" y2=\"100\" stroke=\"#85827D\" stroke-dasharray=\"4\" stroke-width=\"2\" />\n" +
                                "  <rect x=\"170\" y=\"70\" width=\"80\" height=\"60\" rx=\"8\" fill=\"#D97756\" />\n" +
                                "  <text x=\"210\" y=\"105\" text-anchor=\"middle\" fill=\"#FFF\" font-size=\"10\" font-weight=\"bold\">WorkManager</text>\n" +
                                "  <line x1=\"250\" y1=\"100\" x2=\"290\" y2=\"100\" stroke=\"#85827D\" stroke-dasharray=\"4\" stroke-width=\"2\" />\n" +
                                "  <circle cx=\"320\" cy=\"100\" r=\"20\" fill=\"#4CAF50\" />\n" +
                                "  <text x=\"320\" y=\"145\" text-anchor=\"middle\" fill=\"#FFF\" font-size=\"11\">Battery Safe</text>\n" +
                                "</svg>"
                    )
                )
            ),
            isPinned = true
        )

        val th2 = ChatThread(
            id = "t2",
            title = "Lịch trình Du lịch Đà Nẵng 🌊",
            lastUpdated = System.currentTimeMillis() - 2 * 3600 * 1000,
            messages = listOf(
                ChatMessage(
                    id = "msg2_1",
                    content = "Lên kế hoạch đi Đà Nẵng 3 ngày 2 đêm súc tích giúp tôi với",
                    isUser = true,
                    timestamp = System.currentTimeMillis() - 2 * 3600 * 1000 + 1000,
                    attachments = listOf(
                        ChatAttachment(
                            id = "att1",
                            name = "danang_preferences.csv",
                            type = "csv",
                            contentPreview = "Day,Destination,Budget\n" +
                                    "1,Cầu Rồng & Chùa Linh Ứng,300k VNĐ\n" +
                                    "2,Bà Nà Hills cáp treo,950k VNĐ\n" +
                                    "3,Bãi biển Mỹ Khê & Chợ Cồn,150k VNĐ"
                        )
                    )
                ),
                ChatMessage(
                    id = "msg2_2",
                    content = "Tôi đã đọc tệp tùy chọn du lịch gửi kèm và đùa rút ra kế hoạch tối ưu cho chuyến đi Đà Nẵng 3 ngày 2 đêm của bạn:\n\n" +
                            "- **Ngày 1**: Đón sân bay ➔ Viếng Chùa Linh Ứng ngắm tượng Phật Bà ➔ Tối dạo mát ngắm Cầu Rồng phun lửa.\n" +
                            "- **Ngày 2**: Trải nghiệm Cáp treo Bà Nà Hills, check-in Cầu Vàng, khám phá Pháp mộng mơ.\n" +
                            "- **Ngày 3**: Tắm biển Mỹ Khê sớm đón bình minh ➔ Đi Chợ Cồn ăn mì Quảng, bánh xèo trước khi ra sân bay.\n\n" +
                            "Chúc bạn có một hành trình đầy ấp niềm vui!",
                    isUser = false,
                    timestamp = System.currentTimeMillis() - 2 * 3600 * 1000 + 6000
                )
            ),
            isPinned = false
        )

        chatThreads.addAll(listOf(th1, th2))
    }

    fun loadThreads(context: Context) {
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val jsonStr = p.getString("chat_threads_json", "") ?: ""
        chatThreads.clear()
        if (jsonStr.isNotEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val thObj = array.getJSONObject(i)
                    val id = thObj.getString("id")
                    val title = thObj.getString("title")
                    val lastUpdated = thObj.getLong("lastUpdated")
                    val isPinned = thObj.optBoolean("isPinned", false)
                    val projectFolderId = thObj.optString("projectFolderId", "").let { if (it.isEmpty()) null else it }
                    
                    val msgArr = thObj.getJSONArray("messages")
                    val messages = mutableListOf<ChatMessage>()
                    for (j in 0 until msgArr.length()) {
                        val mObj = msgArr.getJSONObject(j)
                        val mId = mObj.getString("id")
                        val content = mObj.getString("content")
                        val isUser = mObj.getBoolean("isUser")
                        val timestamp = mObj.getLong("timestamp")
                        val score = mObj.optInt("score", 0)
                        
                        val attachments = mutableListOf<ChatAttachment>()
                        val attArr = mObj.optJSONArray("attachments")
                        if (attArr != null) {
                            for (k in 0 until attArr.length()) {
                                val aObj = attArr.getJSONObject(k)
                                attachments.add(
                                    ChatAttachment(
                                        id = aObj.getString("id"),
                                        name = aObj.getString("name"),
                                        type = aObj.getString("type"),
                                        contentPreview = aObj.optString("preview", null)
                                    )
                                )
                            }
                        }
                        
                        val artObj = mObj.optJSONObject("artifact")
                        val artifact = if (artObj != null) {
                            ChatArtifact(
                                title = artObj.getString("title"),
                                subtitle = artObj.getString("subtitle"),
                                type = artObj.getString("type"),
                                content = artObj.getString("content")
                            )
                        } else null
                        
                        messages.add(
                            ChatMessage(
                                id = mId,
                                content = content,
                                isUser = isUser,
                                timestamp = timestamp,
                                attachments = attachments,
                                artifact = artifact,
                                score = score
                            )
                        )
                    }
                    chatThreads.add(ChatThread(id, title, lastUpdated, messages, isPinned, projectFolderId))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (chatThreads.isEmpty()) {
            addDefaultMockThreads(context)
            saveThreads(context)
        }
        
        if (activeThreadId.value == null && chatThreads.isNotEmpty()) {
            activeThreadId.value = chatThreads.first { it.isPinned }.id ?: chatThreads.first().id
        }
        syncWithActiveThread()
    }

    fun saveProjects(context: Context) {
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        for (proj in projects) {
            val obj = JSONObject().apply {
                put("id", proj.id)
                put("title", proj.title)
                put("description", proj.description)
                put("customInstructions", proj.customInstructions)
                
                val docArr = JSONArray()
                for (doc in proj.documents) {
                    docArr.put(doc)
                }
                put("documents", docArr)
            }
            array.put(obj)
        }
        p.edit().putString("projects_json", array.toString()).apply()
    }

    fun loadProjects(context: Context) {
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val jsonStr = p.getString("projects_json", "") ?: ""
        projects.clear()
        if (jsonStr.isNotEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    val title = obj.getString("title")
                    val description = obj.getString("description")
                    val customInstructions = obj.getString("customInstructions")
                    
                    val docArr = obj.getJSONArray("documents")
                    val documents = mutableListOf<String>()
                    for (j in 0 until docArr.length()) {
                        documents.add(docArr.getString(j))
                    }
                    projects.add(ProjectItem(id, title, description, customInstructions, documents))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (projects.isEmpty()) {
            projects.add(ProjectItem("p1", "Viết luận văn tốt nghiệp", "Hỗ trợ viết đề cương, nghiên cứu khoa học", "Hãy phản hồi chi tiết, khoa học, có nguồn dẫn chứng rõ ràng."))
            projects.add(ProjectItem("p2", "Xây dựng ứng dụng Claude Mobile", "Tự động thiết kế Compose UI đẹp mắt", "Đóng vai kiến trúc sư Android ưu tú, viết code tối ưu bằng Jetpack Compose."))
            saveProjects(context)
        }
    }

    fun saveSettings(context: Context) {
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        p.edit().apply {
            putString("selected_style", selectedStyle.value)
            putString("custom_style_desc", customStyleDescription.value)
            putBoolean("is_pro_user", isProUser.value)
            putString("user_email", userEmail.value)
        }.apply()
    }

    fun loadSettings(context: Context) {
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        selectedStyle.value = p.getString("selected_style", "explanatory") ?: "explanatory"
        customStyleDescription.value = p.getString("custom_style_desc", "Hãy trả lời thân thiện và lôi cuốn.") ?: "Hãy trả lời thân thiện và lôi cuốn."
        isProUser.value = p.getBoolean("is_pro_user", false)
        userEmail.value = p.getString("user_email", "user@example.com") ?: "user@example.com"
    }

    fun syncWithActiveThread() {
        val active = chatThreads.find { it.id == activeThreadId.value }
        chatMessages.clear()
        active?.messages?.forEach {
            chatMessages.add(Pair(it.content, it.isUser))
        }
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        prefs?.let {
            isAiControlGranted.value = it.getBoolean("ai_control_granted", false)
            isRecordingByTouch.value = it.getBoolean("recording_by_touch", false)
            
            val saved = it.getString("saved_macro_steps", "") ?: ""
            macroSteps.clear()
            if (saved.isNotEmpty()) {
                macroSteps.addAll(MacroSerializer.deserializeSteps(saved))
            }
        }
        
        loadSettings(context)
        loadProjects(context)
        loadThreads(context)
    }

    fun saveAiControlPermission(context: Context, granted: Boolean) {
        isAiControlGranted.value = granted
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        p.edit().putBoolean("ai_control_granted", granted).apply()
    }

    fun saveRecordingByTouch(context: Context, enabled: Boolean) {
        isRecordingByTouch.value = enabled
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        p.edit().putBoolean("recording_by_touch", enabled).apply()
    }

    fun saveMacroSteps(context: Context) {
        val p = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val serialized = MacroSerializer.serializeSteps(macroSteps)
        p.edit().putString("saved_macro_steps", serialized).apply()
    }
}

