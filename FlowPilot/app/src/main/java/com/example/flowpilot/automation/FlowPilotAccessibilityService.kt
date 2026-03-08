package com.example.flowpilot.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.flowpilot.MainActivity
import com.example.flowpilot.capture.ProjectionPermissionStore
import com.example.flowpilot.capture.ScreenCaptureManager
import com.example.flowpilot.core.FlowPilotSettings
import com.example.flowpilot.model.PlanStep
import com.example.flowpilot.network.PlannerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs

class FlowPilotAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val plannerClient = PlannerClient()
    private val screenCaptureManager by lazy { ScreenCaptureManager(this) }

    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private var menuView: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null

    private var latestPlanSteps: List<PlanStep> = emptyList()
    private var plannerBusy: Boolean = false

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ensureOverlayViews()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeOverlayViews()
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlayViews()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    fun executeStepsInternal(steps: List<PlanStep>) {
        serviceScope.launch {
            menuView?.visibility = View.GONE
            bubbleView?.visibility = View.GONE
            try {
                for (step in steps) {
                    executeStep(step)
                }
            } finally {
                bubbleView?.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun executeStep(step: PlanStep) {
        when (step.action.lowercase()) {
            "wait" -> delay(step.durationMs ?: 800L)
            "click", "tap" -> {
                if (step.x != null && step.y != null) {
                    performTap(step.x, step.y)
                } else {
                    clickBySemanticHint(step)
                }
            }

            "type", "input" -> {
                step.text?.let { setTextOnFocusedNode(it) }
            }

            "keypress" -> {
                when (step.key?.uppercase()) {
                    "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    else -> Unit
                }
            }

            "navigate" -> {
                step.url?.let { openUrl(it) }
            }

            else -> Unit
        }
    }

    private suspend fun clickBySemanticHint(step: PlanStep): Boolean {
        val hints = listOfNotNull(step.text, step.selector, step.targetSelector, step.url)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (hint in hints) {
            if (clickByHint(hint)) {
                return true
            }
        }

        return false
    }

    private suspend fun clickByHint(hint: String): Boolean {
        val root = rootInActiveWindow ?: return false

        val matches = root.findAccessibilityNodeInfosByText(hint)
        for (index in matches.indices) {
            val node = matches[index]
            val clicked = clickNodeOrCenter(node)
            node.recycle()
            if (clicked) {
                for (remainingIndex in matches.indices) {
                    if (remainingIndex != index) {
                        matches[remainingIndex].recycle()
                    }
                }
                return true
            }
        }

        val contentDescNode = findNodeByContentDescription(root, hint)
        val clickedByDesc = clickNodeOrCenter(contentDescNode)
        contentDescNode?.recycle()
        return clickedByDesc
    }

    private suspend fun clickNodeOrCenter(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val clickableNode = findClickableNode(node)
        if (clickableNode != null) {
            val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickableNode.recycle()
            if (clicked) {
                return true
            }
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            return performTap(bounds.exactCenterX(), bounds.exactCenterY())
        }

        return false
    }

    private fun findClickableNode(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(start)
        while (current != null) {
            if (current.isEnabled && current.isVisibleToUser && current.isClickable) {
                return current
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return null
    }

    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val contentDescription = node.contentDescription?.toString().orEmpty()
            if (contentDescription.contains(hint, ignoreCase = true)) {
                while (queue.isNotEmpty()) {
                    queue.removeFirst().recycle()
                }
                return node
            }

            val childCount = node.childCount
            for (index in 0 until childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }

            node.recycle()
        }

        return null
    }

    private suspend fun performTap(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                Handler(Looper.getMainLooper())
            )

            if (!dispatched && continuation.isActive) {
                continuation.resume(false)
            }
        }

    private fun setTextOnFocusedNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focusedNode.recycle()
        return success
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun ensureOverlayViews() {
        if (bubbleView != null && menuView != null) return

        val wm = windowManager ?: return

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        bubbleView = TextView(this).apply {
            text = "FP"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(26, 20, 26, 20)
            setBackgroundColor(Color.parseColor("#3F51B5"))
            alpha = 0.95f
        }

        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0

        bubbleView?.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    params.x = downX + dx
                    params.y = downY + dy
                    runCatching { wm.updateViewLayout(bubbleView, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - downRawX)
                    val dy = abs(event.rawY - downRawY)
                    if (dx < 12f && dy < 12f) {
                        toggleMenu()
                    }
                    true
                }

                else -> false
            }
        }

        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 410
        }

        menuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DDE3F4"))
            setPadding(12, 12, 12, 12)
            visibility = View.GONE
            addView(menuButton("Capture + Plan") { performOverlayCapture(autoExecute = false) })
            addView(menuButton("Capture + Execute") { performOverlayCapture(autoExecute = true) })
            addView(menuButton("Execute Last") { executeLatestPlan() })
            addView(menuButton("Open FlowPilot") { openFlowPilotApp() })
            addView(menuButton("Hide Menu") { visibility = View.GONE })
        }

        runCatching {
            wm.addView(bubbleView, bubbleParams)
            wm.addView(menuView, menuParams)
        }
    }

    private fun menuButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setOnClickListener { action() }
        }
    }

    private fun toggleMenu() {
        val menu = menuView ?: return
        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun performOverlayCapture(autoExecute: Boolean) {
        if (plannerBusy) {
            toast("FlowPilot is already planning. Please wait.")
            return
        }

        val settings = FlowPilotSettings.read(this)
        if (settings.instruction.isBlank()) {
            toast("Set instruction in FlowPilot app first.")
            openFlowPilotApp()
            return
        }

        val projectionPermission = ProjectionPermissionStore.consume()
        if (projectionPermission == null) {
            toast("Tap Grant Capture in FlowPilot app before using floating capture.")
            openFlowPilotApp()
            return
        }

        plannerBusy = true
        menuView?.visibility = View.GONE
        bubbleView?.visibility = View.GONE

        serviceScope.launch {
            try {
                delay(200)

                val screenshotDataUrl = withContext(Dispatchers.IO) {
                    screenCaptureManager.captureScreenshotDataUrl(
                        projectionPermission.first,
                        projectionPermission.second
                    )
                }

                val mobileInstruction =
                    "${settings.instruction}\n\nContext: Android UI automation. Output executable actions for mobile usage. Prefer actions using x and y coordinates in screen pixels for tap targets."

                val plan = withContext(Dispatchers.IO) {
                    plannerClient.plan(
                        backendUrl = settings.backendUrl,
                        userInstruction = mobileInstruction,
                        screenshotDataUrl = screenshotDataUrl,
                        currentUrl = "android://floating-overlay"
                    )
                }

                latestPlanSteps = plan.steps
                toast("Plan ready: ${plan.steps.size} steps")

                if (autoExecute) {
                    if (settings.strictSafetyEnabled && plan.steps.any(::isRiskyStep)) {
                        toast("Risky steps detected. Open FlowPilot to review first.")
                        openFlowPilotApp()
                    } else {
                        executeStepsInternal(plan.steps)
                    }
                }
            } catch (error: Exception) {
                toast("Capture failed: ${error.message ?: "Unknown error"}")
            } finally {
                plannerBusy = false
                bubbleView?.visibility = View.VISIBLE
            }
        }
    }

    private fun executeLatestPlan() {
        val settings = FlowPilotSettings.read(this)
        if (latestPlanSteps.isEmpty()) {
            toast("No captured plan yet. Use Capture + Plan first.")
            return
        }

        if (settings.strictSafetyEnabled && latestPlanSteps.any(::isRiskyStep)) {
            toast("Risky steps detected. Open FlowPilot to confirm.")
            openFlowPilotApp()
            return
        }

        executeStepsInternal(latestPlanSteps)
    }

    private fun openFlowPilotApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun removeOverlayViews() {
        val wm = windowManager ?: return
        bubbleView?.let { runCatching { wm.removeView(it) } }
        menuView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        menuView = null
        bubbleParams = null
        menuParams = null
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isRiskyStep(step: PlanStep): Boolean {
        val riskyActions = setOf("delete", "pay", "purchase", "transfer", "checkout", "submit")
        val action = step.action.lowercase()
        if (action in riskyActions) {
            return true
        }

        val keywords = listOf("send", "delete", "remove", "trash", "pay", "payment", "purchase", "checkout", "buy", "transfer")
        val haystack = listOfNotNull(step.selector, step.text, step.key, step.url, step.targetSelector)
            .joinToString(" ")
            .lowercase()

        return keywords.any { haystack.contains(it) }
    }

    companion object {
        @Volatile
        private var instance: FlowPilotAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun executeSteps(steps: List<PlanStep>): Boolean {
            val service = instance ?: return false
            service.executeStepsInternal(steps)
            return true
        }
    }
}
