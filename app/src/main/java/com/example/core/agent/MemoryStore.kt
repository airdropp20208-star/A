package com.example.core.agent

import android.content.Context
import android.util.Log
import java.io.File

enum class MemoryTarget {
    MEMORY, USER
}

class MemoryStore(private val context: Context) {
    private val memoriesDir = File(context.filesDir, "memories")
    private val memoryFile = File(memoriesDir, "MEMORY.md")
    private val userFile = File(memoriesDir, "USER.md")

    val memoryLimit = 2200
    val userLimit = 1375

    init {
        if (!memoriesDir.exists()) {
            memoriesDir.mkdirs()
        }
        setupDefaultMemoryFiles()
    }

    private fun setupDefaultMemoryFiles() {
        if (!memoryFile.exists()) {
            memoryFile.writeText("# GHI CHÚ CÁ NHÂN (MEMORY)\n\n- Đã khởi tạo hệ thống Super-Agent Hermes Android.\n- Sẵn sàng ghi nhớ thói quen của người dùng.")
        }
        if (!userFile.exists()) {
            userFile.writeText("# THÔNG TIN NGƯỜI DÙNG (USER PROFILE)\n\n- Thiết bị: Android OS\n- Tên người dùng: Chủ nhân Hermes")
        }
    }

    fun getMemoryText(): String {
        return if (memoryFile.exists()) memoryFile.readText() else ""
    }

    fun getUserText(): String {
        return if (userFile.exists()) userFile.readText() else ""
    }

    fun getMemoryUsage(): String {
        return "${getMemoryText().length}/$memoryLimit kí tự"
    }

    fun getUserUsage(): String {
        return "${getUserText().length}/$userLimit kí tự"
    }

    fun saveMemoryText(content: String): Boolean {
        return if (content.length <= memoryLimit) {
            try {
                memoryFile.writeText(content)
                true
            } catch (e: Exception) {
                false
            }
        } else false
    }

    fun saveUserText(content: String): Boolean {
        return if (content.length <= userLimit) {
            try {
                userFile.writeText(content)
                true
            } catch (e: Exception) {
                false
            }
        } else false
    }

    fun addEntry(target: MemoryTarget, newEntry: String): String {
        val file = if (target == MemoryTarget.MEMORY) memoryFile else userFile
        val limit = if (target == MemoryTarget.MEMORY) memoryLimit else userLimit
        val currentContent = if (file.exists()) file.readText() else ""

        // Check duplicates
        if (currentContent.contains(newEntry.trim())) {
            return "Đã có entry này rồi, không thêm trùng lặp."
        }

        val updatedContent = if (currentContent.isBlank()) {
            newEntry
        } else {
            "$currentContent\n- ${newEntry.trim()}"
        }

        if (updatedContent.length > limit) {
            return "Bộ nhớ $target đầy! (${currentContent.length}/$limit kí tự). Không thể thêm entry này (${newEntry.length} kí tự). Hãy dọn dẹp bộ nhớ trước."
        }

        try {
            file.writeText(updatedContent)
            return "Đã thêm thành công vào $target."
        } catch (e: Exception) {
            return "Lỗi thêm bộ nhớ: ${e.message}"
        }
    }

    fun replaceEntry(target: MemoryTarget, oldText: String, newContent: String): String {
        val file = if (target == MemoryTarget.MEMORY) memoryFile else userFile
        val currentContent = if (file.exists()) file.readText() else ""
        
        if (!currentContent.contains(oldText)) {
            return "Không tìm thấy nội dung cũ cần thay thế!"
        }

        val updatedContent = currentContent.replace(oldText, newContent)
        val limit = if (target == MemoryTarget.MEMORY) memoryLimit else userLimit

        if (updatedContent.length > limit) {
            return "Nội dung mới khiến bộ nhớ $target vượt giới hạn!"
        }

        try {
            file.writeText(updatedContent)
            return "Thay thế thành công trong $target."
        } catch (e: Exception) {
            return "Lỗi thay thế bộ nhớ: ${e.message}"
        }
    }

    fun clearMemory(target: MemoryTarget): String {
        val file = if (target == MemoryTarget.MEMORY) memoryFile else userFile
        try {
            if (target == MemoryTarget.MEMORY) {
                file.writeText("# GHI CHÚ CÁ NHÂN (MEMORY)\n")
            } else {
                file.writeText("# THÔNG TIN NGƯỜI DÙNG (USER PROFILE)\n")
            }
            return "Đã xóa trắng bộ nhớ $target."
        } catch (e: Exception) {
            return "Lỗi xóa bộ nhớ: ${e.message}"
        }
    }
}
