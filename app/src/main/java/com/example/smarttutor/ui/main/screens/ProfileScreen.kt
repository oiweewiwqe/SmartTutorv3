package com.example.smarttutor.ui.main.screens

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import com.example.smarttutor.data.SmartTutorRepository
import com.example.smarttutor.data.model.LearningPath
import com.example.smarttutor.data.model.LearningStep
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    userEmail: String,
    userId: String?,
    userDisplayName: String?,
    lastSignInMillis: Long?,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val repository = remember { SmartTutorRepository() }
    val firestore = remember { FirebaseFirestore.getInstance() }
    val trimmedEmail = userEmail.trim()
    val determinedName = userDisplayName?.takeIf(String::isNotBlank)
        ?: trimmedEmail.substringBefore("@").ifBlank { "Ученик" }
    var localDisplayName by rememberSaveable { mutableStateOf(determinedName) }
    val avatarLetter = localDisplayName.firstOrNull()?.uppercase() ?: "?"
    var showSettings by remember { mutableStateOf(false) }
    var showLearningPlanDetails by remember { mutableStateOf(false) }
    var selectedPlan by remember { mutableStateOf<LearningPath?>(null) }
    var showAllPlansDialog by remember { mutableStateOf(false) }
    val learningPlans = remember { mutableStateListOf<LearningPath>() }
    var didBackfillActivity by remember(userId) { mutableStateOf(false) }
    val profileUrl = "https://smarttutor.app/profile/${userId ?: "guest"}"
    var lastActiveAtMillis by remember { mutableStateOf<Long?>(lastSignInMillis) }
    var activityDays by remember { mutableStateOf(buildActivityDays(emptyMap(), 7)) }
    val onlineStatus = remember(lastActiveAtMillis, lastSignInMillis) {
        formatStatus(lastActiveAtMillis ?: lastSignInMillis)
    }
    var learningPath by remember { mutableStateOf<LearningPath?>(null) }
    var learningPathError by remember { mutableStateOf<String?>(null) }
    val streakDays = activityDays.takeWhile { it.wrote }.size
    val totalActive = activityDays.count { it.wrote }
    val visibleStatus = onlineStatus.text
    val profileCardBrush = if (darkTheme) {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
        )
    }
    val actionIconBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
        )
    )

    fun deleteTrainingPlan(plan: LearningPath) {
        val uid = userId ?: return
        if (plan.id.isBlank()) return
        firestore.collection("users").document(uid)
            .collection("deadlines")
            .whereEqualTo("planId", plan.id)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { doc -> doc.reference.delete() }
                firestore.collection("users").document(uid)
                    .collection("learningPlans")
                    .document(plan.id)
                    .delete()
                plan.sourceChatId?.takeIf { it.isNotBlank() }?.let { chatId ->
                    firestore.collection("users").document(uid)
                        .collection("chats").document(chatId)
                        .update(
                            mapOf(
                                "trainingPlanActive" to false,
                                "trainingPlanCompleted" to false,
                                "trainingPlanId" to FieldValue.delete(),
                                "trainingPlanGoal" to FieldValue.delete(),
                                "trainingPlanProgress" to FieldValue.delete()
                            )
                        )
                }
            }
    }

    DisposableEffect(userId) {
        if (userId.isNullOrBlank()) {
            lastActiveAtMillis = lastSignInMillis
            activityDays = buildActivityDays(emptyMap(), 7)
            onDispose {}
        } else {
            val registration = firestore.collection("users").document(userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        return@addSnapshotListener
                    }
                    val activityMap = snapshot?.get("activityDays") as? Map<*, *>
                    if (!didBackfillActivity && (activityMap == null || activityMap.isEmpty())) {
                        didBackfillActivity = true
                        backfillActivityFromChats(firestore, userId, 7)
                    }
                    val resolvedActivity = activityMap?.mapNotNull { (key, value) ->
                        val dayKey = key as? String ?: return@mapNotNull null
                        val wrote = value as? Boolean ?: false
                        dayKey to wrote
                    }?.toMap().orEmpty()
                    activityDays = buildActivityDays(resolvedActivity, 7)
                    lastActiveAtMillis = snapshot?.getLong("lastActiveAtMillis") ?: lastSignInMillis
                }
            onDispose { registration.remove() }
        }
    }

    DisposableEffect(userId) {
        if (userId.isNullOrBlank()) {
            learningPath = null
            learningPathError = null
            onDispose {}
        } else {
            val registration = repository.learningPathDoc()
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        learningPathError = "Не удалось загрузить план обучения."
                        return@addSnapshotListener
                    }
                    learningPathError = null
                    learningPath = snapshot?.let { parseLearningPath(it) }
                }
            onDispose { registration.remove() }
        }
    }

    DisposableEffect(userId) {
        if (userId.isNullOrBlank()) {
            learningPlans.clear()
            onDispose {}
        } else {
            val registration = repository.learningPlansQuery()
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        learningPathError = "Не удалось загрузить планы обучения."
                        return@addSnapshotListener
                    }
                    learningPathError = null
                    val plans = snapshot?.documents?.mapNotNull { doc ->
                        parseLearningPlan(doc)
                    } ?: emptyList()
                    learningPlans.clear()
                    learningPlans.addAll(plans)
                }
            onDispose { registration.remove() }
        }
    }

    if (showSettings) {
        SettingsOverlay(
            modifier = Modifier.fillMaxSize(),
            darkTheme = darkTheme,
            onToggleTheme = onToggleTheme,
            onSignOut = onSignOut,
            onClose = { showSettings = false },
            context = context,
            currentDisplayName = localDisplayName,
            onDisplayNameUpdate = { localDisplayName = it }
        )
        return
    }

    if (showLearningPlanDetails) {
        val activePlan = selectedPlan
        val orderedSteps = activePlan?.steps?.sortedBy { it.order }.orEmpty()
        val completedSteps = orderedSteps.count { it.isCompleted && !it.isSkipped }
        val totalSteps = orderedSteps.size.coerceAtLeast(1)
        val progressPercent = (completedSteps * 100 / totalSteps).coerceIn(0, 100)
        val resolvedActiveOrder = activePlan?.activeStepOrder
            ?: orderedSteps.firstOrNull { !it.isCompleted && !it.isSkipped }?.order
        AlertDialog(
            onDismissRequest = { showLearningPlanDetails = false },
            title = { Text("План обучения") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Цель: ${activePlan?.goal?.ifBlank { "Не указана" } ?: "Не указана"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = progressPercent / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Прогресс: $progressPercent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (orderedSteps.isEmpty()) {
                        Text("Шагов пока нет.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        orderedSteps.forEach { step ->
                            val isActive = resolvedActiveOrder != null && step.order == resolvedActiveOrder
                            val isCompleted = step.isCompleted && !step.isSkipped
                            val isSkipped = step.isSkipped
                            val stepBackground = if (isActive) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                Color.Transparent
                            }
                            val stepTextStyle = if (isActive) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.bodySmall
                            }
                            val stepBorder = if (isActive) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = stepBackground,
                                border = stepBorder
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "• ${formatLearningStepLabel(step)}",
                                        style = stepTextStyle
                                    )
                                    val statusText = when {
                                        isSkipped -> "Пропущен"
                                        isCompleted -> "Выполнен"
                                        isActive -> "Текущий шаг"
                                        else -> null
                                    }
                                    if (statusText != null) {
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSkipped) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        )
                                    }
                                }
                            }
                            step.description?.takeIf { it.isNotBlank() }?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLearningPlanDetails = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    if (showAllPlansDialog) {
        AlertDialog(
            onDismissRequest = { showAllPlansDialog = false },
            title = { Text("Все планы обучения") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (learningPlans.isEmpty()) {
                        Text("Планов обучения пока нет.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        learningPlans.forEach { plan ->
                            val orderedSteps = plan.steps.sortedBy { it.order }
                            val completedSteps = orderedSteps.count { it.isCompleted && !it.isSkipped }
                            val totalSteps = orderedSteps.size.coerceAtLeast(1)
                            val progressPercent = (completedSteps * 100 / totalSteps).coerceIn(0, 100)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPlan = plan
                                        showLearningPlanDetails = true
                                        showAllPlansDialog = false
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = plan.goal.ifBlank { "Без темы" },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            if (progressPercent >= 100) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "План завершен",
                                                    tint = Color(0xFF2E7D32)
                                                )
                                            }
                                            IconButton(onClick = { deleteTrainingPlan(plan) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Удалить план"
                                                )
                                            }
                                        }
                                    }
                                    LinearProgressIndicator(
                                        progress = progressPercent / 100f,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                    )
                                    Text(
                                        text = "Прогресс: $progressPercent%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAllPlansDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Профиль", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionIcon(
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { shareProfile(context, profileUrl, localDisplayName) }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Поделиться профилем")
                }
                ActionIcon(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { showSettings = true }
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Открыть настройки")
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(28.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(profileCardBrush)
        ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = if (darkTheme) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = avatarLetter,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localDisplayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (darkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = trimmedEmail.ifBlank { "Без email" },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (darkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                        Spacer(modifier = Modifier.size(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(10.dp),
                                shape = CircleShape,
                                color = if (onlineStatus.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            ) {}
                            Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = visibleStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (darkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (trimmedEmail.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(trimmedEmail))
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать email")
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Прогресс", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ProgressChip("$streakDays дней подряд", MaterialTheme.colorScheme.primary, darkTheme)
                        ProgressChip("$totalActive из 7 дней активности", MaterialTheme.colorScheme.secondary, darkTheme)
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(activityDays) { day ->
                            ActivityDayChip(day, darkTheme)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Планы обучения", style = MaterialTheme.typography.titleSmall)
                        if (learningPlans.size > 3) {
                            TextButton(onClick = { showAllPlansDialog = true }) {
                                Text("Показать все планы")
                            }
                        }
                    }
                    learningPathError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (learningPlans.isEmpty()) {
                        Text(
                            text = "Планов обучения пока нет.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        learningPlans.take(3).forEach { plan ->
                            val orderedSteps = plan.steps.sortedBy { it.order }
                            val completedSteps = orderedSteps.count { it.isCompleted && !it.isSkipped }
                            val totalSteps = orderedSteps.size.coerceAtLeast(1)
                            val progressPercent = (completedSteps * 100 / totalSteps).coerceIn(0, 100)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPlan = plan
                                        showLearningPlanDetails = true
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = plan.goal.ifBlank { "Без темы" },
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (progressPercent >= 100) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "План завершен",
                                                    tint = Color(0xFF2E7D32)
                                                )
                                            }
                                            IconButton(onClick = { deleteTrainingPlan(plan) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Удалить план"
                                                )
                                            }
                                        }
                                    }
                                    LinearProgressIndicator(
                                        progress = progressPercent / 100f,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                    )
                                    Text(
                                        text = "Прогресс: $progressPercent%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

@Composable
private fun SettingsOverlay(
    modifier: Modifier = Modifier,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
    context: Context,
    currentDisplayName: String,
    onDisplayNameUpdate: (String) -> Unit
) {
    var isUpdating by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var securityFeedback by remember { mutableStateOf<String?>(null) }
    var securityFeedbackIsError by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var soundEnabled by remember { mutableStateOf(true) }
    val hasNotificationPermission = remember {
        mutableStateOf(checkNotificationPermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission.value = granted
        if (granted && notificationsEnabled) {
            context.sendReminderNotification(soundEnabled)
        }
    }
    var displayNameDraft by rememberSaveable { mutableStateOf(currentDisplayName) }
    var profileFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var profileFeedbackIsError by rememberSaveable { mutableStateOf(false) }
    var isSavingName by remember { mutableStateOf(false) }
    val auth = remember { FirebaseAuth.getInstance() }
    val settingsScrollState = rememberScrollState()

    fun showSecurityFeedback(message: String, isError: Boolean) {
        securityFeedback = message
        securityFeedbackIsError = isError
    }

    fun updatePassword() {
        val user = auth.currentUser ?: run {
            showSecurityFeedback("Сначала войдите заново.", true)
            return
        }
        if (currentPassword.isBlank()) {
            showSecurityFeedback("Введите текущий пароль.", true)
            return
        }
        if (newPassword.length < 6) {
            showSecurityFeedback("Пароль должен быть минимум 6 символов.", true)
            return
        }
        if (newPassword != confirmPassword) {
            showSecurityFeedback("Пароль и подтверждение не совпадают.", true)
            return
        }
        val email = user.email ?: run {
            showSecurityFeedback("Не удалось определить email.", true)
            return
        }
        isUpdating = true
        val credential = EmailAuthProvider.getCredential(email, currentPassword)
        user.reauthenticate(credential).addOnCompleteListener { reauthResult ->
            if (!reauthResult.isSuccessful) {
                isUpdating = false
                showSecurityFeedback(
                    "Текущий пароль не подтвердился. Проверьте ввод.",
                    true
                )
                return@addOnCompleteListener
            }
            user.updatePassword(newPassword).addOnCompleteListener { task ->
                isUpdating = false
                if (task.isSuccessful) {
                    showSecurityFeedback("Пароль успешно изменён.", false)
                    newPassword = ""
                    confirmPassword = ""
                    currentPassword = ""
                } else {
                    showSecurityFeedback("Не удалось сменить пароль. Попробуйте позже.", true)
                }
            }
        }
    }

    fun saveDisplayName(newName: String) {
        if (newName.isBlank()) {
            profileFeedback = "Имя не может быть пустым."
            profileFeedbackIsError = true
            return
        }
        val user = auth.currentUser ?: run {
            profileFeedback = "Нужно авторизоваться."
            profileFeedbackIsError = true
            return
        }
        isSavingName = true
        val request = userProfileChangeRequest {
            displayName = newName
        }
        user.updateProfile(request).addOnCompleteListener { task ->
            isSavingName = false
            if (task.isSuccessful) {
                onDisplayNameUpdate(newName)
                profileFeedback = "Имя обновлено."
                profileFeedbackIsError = false
            } else {
                profileFeedback = "Не удалось сохранить имя."
                profileFeedbackIsError = true
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(settingsScrollState)
            .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Настройки", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть настройки")
            }
        }

        SettingsSection(title = "Профиль") {
            OutlinedTextField(
                value = displayNameDraft,
                onValueChange = { displayNameDraft = it },
                label = { Text("Имя пользователя") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )
            Button(
                onClick = { saveDisplayName(displayNameDraft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                enabled = !isSavingName
            ) {
                Text(if (isSavingName) "Сохраняем..." else "Сохранить имя")
            }
            profileFeedback?.let {
                Text(
                    modifier = Modifier.padding(top = 6.dp),
                    text = it,
                    color = if (profileFeedbackIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Divider()

        SettingsSection(title = "Безопасность") {
            OutlinedTextField(
                value = currentPassword,
                onValueChange = { currentPassword = it },
                label = { Text("Текущий пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("Новый пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Подтвердите пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )
            Button(
                onClick = { updatePassword() },
                enabled = newPassword.isNotBlank() && confirmPassword.isNotBlank() && !isUpdating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text("Изменить пароль")
            }
            securityFeedback?.let {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = it,
                    color = if (securityFeedbackIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Divider()

        SettingsSection(title = "Внешний вид") {
            SettingsSwitch(
                label = if (darkTheme) "Тёмная тема" else "Светлая тема",
                checked = darkTheme,
                onCheckedChange = { onToggleTheme() }
            )
        }

        Divider()

        SettingsSection(title = "Уведомления") {
            SettingsSwitch(
                label = "Включить уведомления",
                checked = notificationsEnabled,
                onCheckedChange = { enabled ->
                    notificationsEnabled = enabled
                    if (enabled) {
                        if (hasNotificationPermission.value || !requiresNotificationPermission()) {
                            context.sendReminderNotification(soundEnabled)
                        } else {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            )
            SettingsSwitch(
                label = "Звуковые сигналы",
                checked = soundEnabled,
                onCheckedChange = { enabled ->
                    soundEnabled = enabled
                    if (notificationsEnabled &&
                        (hasNotificationPermission.value || !requiresNotificationPermission())
                    ) {
                        context.sendReminderNotification(enabled)
                    }
                },
                enabled = notificationsEnabled
            )
        }

        Divider()

        Button(
            onClick = {
                onSignOut()
                onClose()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text("Выйти из аккаунта")
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun SettingsActionButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text)
    }
}

 @Composable
 private fun SettingsSwitch(
     label: String,
     checked: Boolean,
     onCheckedChange: (Boolean) -> Unit,
     enabled: Boolean = true
 ) {
     Row(
         modifier = Modifier.fillMaxWidth(),
         horizontalArrangement = Arrangement.SpaceBetween,
         verticalAlignment = Alignment.CenterVertically
     ) {
         Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
         Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
     }
 }

private fun shareProfile(context: Context, profileUrl: String, displayName: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$displayName — $profileUrl")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться профилем"))
}

private data class StatusInfo(val text: String, val isOnline: Boolean)

private fun formatStatus(lastSignInMillis: Long?): StatusInfo {
    val now = System.currentTimeMillis()
    if (lastSignInMillis == null) {
        return StatusInfo("Статус неизвестен", false)
    }
    val diff = now - lastSignInMillis
    return if (diff <= 5 * 60 * 1000) {
        StatusInfo("В сети", true)
    } else {
        StatusInfo("Был(а) в сети ${formatDuration(diff)} назад", false)
    }
}

private fun formatDuration(durationMillis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
    val days = TimeUnit.MILLISECONDS.toDays(durationMillis)
    return when {
        days > 0 -> "$days ${plural(days, "день", "дня", "дней")}"
        hours > 0 -> "$hours ${plural(hours, "час", "часа", "часов")}"
        minutes > 0 -> "$minutes ${plural(minutes, "минута", "минуты", "минут")}"
        else -> "меньше минуты"
    }
}


@Composable
private fun ProgressChip(
    text: String,
    color: Color,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val chipBrush = Brush.linearGradient(
        colors = if (darkTheme) {
            listOf(
                color.copy(alpha = 0.4f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            )
        } else {
            listOf(
                color.copy(alpha = 0.3f),
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            )
        }
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color.copy(alpha = 0.95f)),
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .background(chipBrush, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun parseLearningPath(snapshot: DocumentSnapshot): LearningPath? {
    if (!snapshot.exists()) return null
    val goal = snapshot.getString("goal").orEmpty()
    val sourceChatId = snapshot.getString("sourceChatId")
    val createdAtMillis = snapshot.getLong("createdAtMillis") ?: System.currentTimeMillis()
    val updatedAtMillis = snapshot.getLong("updatedAtMillis") ?: createdAtMillis
    val steps = (snapshot.get("steps") as? List<*>)?.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val title = map["title"] as? String ?: return@mapNotNull null
        val order = (map["order"] as? Number)?.toInt() ?: 0
        val kind = map["kind"] as? String ?: "step"
        val description = map["description"] as? String
        val isCompleted = map["isCompleted"] as? Boolean ?: false
        val isSkipped = map["isSkipped"] as? Boolean ?: false
        val completedAtMillis = (map["completedAtMillis"] as? Number)?.toLong()
        LearningStep(
            title = title,
            order = order,
            kind = kind,
            description = description,
            isCompleted = isCompleted,
            isSkipped = isSkipped,
            completedAtMillis = completedAtMillis
        )
    }?.sortedBy { it.order }.orEmpty()
    val activeStepOrder = (snapshot.get("activeStepOrder") as? Number)?.toInt()
    return LearningPath(
        id = snapshot.id,
        goal = goal,
        steps = steps,
        activeStepOrder = activeStepOrder,
        sourceChatId = sourceChatId,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}

private fun parseLearningPlan(snapshot: DocumentSnapshot): LearningPath? {
    if (!snapshot.exists()) return null
    val goal = snapshot.getString("goal").orEmpty()
    val sourceChatId = snapshot.getString("sourceChatId")
    val createdAtMillis = snapshot.getLong("createdAtMillis") ?: System.currentTimeMillis()
    val updatedAtMillis = snapshot.getLong("updatedAtMillis") ?: createdAtMillis
    val steps = (snapshot.get("steps") as? List<*>)?.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val title = map["title"] as? String ?: return@mapNotNull null
        val order = (map["order"] as? Number)?.toInt() ?: 0
        val kind = map["kind"] as? String ?: "step"
        val description = map["description"] as? String
        val isCompleted = map["isCompleted"] as? Boolean ?: false
        val isSkipped = map["isSkipped"] as? Boolean ?: false
        val completedAtMillis = (map["completedAtMillis"] as? Number)?.toLong()
        LearningStep(
            title = title,
            order = order,
            kind = kind,
            description = description,
            isCompleted = isCompleted,
            isSkipped = isSkipped,
            completedAtMillis = completedAtMillis
        )
    }?.sortedBy { it.order }.orEmpty()
    val activeStepOrder = (snapshot.get("activeStepOrder") as? Number)?.toInt()
    return LearningPath(
        id = snapshot.id,
        goal = goal,
        steps = steps,
        activeStepOrder = activeStepOrder,
        sourceChatId = sourceChatId,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}

private fun formatLearningStepLabel(step: LearningStep): String {
    val prefix = when (step.kind.lowercase(Locale.getDefault())) {
        "explanation" -> "Объяснение"
        "task" -> "Задание"
        "test" -> "Тест"
        "final_exam" -> "Итоговый экзамен"
        else -> "Шаг"
    }
    return "$prefix: ${step.title}"
}

@Composable
private fun ActivityDayChip(day: ActivityDay, darkTheme: Boolean) {
    val activityColor = if (day.wrote) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val fillGradientStart = if (day.wrote) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (darkTheme) 0.35f else 0.5f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (darkTheme) 0.25f else 0.32f)
    }
    val fillGradientEnd = fillGradientStart.copy(alpha = 0.9f)

    Surface(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .height(60.dp),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
        color = Color.Transparent,
        border = BorderStroke(1.dp, activityColor.copy(alpha = 0.35f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(fillGradientStart, fillGradientEnd)),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    day.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (day.wrote) "Писал" else "Не писал",
                    style = MaterialTheme.typography.labelSmall,
                    color = activityColor
                )
            }
        }
    }
}

private data class ActivityDay(val label: String, val wrote: Boolean)

private fun buildActivityDays(activityMap: Map<String, Boolean>, count: Int): List<ActivityDay> {
    val days = mutableListOf<ActivityDay>()
    val calendar = Calendar.getInstance()
    val labelFormatter = SimpleDateFormat("EE, d MMM", Locale("ru"))
    for (i in 0 until count) {
        val date = calendar.time
        val key = formatDayKey(date.time)
        val label = labelFormatter.format(date)
        days += ActivityDay(label, activityMap[key] == true)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
    }
    return days
}

private fun formatDayKey(millis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return formatter.format(Date(millis))
}

private fun backfillActivityFromChats(
    firestore: FirebaseFirestore,
    userId: String,
    days: Int
) {
    if (days <= 0) return
    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong() - 1)
    firestore.collection("users").document(userId)
        .collection("chats")
        .get()
        .addOnSuccessListener { snapshot ->
            val activity = mutableMapOf<String, Boolean>()
            snapshot.documents.forEach { doc ->
                val updatedAt = doc.getLong("updatedAtMillis")
                    ?: doc.getLong("createdAtMillis")
                    ?: return@forEach
                if (updatedAt >= cutoff) {
                    activity[formatDayKey(updatedAt)] = true
                }
            }
            if (activity.isNotEmpty()) {
                firestore.collection("users").document(userId)
                    .set(mapOf("activityDays" to activity), SetOptions.merge())
            }
        }
}

private fun plural(value: Long, one: String, few: String, many: String): String {
    return when {
        value % 10L == 1L && value % 100L != 11L -> one
        value % 10L in 2L..4L && value % 100L !in 12L..14L -> few
        else -> many
    }
}

private const val REMINDER_CHANNEL_ID = "smarttutor_reminder_channel"
private const val REMINDER_NOTIFICATION_ID = 1001

private fun requiresNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

private fun checkNotificationPermission(context: Context): Boolean {
    if (!requiresNotificationPermission()) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.ensureReminderChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(REMINDER_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID,
                "Напоминания Smart Tutor",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }
}

private fun Context.sendReminderNotification(withSound: Boolean) {
    if (!checkNotificationPermission(this)) return
    ensureReminderChannel()
    val builder = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Smart Tutor")
        .setContentText("ChatGPT приготовил новое напоминание.")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
    if (withSound) {
        builder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
    }
    NotificationManagerCompat.from(this).notify(REMINDER_NOTIFICATION_ID, builder.build())
}

@Composable
private fun ActionIcon(
    modifier: Modifier = Modifier,
    tint: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val iconBrush = Brush.linearGradient(
        colors = listOf(
            tint.copy(alpha = 0.65f),
            tint.copy(alpha = 0.3f)
        )
    )
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(iconBrush, RoundedCornerShape(14.dp))
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            content()
        }
    }
}
