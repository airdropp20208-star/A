package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.example.model.HermesState
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import android.content.Intent
import android.net.Uri
import java.util.UUID

enum class StepType {
    CLICK, SWIPE, ACTION_BACK, ACTION_HOME, ACTION_RECENTS, ACTION_NOTIFICATIONS, OPEN_APP
}

data class MacroStep(
    val id: String = UUID.randomUUID().toString(),
    val type: StepType = StepType.CLICK,
    val x: Float = 0f,
    val y: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val durationMs: Long = 300,
    val delayMs: Long = 1000,
    val packageName: String = ""
)

object MacroSerializer {
    fun serializeSteps(steps: List<MacroStep>): String {
        return steps.joinToString(";;") { step ->
            "${step.id}|${step.type.name}|${step.x}|${step.y}|${step.endX}|${step.endY}|${step.durationMs}|${step.delayMs}|${step.packageName}"
        }
    }

    fun deserializeSteps(input: String): List<MacroStep> {
        if (input.trim().isEmpty()) return emptyList()
        return try {
            input.split(";;").mapNotNull { part ->
                val tokens = part.split("|")
                if (tokens.size >= 9) {
                    MacroStep(
                        id = tokens[0],
                        type = StepType.valueOf(tokens[1]),
                        x = tokens[2].toFloatOrNull() ?: 0f,
                        y = tokens[3].toFloatOrNull() ?: 0f,
                        endX = tokens[4].toFloatOrNull() ?: 0f,
                        endY = tokens[5].toFloatOrNull() ?: 0f,
                        durationMs = tokens[6].toLongOrNull() ?: 300,
                        delayMs = tokens[7].toLongOrNull() ?: 1000,
                        packageName = tokens[8]
                    )
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}


class HermesAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var bubbleLayout: FrameLayout? = null
    private var isBubbleShowing = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val activeMacroSteps = mutableListOf<MacroStep>()
    var isSequenceRunning = false
    private var macroJob: Job? = null

    private val longClickRunnable = Runnable {
        Toast.makeText(this, "⚙️ Đang mở cấu hình Hermes...", Toast.LENGTH_SHORT).show()
        openHermesApp()
    }

    fun openHermesApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
            }
        } catch (e: Exception) {}
    }

    fun runMacroSequence(onStepChanged: (Int) -> Unit, onFinished: (Boolean, String) -> Unit) {
        if (isSequenceRunning) {
            stopMacroSequence()
            onFinished(false, "Đã chủ động dừng macro")
            return
        }
        if (activeMacroSteps.isEmpty()) {
            onFinished(false, "Danh sách bước trống")
            return
        }

        isSequenceRunning = true
        macroJob = serviceScope.launch {
            try {
                activeMacroSteps.forEachIndexed { index, step ->
                    if (!isSequenceRunning) return@launch
                    onStepChanged(index)

                    when (step.type) {
                        StepType.CLICK -> {
                            tapCoordinateSuspend(step.x, step.y)
                        }
                        StepType.SWIPE -> {
                            swipeCoordinatesSuspend(step.x, step.y, step.endX, step.endY, step.durationMs)
                        }
                        StepType.ACTION_BACK -> {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        StepType.ACTION_HOME -> {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                        StepType.ACTION_RECENTS -> {
                            performGlobalAction(GLOBAL_ACTION_RECENTS)
                        }
                        StepType.ACTION_NOTIFICATIONS -> {
                            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                        }
                        StepType.OPEN_APP -> {
                            val pm = packageManager
                            val launchIntent = pm.getLaunchIntentForPackage(step.packageName)
                            if (launchIntent != null) {
                                launchIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                startActivity(launchIntent)
                            }
                        }
                    }

                    // Log execution
                    HermesState.addLog("[AUTO-CLICK] Executed step ${index + 1}: ${step.type} (x=${step.x}, y=${step.y})")
                    delay(step.delayMs)
                }
                onFinished(true, "Hoàn thành toàn bộ macro!")
            } catch (e: Exception) {
                onFinished(false, "Lỗi khi chạy: ${e.message}")
            } finally {
                isSequenceRunning = false
                macroJob = null
            }
        }
    }

    fun stopMacroSequence() {
        isSequenceRunning = false
        macroJob?.cancel()
        macroJob = null
        HermesState.addLog("[AUTO-CLICK] Stopped running macro")
    }

    companion object {
        var instance: HermesAccessibilityService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Load persist steps from preferences on startup
        try {
            val saved = getSharedPreferences("hermes_prefs", MODE_PRIVATE).getString("saved_macro_steps", "") ?: ""
            if (saved.isNotEmpty()) {
                activeMacroSteps.clear()
                activeMacroSteps.addAll(MacroSerializer.deserializeSteps(saved))
            }
        } catch (e: Exception) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        HermesState.currentStatus.value = "Hermes Service đã kết nối thành công!"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Log basic event tracking for user visibility
        if (event.packageName != null && event.className != null) {
            val text = "[WINDOW DETECTED] App: ${event.packageName} | View: ${event.className}"
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                HermesState.addLog(text)
            }
        }

        // Live touch recording feature from actual screen interaction
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && HermesState.isRecordingByTouch.value) {
            val node = event.source
            if (node != null) {
                try {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    val x = rect.centerX().toFloat()
                    val y = rect.centerY().toFloat()
                    if (x > 0 && y > 0) {
                        // Prevent recording clicks inside our own app package to avoid infinite loops or trash commands
                        if (event.packageName?.toString() != packageName) {
                            val step = MacroStep(
                                type = StepType.CLICK,
                                x = x,
                                y = y,
                                delayMs = 1500
                            )
                            // Add step on main thread for safety
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                HermesState.macroSteps.add(step)
                                HermesState.saveMacroSteps(this)
                                activeMacroSteps.clear()
                                activeMacroSteps.addAll(HermesState.macroSteps)
                                Toast.makeText(
                                    this,
                                    "✨ Đã ghi nhận sờ chạm: (${x.toInt()}, ${y.toInt()}) [${event.packageName}]",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            HermesState.addLog("[LIVE-RECORD] Đã tự động thêm bước Click tại ($x, $y) của app ${event.packageName}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HermesAccessibility", "Error during touch recording capture: ${e.message}")
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopMacroSequence()
        hideFloatingBubble()
        instance = null
    }

    fun showFloatingBubble() {
        if (isBubbleShowing) return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val context = this
        val bubble = FrameLayout(context).apply {
            val sizeDp = 56
            val shapeDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF00D2FF.toInt()) // Neon Core Azure Blue
                setStroke(3, 0xFF05070C.toInt()) // Deep Dark Cosmic stroke border
            }
            background = shapeDrawable
            elevation = 16f

            val tv = TextView(context).apply {
                text = "H"
                setTextColor(0xFF05070C.toInt()) // Crystal onyx text for high accessibility and contrast
                textSize = 22f
                gravity = Gravity.CENTER
                setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD))
            }
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            })
        }

        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = (56 * resources.displayMetrics.density).toInt()
            height = (56 * resources.displayMetrics.density).toInt()
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 200
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDrag = false
                        v.handler?.postDelayed(longClickRunnable, 600)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (dx * dx + dy * dy > 64) {
                            isDrag = true
                            v.handler?.removeCallbacks(longClickRunnable)
                        }

                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            wm.updateViewLayout(bubble, layoutParams)
                        } catch (e: Exception) {
                            // Ignored
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.handler?.removeCallbacks(longClickRunnable)
                        if (!isDrag) {
                            // Tap action: toggle execution of real macro sequence!
                            if (isSequenceRunning) {
                                stopMacroSequence()
                                Toast.makeText(context, "⏹️ Đã dừng trình vận hành tự động!", Toast.LENGTH_SHORT).show()
                            } else {
                                if (activeMacroSteps.isEmpty()) {
                                    Toast.makeText(context, "⚠️ Chưa có bước bấm nào. Giữ phím 'H' để mở app và tự chọn vị trí!", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "▶️ Khởi chạy ${activeMacroSteps.size} bước bấm tự động...", Toast.LENGTH_SHORT).show()
                                    runMacroSequence(
                                        onStepChanged = { idx ->
                                            Toast.makeText(context, "👉 Chạy bước ${idx + 1}/${activeMacroSteps.size}", Toast.LENGTH_SHORT).show()
                                        },
                                        onFinished = { success, msg ->
                                            Toast.makeText(context, if (success) "✅ $msg" else "❌ $msg", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            wm.addView(bubble, layoutParams)
            bubbleLayout = bubble
            isBubbleShowing = true
        } catch (e: Exception) {
            Toast.makeText(context, "Không thể mở bong bóng: Vui lòng bật Hiển thị trên ứng dụng khác!", Toast.LENGTH_LONG).show()
        }
    }

    fun hideFloatingBubble() {
        if (!isBubbleShowing) return
        bubbleLayout?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Ignored
            }
        }
        bubbleLayout = null
        isBubbleShowing = false
    }

    fun tapCoordinate(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        try {
            val safeX = x.coerceAtLeast(0f)
            val safeY = y.coerceAtLeast(0f)
            val path = Path().apply {
                moveTo(safeX, safeY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            callback?.invoke(true)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            callback?.invoke(false)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
            callback?.invoke(false)
        }
    }

    fun swipeCoordinates(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300, callback: ((Boolean) -> Unit)? = null) {
        try {
            val safeStartX = startX.coerceAtLeast(0f)
            val safeStartY = startY.coerceAtLeast(0f)
            val safeEndX = endX.coerceAtLeast(0f)
            val safeEndY = endY.coerceAtLeast(0f)
            val safeDuration = durationMs.coerceAtLeast(1)
            val path = Path().apply {
                moveTo(safeStartX, safeStartY)
                lineTo(safeEndX, safeEndY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, safeDuration))
                .build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            callback?.invoke(true)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            callback?.invoke(false)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
            callback?.invoke(false)
        }
    }

    suspend fun tapCoordinateSuspend(x: Float, y: Float): Boolean = suspendCancellableCoroutine { continuation ->
        tapCoordinate(x, y) { success ->
            if (continuation.isActive) {
                continuation.resume(success)
            }
        }
    }

    suspend fun swipeCoordinatesSuspend(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean = suspendCancellableCoroutine { continuation ->
        swipeCoordinates(startX, startY, endX, endY, durationMs) { success ->
            if (continuation.isActive) {
                continuation.resume(success)
            }
        }
    }
}
