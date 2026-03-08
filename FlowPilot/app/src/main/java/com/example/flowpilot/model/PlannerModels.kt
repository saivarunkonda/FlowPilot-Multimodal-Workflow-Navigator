package com.example.flowpilot.model

data class PlanStep(
    val action: String,
    val selector: String? = null,
    val text: String? = null,
    val key: String? = null,
    val url: String? = null,
    val targetSelector: String? = null,
    val durationMs: Long? = null,
    val x: Float? = null,
    val y: Float? = null
)

data class PlanResponse(
    val workflowId: String,
    val summary: String,
    val confidence: Double,
    val needsConfirmation: Boolean,
    val clarifyingQuestion: String?,
    val steps: List<PlanStep>
)
