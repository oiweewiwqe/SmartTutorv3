package com.example.smarttutor.data.model

data class LearningStep(
    val title: String = "",
    val order: Int = 0,
    val kind: String = "step",
    val description: String? = null,
    val isCompleted: Boolean = false,
    val isSkipped: Boolean = false,
    val completedAtMillis: Long? = null
)

data class LearningPath(
    val id: String = "",
    val goal: String = "",
    val steps: List<LearningStep> = emptyList(),
    val activeStepOrder: Int? = null,
    val sourceChatId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)
