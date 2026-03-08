package com.example.flowpilot

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.flowpilot.automation.FlowPilotAccessibilityService
import com.example.flowpilot.capture.ProjectionPermissionStore
import com.example.flowpilot.capture.ScreenCaptureManager
import com.example.flowpilot.core.FlowPilotSettings
import com.example.flowpilot.model.PlanResponse
import com.example.flowpilot.network.PlannerClient
import com.example.flowpilot.ui.theme.FlowPilotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val plannerClient = PlannerClient()
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var capturePermissionLauncher: ActivityResultLauncher<Intent>

    private var backendUrl by mutableStateOf("http://10.0.2.2:8080")
    private var instruction by mutableStateOf("")
    private var statusMessage by mutableStateOf("Grant screen capture and accessibility permissions to begin.")
    private var isBusy by mutableStateOf(false)
    private var strictSafetyEnabled by mutableStateOf(true)
    private var showRiskyConfirmDialog by mutableStateOf(false)
    private var pendingRiskySteps by mutableStateOf<List<com.example.flowpilot.model.PlanStep>>(emptyList())
    private var planResult by mutableStateOf<PlanResponse?>(null)

    private val externalCaptureCountdownSeconds = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val config = FlowPilotSettings.read(this)
        backendUrl = config.backendUrl
        instruction = config.instruction
        strictSafetyEnabled = config.strictSafetyEnabled

        screenCaptureManager = ScreenCaptureManager(this)
        capturePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK && result.data != null) {
                    ProjectionPermissionStore.set(result.resultCode, Intent(result.data))
                    statusMessage = "Screen capture permission granted."
                } else {
                    statusMessage = "Screen capture permission was not granted."
                }
            }

        setContent {
            FlowPilotTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FlowPilotScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        backendUrl = backendUrl,
                        onBackendUrlChange = {
                            backendUrl = it
                            FlowPilotSettings.setBackendUrl(this@MainActivity, it)
                        },
                        instruction = instruction,
                        onInstructionChange = {
                            instruction = it
                            FlowPilotSettings.setInstruction(this@MainActivity, it)
                        },
                        statusMessage = statusMessage,
                        isBusy = isBusy,
                        strictSafetyEnabled = strictSafetyEnabled,
                        onStrictSafetyEnabledChange = {
                            strictSafetyEnabled = it
                            FlowPilotSettings.setStrictSafetyEnabled(this@MainActivity, it)
                        },
                        planResult = planResult,
                        showRiskyConfirmDialog = showRiskyConfirmDialog,
                        pendingRiskySteps = pendingRiskySteps,
                        onDismissRiskyConfirm = {
                            showRiskyConfirmDialog = false
                            pendingRiskySteps = emptyList()
                            statusMessage = "Execution cancelled by user due to strict safety mode."
                        },
                        onConfirmRiskyExecution = {
                            showRiskyConfirmDialog = false
                            val steps = pendingRiskySteps
                            pendingRiskySteps = emptyList()
                            actuallyExecuteSteps(steps)
                        },
                        onGrantCapture = { requestScreenCapturePermission() },
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onCaptureAndPlan = { captureAndPlan() },
                        onExecutePlan = { executeLastPlan() }
                    )
                }
            }
        }
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            statusMessage = "MediaProjection manager unavailable on this device."
            return
        }

        capturePermissionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun captureAndPlan() {
        val projectionPermission = consumeProjectionPermission()

        if (projectionPermission == null) {
            statusMessage = "Grant screen capture permission first."
            return
        }

        val (resultCode, dataIntent) = projectionPermission

        if (instruction.isBlank()) {
            statusMessage = "Enter a natural-language instruction first."
            return
        }

        isBusy = true
        statusMessage = "Switch to the target app. Capture starts in ${externalCaptureCountdownSeconds}s..."

        lifecycleScope.launch {
            try {
                sendAppToBackground()

                for (remaining in externalCaptureCountdownSeconds downTo 1) {
                    statusMessage = "Open target app now. Capturing in ${remaining}s..."
                    delay(1_000)
                }

                statusMessage = "Capturing target app screen..."
                val screenshotDataUrl = withContext(Dispatchers.IO) {
                    screenCaptureManager.captureScreenshotDataUrl(resultCode, dataIntent)
                }

                val mobileInstruction =
                    "$instruction\n\nContext: Android UI automation. Output executable actions for mobile usage. Prefer actions using x and y coordinates in screen pixels for tap targets."

                statusMessage = "Requesting plan from backend..."
                val plan = withContext(Dispatchers.IO) {
                    plannerClient.plan(
                        backendUrl = backendUrl,
                        userInstruction = mobileInstruction,
                        screenshotDataUrl = screenshotDataUrl,
                        currentUrl = "android://screen"
                    )
                }

                planResult = plan
                statusMessage = "Plan received with ${plan.steps.size} steps. Grant Capture again for the next screenshot."
            } catch (error: Exception) {
                statusMessage = "Plan failed: ${error.message ?: "Unknown error"}. Grant Capture again and retry."
            } finally {
                bringFlowPilotToFront()
                isBusy = false
            }
        }
    }

    private fun sendAppToBackground() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun bringFlowPilotToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun consumeProjectionPermission(): Pair<Int, Intent>? {
        return ProjectionPermissionStore.consume()
    }

    private fun executeLastPlan() {
        val steps = planResult?.steps.orEmpty()
        if (steps.isEmpty()) {
            statusMessage = "No steps available. Capture and plan first."
            return
        }

        if (strictSafetyEnabled) {
            val riskySteps = steps.filter(::isRiskyStep)
            if (riskySteps.isNotEmpty()) {
                pendingRiskySteps = steps
                showRiskyConfirmDialog = true
                statusMessage = "Strict safety mode requires confirmation for risky actions."
                return
            }
        }

        actuallyExecuteSteps(steps)
    }

    private fun actuallyExecuteSteps(steps: List<com.example.flowpilot.model.PlanStep>) {
        if (steps.isEmpty()) {
            statusMessage = "No steps available. Capture and plan first."
            return
        }

        val connected = FlowPilotAccessibilityService.isConnected()
        if (!connected) {
            statusMessage = "Accessibility service is not enabled yet. Open Accessibility settings and enable FlowPilot Navigator."
            return
        }

        val started = FlowPilotAccessibilityService.executeSteps(steps)
        statusMessage = if (started) {
            "Executing ${steps.size} steps through accessibility service."
        } else {
            "Failed to start execution."
        }
    }

    private fun isRiskyStep(step: com.example.flowpilot.model.PlanStep): Boolean {
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
}

@Composable
private fun FlowPilotScreen(
    modifier: Modifier,
    backendUrl: String,
    onBackendUrlChange: (String) -> Unit,
    instruction: String,
    onInstructionChange: (String) -> Unit,
    statusMessage: String,
    isBusy: Boolean,
    strictSafetyEnabled: Boolean,
    onStrictSafetyEnabledChange: (Boolean) -> Unit,
    planResult: PlanResponse?,
    showRiskyConfirmDialog: Boolean,
    pendingRiskySteps: List<com.example.flowpilot.model.PlanStep>,
    onDismissRiskyConfirm: () -> Unit,
    onConfirmRiskyExecution: () -> Unit,
    onGrantCapture: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onCaptureAndPlan: () -> Unit,
    onExecutePlan: () -> Unit
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "FlowPilot Android UI Navigator")

        OutlinedTextField(
            value = backendUrl,
            onValueChange = onBackendUrlChange,
            label = { Text("Backend URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = instruction,
            onValueChange = onInstructionChange,
            label = { Text("Instruction") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Strict safety mode")
            Switch(
                checked = strictSafetyEnabled,
                onCheckedChange = onStrictSafetyEnabledChange
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onGrantCapture) {
                Text("Grant Capture")
            }
            Button(onClick = onOpenAccessibility) {
                Text("Accessibility")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCaptureAndPlan, enabled = !isBusy) {
                Text("Capture + Plan (5s)")
            }
            Button(onClick = onExecutePlan, enabled = !isBusy) {
                Text("Execute")
            }
        }

        if (isBusy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text("Working...")
            }
        }

        Text(text = "Status: $statusMessage")

        HorizontalDivider()

        Text(text = "Plan Output")
        Text(text = "Summary: ${planResult?.summary ?: "-"}")
        Text(text = "Confidence: ${((planResult?.confidence ?: 0.0) * 100).toInt()}%")
        Text(text = "Clarification: ${planResult?.clarifyingQuestion ?: "-"}")
        Text(text = "Steps:")
        planResult?.steps?.forEachIndexed { index, step ->
            Text(
                text = "${index + 1}. ${step.action} x=${step.x ?: "-"} y=${step.y ?: "-"} text=${step.text ?: ""}",
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        if (showRiskyConfirmDialog) {
            AlertDialog(
                onDismissRequest = onDismissRiskyConfirm,
                title = { Text("Confirm risky actions") },
                text = {
                    val preview = pendingRiskySteps
                        .take(5)
                        .joinToString("\n") { step ->
                            "• ${step.action} ${step.selector ?: step.url ?: step.text ?: ""}".trim()
                        }
                    Text("Strict safety mode detected risky actions:\n$preview\n\nProceed with execution?")
                },
                confirmButton = {
                    TextButton(onClick = onConfirmRiskyExecution) {
                        Text("Proceed")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissRiskyConfirm) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}