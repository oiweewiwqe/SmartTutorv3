package com.example.smarttutor.ui.main.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarttutor.R
import com.example.smarttutor.data.ai.OpenRouterClient
import com.example.smarttutor.data.model.ChatMessage
import com.example.smarttutor.ui.main.components.MessageBubble
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import com.example.smarttutor.data.ai.AiRequestScope
import com.example.smarttutor.data.ai.AiRequestTracker
import androidx.compose.runtime.derivedStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    chatId: String?,
    chatTitle: String?,
    activeReminderId: String?,
    activeReminderMessageId: String?,
    scrollToReminderMessage: Boolean,
    replyTaskText: String?,
    onReplyTaskChange: (String?) -> Unit,
    onReminderScrollConsumed: () -> Unit,
    onChatCreated: (String, String) -> Unit
) {
    val logTag = "PlanFlow"
    val auth = remember { FirebaseAuth.getInstance() }
    val firestore = remember { FirebaseFirestore.getInstance() }
    val openRouterClient = remember { OpenRouterClient() }
    val user = auth.currentUser
    val uiScope = rememberCoroutineScope()
    var messageText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val isSending = AiRequestTracker.isSending(chatId)
    val listState = rememberLazyListState()
    var pendingPlanTopic by remember { mutableStateOf<String?>(null) }
    var pendingPlanChatId by remember { mutableStateOf<String?>(null) }
    var pendingPlanStartChatId by remember { mutableStateOf<String?>(null) }
    var pendingPlanId by remember { mutableStateOf<String?>(null) }
    var currentPlanId by remember { mutableStateOf<String?>(null) }
    var currentPlanTaskOrder by remember { mutableStateOf<Int?>(null) }
    var lastUserTopic by remember { mutableStateOf<String?>(null) }
    val planOfferDeclinedByChatId = remember { mutableStateMapOf<String, Boolean>() }
    val planOfferShownByChatId = remember { mutableStateMapOf<String, Boolean>() }
    var skipAdvanceInFlight by remember { mutableStateOf(false) }
    var skipAdvanceStartedAt by remember { mutableStateOf<Long?>(null) }
    var activeReminderPlanId by remember { mutableStateOf<String?>(null) }
    val showScrollToBottom by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo.size
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.firstVisibleItemIndex + visible
            total > 0 && lastVisible < total
        }
    }
    val context = LocalContext.current
    var isVoiceInputActive by remember { mutableStateOf(false) }
    var hasRecordAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var activeReminderProgress by remember { mutableStateOf(0) }
    val voiceInputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isVoiceInputActive = false
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val recognized = matches?.firstOrNull().orEmpty()
        if (recognized.isBlank()) {
            errorMessage = "Голос не распознан."
            return@rememberLauncherForActivityResult
        }
        messageText = (messageText.takeIf { it.isNotBlank() }?.let { "$it $recognized" } ?: recognized).trim()
        errorMessage = null
    }

    fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_input_prompt))
        }
        try {
            isVoiceInputActive = true
            voiceInputLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            isVoiceInputActive = false
            errorMessage = "Голосовой ввод недоступен на устройстве."
        }
    }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasRecordAudioPermission = true
            startVoiceRecognition()
        } else {
            hasRecordAudioPermission = false
            errorMessage = "Нужно разрешение на микрофон для голосового ввода."
        }
    }
    var autoScrollRemaining by remember { mutableStateOf(0) }
    var stickToBottom by remember { mutableStateOf(false) }

    fun formatActivityDayKey(millis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return formatter.format(Date(millis))
    }

    fun markUserActivity(uid: String) {
        val now = System.currentTimeMillis()
        val dayKey = formatActivityDayKey(now)
        firestore.collection("users").document(uid)
            .set(
                mapOf(
                    "lastActiveAtMillis" to now,
                    "activityDays" to mapOf(dayKey to true)
                ),
                SetOptions.merge()
            )
    }

    fun setPlanOfferDeclined(uid: String, chatId: String, declined: Boolean) {
        firestore.collection("users").document(uid)
            .collection("chats").document(chatId)
            .set(mapOf("planOfferDeclined" to declined), SetOptions.merge())
    }

    fun setPlanOfferShown(uid: String, chatId: String, shown: Boolean) {
        firestore.collection("users").document(uid)
            .collection("chats").document(chatId)
            .set(mapOf("planOfferShown" to shown), SetOptions.merge())
    }

    fun updateChatPlanProgress(chatId: String, steps: List<PlanStepData>) {
        val completedSteps = steps.count { it.isCompleted && !it.isSkipped }
        val totalSteps = steps.size.coerceAtLeast(1)
        val progressPercent = (completedSteps * 100 / totalSteps).coerceIn(0, 100)
        val planCompleted = progressPercent >= 100
        firestore.collection("users").document(user?.uid ?: return)
            .collection("chats").document(chatId)
            .update(
                mapOf(
                    "trainingPlanProgress" to progressPercent,
                    "trainingPlanCompleted" to planCompleted
                )
            )
    }

    fun resolvePlanTopic(text: String, chatTitle: String?, lastTopic: String?): String {
        return lastTopic?.takeIf { it.isNotBlank() }
            ?: chatTitle?.trim().orEmpty().takeIf { it.isNotBlank() }
            ?: text.trim()
    }

    fun isShortMathExpression(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.length > 20) return false
        if (!normalized.any { it == '+' || it == '-' || it == '*' || it == '/' }) return false
        return normalized.matches(Regex("^[0-9\\s+\\-*/().]+$"))
    }

    fun isExampleRequest(text: String): Boolean {
        val normalized = text.trim().lowercase()
        val tokens = listOf(
            "пример", "примерчик", "еще пример", "ещё пример", "другой пример",
            "покажи пример", "пример пожалуйста", "example", "another example"
        )
        return tokens.any { token ->
            normalized == token || normalized.contains(" $token") || normalized.startsWith("$token ")
        }
    }

    fun isFollowUpRequest(text: String, lastAssistant: String?): Boolean {
        if (lastAssistant.isNullOrBlank()) return false
        return isExampleRequest(text) || isShortMathExpression(text)
    }

    fun isCreatePlanCommand(text: String): Boolean {
        val normalized = text.lowercase()
            .replace(Regex("[^a-zа-я0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        val ru = (normalized.contains("создай") || normalized.contains("сделай") || normalized.contains("составь")) &&
            normalized.contains("план")
        val en = (normalized.contains("create") || normalized.contains("make") || normalized.contains("build")) &&
            normalized.contains("plan")
        return ru || en
    }

    suspend fun fetchPlanDoc(uid: String, planId: String): DocumentSnapshot? =
        suspendCancellableCoroutine { continuation ->
            firestore.collection("users").document(uid)
                .collection("learningPlans")
                .document(planId)
                .get()
                .addOnSuccessListener { doc ->
                    if (continuation.isCancelled) return@addOnSuccessListener
                    continuation.resume(doc, onCancellation = null)
                }
                .addOnFailureListener {
                    if (continuation.isCancelled) return@addOnFailureListener
                    continuation.resume(null, onCancellation = null)
                }
        }

    suspend fun fetchChatDoc(uid: String, chatId: String): DocumentSnapshot? =
        suspendCancellableCoroutine { continuation ->
            firestore.collection("users").document(uid)
                .collection("chats")
                .document(chatId)
                .get()
                .addOnSuccessListener { doc ->
                    if (continuation.isCancelled) return@addOnSuccessListener
                    continuation.resume(doc, onCancellation = null)
                }
                .addOnFailureListener {
                    if (continuation.isCancelled) return@addOnFailureListener
                    continuation.resume(null, onCancellation = null)
                }
        }

    suspend fun sendNextPlanStep(
        uid: String,
        chatId: String,
        planId: String
    ) {
        Log.d(logTag, "sendNextPlanStep: chatId=$chatId planId=$planId")
        val doc = fetchPlanDoc(uid, planId)
        if (doc == null) {
            errorMessage = "Не удалось загрузить план обучения."
            return
        }
        val steps = parsePlanSteps(doc)
        val nextStep = steps.firstOrNull { !it.isCompleted }
        Log.d(logTag, "nextStep: ${nextStep?.order} kind=${nextStep?.kind}")
        if (nextStep == null) {
            val doneMessage = "План обучения завершен. Вы освоили тему."
            firestore.collection("users").document(uid)
                .collection("chats").document(chatId)
                .collection("messages")
                .add(
                    mapOf(
                        "role" to "assistant",
                        "content" to doneMessage,
                        "createdAtMillis" to System.currentTimeMillis()
                    )
                )
            firestore.collection("users").document(uid)
                .collection("chats").document(chatId)
                .update(
                    mapOf(
                        "updatedAtMillis" to System.currentTimeMillis(),
                        "lastMessage" to doneMessage
                    )
                )
            firestore.collection("users").document(uid)
                .collection("learningPlans")
                .document(planId)
                .update(mapOf("activeStepOrder" to null))
            updateChatPlanProgress(chatId, steps)
            return
        }

        firestore.collection("users").document(uid)
            .collection("learningPlans")
            .document(planId)
            .update(mapOf("activeStepOrder" to nextStep.order))

        val stepMessage = sanitizeAssistantReply(
            openRouterClient.generatePlanStepMessage(
            goal = doc.getString("goal").orEmpty(),
            stepTitle = nextStep.title,
            stepKind = nextStep.kind,
            stepDescription = nextStep.description
            )
        )
        firestore.collection("users").document(uid)
            .collection("chats").document(chatId)
            .collection("messages")
            .add(
                mapOf(
                    "role" to "assistant",
                    "content" to stepMessage,
                    "createdAtMillis" to System.currentTimeMillis()
                )
            )
        firestore.collection("users").document(uid)
            .collection("chats").document(chatId)
            .update(
                mapOf(
                    "updatedAtMillis" to System.currentTimeMillis(),
                    "lastMessage" to stepMessage
                )
            )

        val kind = nextStep.kind.lowercase()
        if (kind == "explanation") {
            val updatedSteps = steps.map { step ->
                if (step.order == nextStep.order) {
                    step.copy(isCompleted = true, completedAtMillis = System.currentTimeMillis())
                } else {
                    step
                }
            }
            val updateData = buildPlanUpdateData(updatedSteps)
            firestore.collection("users").document(uid)
                .collection("learningPlans")
                .document(planId)
                .update(updateData)
            updateChatPlanProgress(chatId, updatedSteps)
            // Автоматически переходим к следующему шагу после объяснения.
            sendNextPlanStep(uid = uid, chatId = chatId, planId = planId)
            return
        }

        currentPlanId = planId
        currentPlanTaskOrder = nextStep.order
        onReplyTaskChange(stepMessage)
        return
    }

    DisposableEffect(user?.uid, chatId) {
        if (user?.uid == null || chatId == null) {
            onDispose {}
        } else {
            val registration = firestore.collection("users").document(user.uid)
                .collection("chats").document(chatId)
                .collection("messages")
                .orderBy("createdAtMillis", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        errorMessage = "Не удалось загрузить чат."
                        return@addSnapshotListener
                    }
                    stickToBottom = !showScrollToBottom
                    val items = snapshot?.documents?.map { doc ->
                        ChatMessage(
                            id = doc.id,
                            role = doc.getString("role") ?: "user",
                            content = doc.getString("content") ?: "",
                            createdAtMillis = doc.getLong("createdAtMillis") ?: 0L
                        )
                    } ?: emptyList()
                    messages.clear()
                    messages.addAll(items)
                }
            onDispose { registration.remove() }
        }
    }

    LaunchedEffect(user?.uid, chatId) {
        val uid = user?.uid ?: return@LaunchedEffect
        val activeChatId = chatId ?: return@LaunchedEffect
        firestore.collection("users").document(uid)
            .collection("chats").document(activeChatId)
            .get()
            .addOnSuccessListener { doc ->
                val declined = doc.getBoolean("planOfferDeclined") ?: false
                planOfferDeclinedByChatId[activeChatId] = declined
                val shown = doc.getBoolean("planOfferShown") ?: false
                planOfferShownByChatId[activeChatId] = shown
            }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && stickToBottom) {
            listState.animateScrollToItem(messages.size - 1)
            stickToBottom = false
        } else if (messages.isNotEmpty() && autoScrollRemaining > 0) {
            listState.animateScrollToItem(messages.size - 1)
            autoScrollRemaining = (autoScrollRemaining - 1).coerceAtLeast(0)
        } else if (messages.isNotEmpty() && !showScrollToBottom) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(activeReminderMessageId, messages.size, scrollToReminderMessage) {
        if (!scrollToReminderMessage) return@LaunchedEffect
        if (activeReminderMessageId.isNullOrBlank()) return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == activeReminderMessageId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            onReminderScrollConsumed()
        }
    }

    LaunchedEffect(activeReminderId) {
        if (activeReminderId.isNullOrBlank() || user == null) {
            activeReminderProgress = 0
            return@LaunchedEffect
        }
        firestore.collection("users").document(user.uid)
            .collection("deadlines").document(activeReminderId)
            .get()
            .addOnSuccessListener { doc ->
                val progress = doc.getLong("progressPercent")?.toInt()
                val isCompleted = doc.getBoolean("isCompleted") ?: false
                activeReminderProgress = progress ?: if (isCompleted) 100 else 0
                activeReminderPlanId = doc.getString("planId")
            }
    }

    if (user == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text("Нужно войти в аккаунт, чтобы пользоваться чатом.")
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(text = chatTitle ?: "Чат с ИИ", style = MaterialTheme.typography.titleMedium)
            }
            if (chatId == null) {
                item {
                    Text("Выберите чат во вкладке «Чаты» или отправьте первое сообщение, чтобы создать новый чат.")
                }
            }
            if (messages.isEmpty()) {
                item {
                    Text("Пока нет сообщений. Задайте первый вопрос.")
                }
            } else {
                items(messages) { message ->
                    val prefix = if (message.role == "assistant") "Тьютор" else "Вы"
                    MessageBubble(
                        text = "$prefix: ${message.content}",
                        isAssistant = message.role == "assistant",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = null
                    )
                }
            }
            if (isSending) {
                item {
                    Text("Тьютор печатает...", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (showScrollToBottom) {
            FloatingActionButton(
                onClick = {
                    if (messages.isNotEmpty()) {
                        uiScope.launch {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 120.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Вниз")
            }
        }

        Column(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Ваш вопрос") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        disabledContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 96.dp)
                )
                Row(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            val text = messageText.trim()
                            if (text.isEmpty()) {
                                errorMessage = "Введите сообщение."
                                infoMessage = null
                                return@IconButton
                            }
                            if (skipAdvanceInFlight) {
                                val startedAt = skipAdvanceStartedAt
                                val elapsed = if (startedAt == null) 0L else System.currentTimeMillis() - startedAt
                                if (elapsed < 3000L) {
                                    infoMessage = "Следующий шаг уже готовится. Подождите пару секунд."
                                    return@IconButton
                                }
                                if (chatId != null) {
                                    AiRequestTracker.setSending(chatId, true)
                                    val job = AiRequestScope.scope.launch {
                                        try {
                                            val planId = currentPlanId
                                                ?: fetchChatDoc(user.uid, chatId)?.getString("trainingPlanId")
                                            if (!planId.isNullOrBlank()) {
                                                sendNextPlanStep(
                                                    uid = user.uid,
                                                    chatId = chatId,
                                                    planId = planId
                                                )
                                            } else {
                                                errorMessage = "Активный план обучения не найден."
                                            }
                                        } finally {
                                            skipAdvanceInFlight = false
                                            skipAdvanceStartedAt = null
                                            AiRequestTracker.setSending(chatId, false)
                                        }
                                    }
                                    AiRequestTracker.setJob(chatId, job)
                                }
                                return@IconButton
                            }
                            if (isSending) {
                                return@IconButton
                            }
                            if (skipAdvanceInFlight) {
                                return@IconButton
                            }
                            errorMessage = null
                            infoMessage = null
                            messageText = ""
                            stickToBottom = true

                            val lastAssistant = messages.lastOrNull { it.role == "assistant" }?.content
                            val isDeclineOffer = chatId != null &&
                                !lastAssistant.isNullOrBlank() &&
                                isPlanOffer(lastAssistant) &&
                                isNegativeReply(text)
                            if (chatId != null &&
                                !lastAssistant.isNullOrBlank() &&
                                isPlanOffer(lastAssistant) &&
                                planOfferShownByChatId[chatId] != true
                            ) {
                                planOfferShownByChatId[chatId] = true
                                setPlanOfferShown(user.uid, chatId, true)
                            }
                            if (isDeclineOffer && chatId != null) {
                                val uid = user.uid
                                planOfferDeclinedByChatId[chatId] = true
                                setPlanOfferDeclined(uid, chatId, true)
                                planOfferShownByChatId[chatId] = true
                                setPlanOfferShown(uid, chatId, true)
                                pendingPlanTopic = null
                                pendingPlanChatId = null
                                val userData = mapOf(
                                    "role" to "user",
                                    "content" to text,
                                    "createdAtMillis" to System.currentTimeMillis()
                                )
                                val assistantReply = "Хорошо, тогда жду ваши вопросы."
                                val assistantData = mapOf(
                                    "role" to "assistant",
                                    "content" to assistantReply,
                                    "createdAtMillis" to System.currentTimeMillis()
                                )
                                firestore.collection("users").document(uid)
                                    .collection("chats").document(chatId)
                                    .collection("messages")
                                    .add(userData)
                                firestore.collection("users").document(uid)
                                    .collection("chats").document(chatId)
                                    .collection("messages")
                                    .add(assistantData)
                                firestore.collection("users").document(uid)
                                    .collection("chats").document(chatId)
                                    .update(
                                        mapOf(
                                            "updatedAtMillis" to System.currentTimeMillis(),
                                            "lastMessage" to assistantReply
                                        )
                                    )
                                markUserActivity(uid)
                                return@IconButton
                            }

                            val isPlanCommand = chatId != null && isCreatePlanCommand(text)
                            if (isPlanCommand && chatId != null) {
                                planOfferDeclinedByChatId[chatId] = false
                                setPlanOfferDeclined(user.uid, chatId, false)
                                planOfferShownByChatId[chatId] = true
                                setPlanOfferShown(user.uid, chatId, true)
                                val resolvedTopic = lastUserTopic?.takeIf { it.isNotBlank() }
                                    ?: chatTitle?.trim().orEmpty().ifBlank { "Тема чата" }
                                pendingPlanTopic = resolvedTopic
                                pendingPlanChatId = chatId
                            }

                            if (chatId != null && isPlanRequest(text)) {
                                planOfferDeclinedByChatId[chatId] = false
                                setPlanOfferDeclined(user.uid, chatId, false)
                                planOfferShownByChatId[chatId] = true
                                setPlanOfferShown(user.uid, chatId, true)
                                val resolvedTopic = resolvePlanTopic(text, chatTitle, lastUserTopic)
                                if (resolvedTopic.isNotBlank()) {
                                    pendingPlanTopic = resolvedTopic
                                    pendingPlanChatId = chatId
                                }
                            }

                            if (!isPositiveReply(text) &&
                                !isContinueLearningPlan(text) &&
                                !isAnswerRequest(text) &&
                                !isPlanCommand &&
                                text.length >= 4
                            ) {
                                lastUserTopic = text
                            }

                            if (pendingPlanTopic == null &&
                                chatId != null &&
                                isPlanAcceptance(text) &&
                                planOfferDeclinedByChatId[chatId] != true
                            ) {
                                if (!lastAssistant.isNullOrBlank() &&
                                    isPlanOffer(lastAssistant)
                                ) {
                                    val resolvedTopic = lastUserTopic
                                        ?: chatTitle?.trim().orEmpty()
                                    if (resolvedTopic.isNotBlank()) {
                                        pendingPlanTopic = resolvedTopic
                                        pendingPlanChatId = chatId
                                        planOfferDeclinedByChatId[chatId] = false
                                        setPlanOfferDeclined(user.uid, chatId, false)
                                        planOfferShownByChatId[chatId] = true
                                        setPlanOfferShown(user.uid, chatId, true)
                                    }
                                }
                            }

                            if (pendingPlanTopic == null &&
                                chatId != null &&
                                isPlanAcceptance(text) &&
                                planOfferDeclinedByChatId[chatId] != true
                            ) {
                                if (!lastAssistant.isNullOrBlank() &&
                                    isPlanOffer(lastAssistant) &&
                                    !lastUserTopic.isNullOrBlank()
                                ) {
                                    pendingPlanTopic = lastUserTopic
                                    pendingPlanChatId = chatId
                                    planOfferDeclinedByChatId[chatId] = false
                                    setPlanOfferDeclined(user.uid, chatId, false)
                                    planOfferShownByChatId[chatId] = true
                                    setPlanOfferShown(user.uid, chatId, true)
                                }
                            }

                            val taskToEvaluate = replyTaskText
                            if (taskToEvaluate != null && chatId != null) {
                                AiRequestTracker.setSending(chatId, true)
                                autoScrollRemaining = 2
                                stickToBottom = true
                                val job = AiRequestScope.scope.launch {
                                    val uid = user.uid
                                    val userData = mapOf(
                                        "role" to "user",
                                        "content" to text,
                                        "createdAtMillis" to System.currentTimeMillis()
                                    )
                                    firestore.collection("users").document(uid)
                                        .collection("chats").document(chatId)
                                        .collection("messages")
                                        .add(userData)
                                    markUserActivity(uid)
                                    if (isContinueLearningPlan(text) && currentPlanTaskOrder == null) {
                                        val planId = currentPlanId
                                        if (!planId.isNullOrBlank()) {
                                            sendNextPlanStep(
                                                uid = uid,
                                                chatId = chatId,
                                                planId = planId
                                            )
                                        }
                                        AiRequestTracker.setSending(chatId, false)
                                        return@launch
                                    }
                                    if (currentPlanTaskOrder == null) {
                                        val resolvedPlanId = currentPlanId
                                            ?: fetchChatDoc(uid, chatId)?.getString("trainingPlanId")
                                        if (!resolvedPlanId.isNullOrBlank()) {
                                            val planDoc = fetchPlanDoc(uid, resolvedPlanId)
                                            if (planDoc != null) {
                                                val steps = parsePlanSteps(planDoc)
                                                val activeOrder = planDoc.getLong("activeStepOrder")?.toInt()
                                                    ?: steps.firstOrNull { !it.isCompleted && !it.isSkipped }?.order
                                                val activeStep = steps.firstOrNull { it.order == activeOrder }
                                                if (activeStep != null) {
                                                    val taskText = buildString {
                                                        append("Шаг: ").append(activeStep.title)
                                                        activeStep.description?.takeIf { it.isNotBlank() }?.let {
                                                            append(". Описание: ").append(it)
                                                        }
                                                    }
                                                    val answerRequested = isAnswerRequest(text)
                                                    if (answerRequested) {
                                                        val answerText = openRouterClient.generateTaskAnswer(taskText)
                                                        val feedback = "$answerText Шаг пропущен, прогресс не увеличен."
                                                        val assistantData = mapOf(
                                                            "role" to "assistant",
                                                            "content" to feedback,
                                                            "createdAtMillis" to System.currentTimeMillis()
                                                        )
                                                        firestore.collection("users").document(uid)
                                                            .collection("chats").document(chatId)
                                                            .collection("messages")
                                                            .add(assistantData)
                                                        firestore.collection("users").document(uid)
                                                            .collection("chats").document(chatId)
                                                            .update(
                                                                mapOf(
                                                                    "updatedAtMillis" to System.currentTimeMillis(),
                                                                    "lastMessage" to feedback
                                                                )
                                                            )
                                                        val updatedSteps = steps.map { step ->
                                                            if (step.order == activeStep.order) {
                                                                step.copy(
                                                                    isCompleted = true,
                                                                    isSkipped = true,
                                                                    completedAtMillis = System.currentTimeMillis()
                                                                )
                                                            } else {
                                                                step
                                                            }
                                                        }
                                                        val updateData = buildPlanUpdateData(updatedSteps)
                                                        firestore.collection("users").document(uid)
                                                            .collection("learningPlans")
                                                            .document(resolvedPlanId)
                                                            .update(updateData)
                                                        updateChatPlanProgress(chatId, updatedSteps)
                                                        currentPlanTaskOrder = null
                                                        onReplyTaskChange(null)
                                                        sendNextPlanStep(
                                                            uid = uid,
                                                            chatId = chatId,
                                                            planId = resolvedPlanId
                                                        )
                                                        AiRequestTracker.setSending(chatId, false)
                                                        return@launch
                                                    }

                                                    val evaluation = openRouterClient.evaluateAnswer(taskText, text)
                                                    val resolvedProgress = evaluation.progressPercent.coerceIn(0, 100)
                                                    val feedback = evaluation.feedback.ifBlank {
                                                        if (resolvedProgress >= 100) "Да, все верно." else "Есть ошибки. Попробуйте уточнить решение."
                                                    }.let { base ->
                                                        if (resolvedProgress >= 100) "$base Прогресс увеличен." else base
                                                    }
                                                    val assistantData = mapOf(
                                                        "role" to "assistant",
                                                        "content" to feedback,
                                                        "createdAtMillis" to System.currentTimeMillis()
                                                    )
                                                    firestore.collection("users").document(uid)
                                                        .collection("chats").document(chatId)
                                                        .collection("messages")
                                                        .add(assistantData)
                                                    firestore.collection("users").document(uid)
                                                        .collection("chats").document(chatId)
                                                        .update(
                                                            mapOf(
                                                                "updatedAtMillis" to System.currentTimeMillis(),
                                                                "lastMessage" to feedback
                                                            )
                                                        )
                                                    if (resolvedProgress >= 100) {
                                                        val updatedSteps = steps.map { step ->
                                                            if (step.order == activeStep.order) {
                                                                step.copy(
                                                                    isCompleted = true,
                                                                    completedAtMillis = System.currentTimeMillis()
                                                                )
                                                            } else {
                                                                step
                                                            }
                                                        }
                                                        val updateData = buildPlanUpdateData(updatedSteps)
                                                        firestore.collection("users").document(uid)
                                                            .collection("learningPlans")
                                                            .document(resolvedPlanId)
                                                            .update(updateData)
                                                        updateChatPlanProgress(chatId, updatedSteps)
                                                        currentPlanTaskOrder = null
                                                        onReplyTaskChange(null)
                                                        sendNextPlanStep(
                                                            uid = uid,
                                                            chatId = chatId,
                                                            planId = resolvedPlanId
                                                        )
                                                    }
                                                    AiRequestTracker.setSending(chatId, false)
                                                    return@launch
                                                }
                                            }
                                            sendNextPlanStep(
                                                uid = uid,
                                                chatId = chatId,
                                                planId = resolvedPlanId
                                            )
                                            AiRequestTracker.setSending(chatId, false)
                                            return@launch
                                        }
                                        onReplyTaskChange(null)
                                        AiRequestTracker.setSending(chatId, false)
                                        return@launch
                                    }
                                    try {
                                        val answerRequested = isAnswerRequest(text)
                                        val continueRequested = isContinueLearningPlan(text)
                                        val planStepOrder = currentPlanTaskOrder
                                        val resolvedPlanId = currentPlanId
                                            ?: fetchChatDoc(uid, chatId)?.getString("trainingPlanId")

                                        if (answerRequested) {
                                            val answerText = sanitizeAssistantReply(
                                                openRouterClient.generateTaskAnswer(taskToEvaluate)
                                            )
                                            val feedback = "$answerText Шаг пропущен, прогресс не увеличен."
                                            val assistantData = mapOf(
                                                "role" to "assistant",
                                                "content" to feedback,
                                                "createdAtMillis" to System.currentTimeMillis()
                                            )
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(chatId)
                                                .collection("messages")
                                                .add(assistantData)
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(chatId)
                                                .update(
                                                    mapOf(
                                                        "updatedAtMillis" to System.currentTimeMillis(),
                                                        "lastMessage" to feedback
                                                    )
                                                )
                                            if (activeReminderId != null) {
                                                firestore.collection("users").document(uid)
                                                    .collection("deadlines").document(activeReminderId)
                                                    .update(
                                                        mapOf(
                                                            "progressPercent" to 0,
                                                            "isCompleted" to false
                                                        )
                                                    )
                                                activeReminderProgress = 0
                                            }
                                            if (planStepOrder == null || resolvedPlanId.isNullOrBlank()) {
                                                AiRequestTracker.setSending(chatId, false)
                                                return@launch
                                            }
                                            currentPlanTaskOrder = null
                                            onReplyTaskChange(null)
                                            skipAdvanceInFlight = true
                                            skipAdvanceStartedAt = System.currentTimeMillis()
                                            Log.d(logTag, "skipStep: order=$planStepOrder planId=$resolvedPlanId")
                                            firestore.collection("users").document(uid)
                                                .collection("learningPlans")
                                                .document(resolvedPlanId)
                                                .get()
                                                .addOnSuccessListener { doc ->
                                                    val updatedSteps = parsePlanSteps(doc).map { step ->
                                                        if (step.order == planStepOrder) {
                                                            step.copy(
                                                                isCompleted = true,
                                                                isSkipped = true,
                                                                completedAtMillis = System.currentTimeMillis()
                                                            )
                                                        } else {
                                                            step
                                                        }
                                                    }
                                                    val updateData = buildPlanUpdateData(updatedSteps)
                                                    firestore.collection("users").document(uid)
                                                        .collection("learningPlans")
                                                        .document(resolvedPlanId)
                                                        .update(updateData)
                                                        .addOnSuccessListener {
                                                            updateChatPlanProgress(chatId, updatedSteps)
                                                            AiRequestScope.scope.launch {
                                                                AiRequestTracker.setSending(chatId, true)
                                                                sendNextPlanStep(
                                                                    uid = uid,
                                                                    chatId = chatId,
                                                                    planId = resolvedPlanId
                                                                )
                                                                skipAdvanceInFlight = false
                                                                AiRequestTracker.setSending(chatId, false)
                                                            }
                                                        }
                                                }
                                            return@launch
                                        }

                                        val shouldSkipStep = continueRequested
                                        val (progress, feedback) = if (shouldSkipStep) {
                                            0 to "Хорошо, пропускаем шаг и идем дальше."
                                        } else {
                                            val evaluation = openRouterClient.evaluateAnswer(taskToEvaluate, text)
                                            val resolvedProgress = evaluation.progressPercent.coerceIn(0, 100)
                                            val resolvedFeedback = evaluation.feedback.ifBlank {
                                                if (resolvedProgress >= 100) "Да, все верно." else "Есть ошибки. Попробуйте уточнить решение."
                                            }.let { base ->
                                                if (resolvedProgress >= 100) "$base Прогресс увеличен." else base
                                            }
                                            resolvedProgress to resolvedFeedback
                                        }
                                        val shouldReply = feedback.isNotBlank()
                                        if (shouldReply && feedback.isNotBlank()) {
                                            val assistantData = mapOf(
                                                "role" to "assistant",
                                                "content" to feedback,
                                                "createdAtMillis" to System.currentTimeMillis()
                                            )
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(chatId)
                                                .collection("messages")
                                                .add(assistantData)
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(chatId)
                                                .update(
                                                    mapOf(
                                                        "updatedAtMillis" to System.currentTimeMillis(),
                                                        "lastMessage" to feedback
                                                    )
                                                )
                                        }
                                        if (activeReminderId != null) {
                                            firestore.collection("users").document(uid)
                                                .collection("deadlines").document(activeReminderId)
                                                .update(
                                                    mapOf(
                                                        "progressPercent" to progress,
                                                        "isCompleted" to (progress >= 100)
                                                    )
                                                )
                                            activeReminderProgress = progress
                                        }
                                        if (shouldSkipStep) {
                                            currentPlanTaskOrder = null
                                            onReplyTaskChange(null)
                                            skipAdvanceInFlight = true
                                            skipAdvanceStartedAt = System.currentTimeMillis()
                                        }
                                        if (shouldSkipStep && planStepOrder == null && !resolvedPlanId.isNullOrBlank()) {
                                            AiRequestScope.scope.launch {
                                                AiRequestTracker.setSending(chatId, true)
                                                sendNextPlanStep(
                                                    uid = uid,
                                                    chatId = chatId,
                                                    planId = resolvedPlanId
                                                )
                                                skipAdvanceInFlight = false
                                                AiRequestTracker.setSending(chatId, false)
                                            }
                                        } else if (shouldSkipStep && planStepOrder != null && !resolvedPlanId.isNullOrBlank()) {
                                            Log.d(logTag, "skipStep: order=$planStepOrder planId=$resolvedPlanId")
                                            firestore.collection("users").document(uid)
                                                .collection("learningPlans")
                                                .document(resolvedPlanId)
                                                .get()
                                                .addOnSuccessListener { doc ->
                                                    val updatedSteps = parsePlanSteps(doc).map { step ->
                                                        if (step.order == planStepOrder) {
                                                            step.copy(
                                                                isCompleted = true,
                                                                isSkipped = true,
                                                                completedAtMillis = System.currentTimeMillis()
                                                            )
                                                        } else {
                                                            step
                                                        }
                                                    }
                                                    val updateData = buildPlanUpdateData(updatedSteps)
                                                    firestore.collection("users").document(uid)
                                                        .collection("learningPlans")
                                                        .document(resolvedPlanId)
                                                        .update(updateData)
                                                        .addOnSuccessListener {
                                                            updateChatPlanProgress(chatId, updatedSteps)
                                                            AiRequestScope.scope.launch {
                                                                AiRequestTracker.setSending(chatId, true)
                                                                sendNextPlanStep(
                                                                    uid = uid,
                                                                    chatId = chatId,
                                                                    planId = resolvedPlanId
                                                                )
                                                                skipAdvanceInFlight = false
                                                                AiRequestTracker.setSending(chatId, false)
                                                            }
                                                        }
                                                }
                                        } else if (shouldSkipStep) {
                                            skipAdvanceInFlight = false
                                        } else if (progress >= 100 && planStepOrder != null && !resolvedPlanId.isNullOrBlank()) {
                                            firestore.collection("users").document(uid)
                                                .collection("learningPlans")
                                                .document(resolvedPlanId)
                                                .get()
                                                .addOnSuccessListener { doc ->
                                                    val updatedSteps = parsePlanSteps(doc).map { step ->
                                                        if (step.order == planStepOrder) {
                                                            step.copy(isCompleted = true, completedAtMillis = System.currentTimeMillis())
                                                        } else {
                                                            step
                                                        }
                                                    }
                                                    val updateData = buildPlanUpdateData(updatedSteps)
                                                    firestore.collection("users").document(uid)
                                                        .collection("learningPlans")
                                                        .document(resolvedPlanId)
                                                        .update(updateData)
                                                    val completedSteps = updatedSteps.count { it.isCompleted && !it.isSkipped }
                                                    val totalSteps = updatedSteps.size.coerceAtLeast(1)
                                                    val progressPercent = (completedSteps * 100 / totalSteps).coerceIn(0, 100)
                                                    val planCompleted = progressPercent >= 100
                                                    firestore.collection("users").document(uid)
                                                        .collection("chats").document(chatId)
                                                        .update(
                                                            mapOf(
                                                                "trainingPlanProgress" to progressPercent,
                                                                "trainingPlanCompleted" to planCompleted
                                                            )
                                                        )

                                                    val remainingTasks = updatedSteps.count {
                                                        val kind = it.kind.lowercase()
                                                        !it.isCompleted && (kind == "task" || kind == "test" || kind == "final_exam")
                                                    }
                                                    if (remainingTasks == 0) {
                                                        val doneMessage = "План обучения завершен. Вы освоили тему."
                                                        firestore.collection("users").document(uid)
                                                            .collection("chats").document(chatId)
                                                            .collection("messages")
                                                            .add(
                                                                mapOf(
                                                                    "role" to "assistant",
                                                                    "content" to doneMessage,
                                                                    "createdAtMillis" to System.currentTimeMillis()
                                                                )
                                                            )
                                                        firestore.collection("users").document(uid)
                                                            .collection("chats").document(chatId)
                                                            .update(
                                                                mapOf(
                                                                    "updatedAtMillis" to System.currentTimeMillis(),
                                                                    "lastMessage" to doneMessage
                                                                )
                                                            )
                                                    } else {
                                                        AiRequestScope.scope.launch {
                                                            sendNextPlanStep(
                                                                uid = uid,
                                                                chatId = chatId,
                                                                planId = resolvedPlanId
                                                            )
                                                        }
                                                    }
                                                    currentPlanTaskOrder = null
                                                    onReplyTaskChange(null)
                                                }
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "Ошибка при проверке ответа."
                                    } finally {
                                        AiRequestTracker.setSending(chatId, false)
                                    }
                                }
                                AiRequestTracker.setJob(chatId, job)
                                return@IconButton
                            }

                            if (pendingPlanTopic != null &&
                                pendingPlanChatId == chatId &&
                                (isPlanAcceptance(text) || isPlanCommand)
                            ) {
                                val activeChatId = chatId ?: return@IconButton
                                autoScrollRemaining = 2
                                AiRequestTracker.setSending(activeChatId, true)
                                stickToBottom = true
                                val planTopic = pendingPlanTopic.orEmpty()
                                pendingPlanTopic = null
                                pendingPlanChatId = null
                                val job = AiRequestScope.scope.launch {
                                    val uid = user.uid
                                    val userData = mapOf(
                                        "role" to "user",
                                        "content" to text,
                                        "createdAtMillis" to System.currentTimeMillis()
                                    )
                                    firestore.collection("users").document(uid)
                                        .collection("chats").document(activeChatId)
                                        .collection("messages")
                                        .add(userData)
                                    markUserActivity(uid)
                                    try {
                                        val chatDoc = fetchChatDoc(uid, activeChatId)
                                        val chatTitle = chatDoc?.getString("title")?.trim().orEmpty()
                                        val resolvedTopic = chatTitle.ifBlank { planTopic }
                                        val plan = openRouterClient.generateLearningPath(resolvedTopic) { call ->
                                            AiRequestTracker.setCall(activeChatId, call)
                                        }
                                        if (plan == null || plan.goal.isBlank() || plan.steps.isEmpty()) {
                                            val assistantData = mapOf(
                                                "role" to "assistant",
                                                "content" to "Не удалось создать план обучения. Попробуйте уточнить тему.",
                                                "createdAtMillis" to System.currentTimeMillis()
                                            )
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(activeChatId)
                                                .collection("messages")
                                                .add(assistantData)
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(activeChatId)
                                                .update(
                                                    mapOf(
                                                        "updatedAtMillis" to System.currentTimeMillis(),
                                                        "lastMessage" to "Не удалось создать план обучения. Попробуйте уточнить тему."
                                                    )
                                                )
                                        } else {
                                        val planDoc = firestore.collection("users").document(uid)
                                            .collection("learningPlans")
                                            .document()
                                        val planId = planDoc.id
                                        val planData = mapOf(
                                                "goal" to plan.goal,
                                                "steps" to plan.steps.map { step ->
                                                    mapOf(
                                                        "title" to step.title,
                                                        "order" to step.order,
                                                        "kind" to step.kind,
                                                        "description" to step.description,
                                                        "isCompleted" to step.isCompleted,
                                                        "isSkipped" to false,
                                                        "completedAtMillis" to step.completedAtMillis
                                                    )
                                                },
                                                "activeStepOrder" to null,
                                                "createdAtMillis" to plan.createdAtMillis,
                                                "updatedAtMillis" to System.currentTimeMillis(),
                                                "sourceChatId" to activeChatId,
                                                "progressPercent" to 0,
                                                "isCompleted" to false,
                                                "completedAtMillis" to null
                                            )
                                        planDoc.set(planData)
                                            val reminderTitle = openRouterClient.generateReminderTitle(plan.goal)
                                                .ifBlank { plan.goal.take(60) }
                                            val reminderData = mapOf(
                                                "title" to reminderTitle,
                                                "chatId" to activeChatId,
                                                "planId" to planId,
                                                "sourceMessageId" to "",
                                                "dueAtMillis" to System.currentTimeMillis() + 3L * 24L * 60L * 60L * 1000L,
                                                "progressPercent" to 0,
                                                "isCompleted" to false,
                                                "createdAtMillis" to System.currentTimeMillis()
                                            )
                                            firestore.collection("users").document(uid)
                                                .collection("deadlines")
                                                .add(reminderData)
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(activeChatId)
                                                .update(
                                                    mapOf(
                                                        "trainingPlanActive" to true,
                                                        "trainingPlanGoal" to plan.goal,
                                                        "trainingPlanProgress" to 0,
                                                    "trainingPlanCompleted" to false,
                                                    "trainingPlanId" to planId
                                                    )
                                                )
                                            val stepsText = plan.steps
                                                .sortedBy { it.order }
                                                .joinToString("\n") { step ->
                                                    val label = when (step.kind.lowercase()) {
                                                        "explanation" -> "Объяснение"
                                                        "task" -> "Задание"
                                                        "test" -> "Тест"
                                                        "final_exam" -> "Итоговый экзамен"
                                                        else -> "Шаг"
                                                    }
                                                    "${step.order + 1}. $label: ${step.title}"
                                                }
                                            val planNotice = "План обучения готов:\n$stepsText\n\nНачнем сейчас? Напишите «да»."
                                            val planMessage = mapOf(
                                                "role" to "assistant",
                                                "content" to planNotice,
                                                "createdAtMillis" to System.currentTimeMillis()
                                            )
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(activeChatId)
                                                .collection("messages")
                                                .add(planMessage)
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(activeChatId)
                                                .update(
                                                    mapOf(
                                                        "updatedAtMillis" to System.currentTimeMillis(),
                                                        "lastMessage" to planNotice
                                                    )
                                                )
                                        pendingPlanStartChatId = activeChatId
                                        pendingPlanId = planId
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "Ошибка при создании плана обучения."
                                    } finally {
                                        AiRequestTracker.setSending(activeChatId, false)
                                    }
                                }
                                AiRequestTracker.setJob(activeChatId, job)
                                return@IconButton
                            }
                            val shouldStartPlanNow = (pendingPlanStartChatId == chatId && isPlanAcceptance(text)) ||
                                isContinueLearningPlan(text)
                            if (chatId != null && shouldStartPlanNow) {
                                val activeChatId = chatId
                                autoScrollRemaining = 2
                                AiRequestTracker.setSending(activeChatId, true)
                                stickToBottom = true
                                val job = AiRequestScope.scope.launch {
                                    val uid = user.uid
                                    val userData = mapOf(
                                        "role" to "user",
                                        "content" to text,
                                        "createdAtMillis" to System.currentTimeMillis()
                                    )
                                    firestore.collection("users").document(uid)
                                        .collection("chats").document(activeChatId)
                                        .collection("messages")
                                        .add(userData)
                                    markUserActivity(uid)
                                    suspend fun startPlanWithId(resolvedPlanId: String) {
                                        pendingPlanId = resolvedPlanId
                                        sendNextPlanStep(
                                            uid = uid,
                                            chatId = activeChatId,
                                            planId = resolvedPlanId
                                        )
                                        AiRequestTracker.setSending(activeChatId, false)
                                    }

                                    val planId = pendingPlanId
                                    if (!planId.isNullOrBlank()) {
                                        startPlanWithId(planId)
                                        return@launch
                                    }
                                    firestore.collection("users").document(uid)
                                        .collection("chats").document(activeChatId)
                                        .get()
                                        .addOnSuccessListener { chatDoc ->
                                            val resolvedPlanId = chatDoc.getString("trainingPlanId")
                                            if (resolvedPlanId.isNullOrBlank()) {
                                                errorMessage = "Активный план обучения не найден."
                                                AiRequestTracker.setSending(activeChatId, false)
                                            } else {
                                                AiRequestScope.scope.launch {
                                                    startPlanWithId(resolvedPlanId)
                                                }
                                            }
                                        }
                                        .addOnFailureListener {
                                            errorMessage = "Активный план обучения не найден."
                                            AiRequestTracker.setSending(activeChatId, false)
                                        }
                                }
                                AiRequestTracker.setJob(activeChatId, job)
                                pendingPlanStartChatId = null
                                return@IconButton
                            }
                            if (pendingPlanTopic != null && pendingPlanChatId == chatId) {
                                pendingPlanTopic = null
                                pendingPlanChatId = null
                            }
                            autoScrollRemaining = 2
                            val job = AiRequestScope.scope.launch {
                                val uid = user.uid
                                var shouldAutoTitle = false
                                var latestReply: String? = null
                                val resolvedChatId = chatId ?: run {
                                    val title = text.take(40)
                                    val chatData = mapOf(
                                        "title" to title,
                                        "createdAtMillis" to System.currentTimeMillis(),
                                        "updatedAtMillis" to System.currentTimeMillis()
                                    )
                                    val docRef = firestore.collection("users").document(uid)
                                        .collection("chats")
                                        .document()
                                    val newChatId = docRef.id
                                    docRef.set(chatData).addOnFailureListener {
                                        errorMessage = "Не удалось создать чат."
                                    }
                                    onChatCreated(newChatId, title)
                                    shouldAutoTitle = true
                                    newChatId
                                }
                                AiRequestTracker.setSending(resolvedChatId, true)
                                AiRequestTracker.setJob(resolvedChatId, currentCoroutineContext().job)

                                val userData = mapOf(
                                    "role" to "user",
                                    "content" to text,
                                    "createdAtMillis" to System.currentTimeMillis()
                                )
                                firestore.collection("users").document(uid)
                                    .collection("chats").document(resolvedChatId)
                                    .collection("messages")
                                    .add(userData)
                                    .addOnFailureListener {
                                        errorMessage = "Не удалось отправить сообщение."
                                    }
                                markUserActivity(uid)
                                firestore.collection("users").document(uid)
                                    .collection("chats").document(resolvedChatId)
                                    .update(
                                        mapOf(
                                            "updatedAtMillis" to System.currentTimeMillis(),
                                            "lastMessage" to text
                                        )
                                    )

                                val chatDoc = fetchChatDoc(uid, resolvedChatId)
                                val planActive = chatDoc?.getBoolean("trainingPlanActive") ?: false
                                val planCompleted = chatDoc?.getBoolean("trainingPlanCompleted") ?: false
                                val activePlanId = chatDoc?.getString("trainingPlanId")
                                if (planActive && !planCompleted && !activePlanId.isNullOrBlank()) {
                                    val planDoc = fetchPlanDoc(uid, activePlanId)
                                    if (planDoc == null) {
                                        AiRequestTracker.setSending(resolvedChatId, false)
                                        return@launch
                                    }
                                    val steps = parsePlanSteps(planDoc)
                                    val activeOrder = planDoc.getLong("activeStepOrder")?.toInt()
                                        ?: steps.firstOrNull { !it.isCompleted && !it.isSkipped }?.order
                                    val activeStep = steps.firstOrNull { it.order == activeOrder }
                                    if (activeStep == null) {
                                        sendNextPlanStep(uid = uid, chatId = resolvedChatId, planId = activePlanId)
                                        AiRequestTracker.setSending(resolvedChatId, false)
                                        return@launch
                                    }

                                    val kind = activeStep.kind.lowercase()
                                    if (kind == "explanation") {
                                        sendNextPlanStep(uid = uid, chatId = resolvedChatId, planId = activePlanId)
                                        AiRequestTracker.setSending(resolvedChatId, false)
                                        return@launch
                                    }

                                    val taskText = buildString {
                                        append("Шаг: ").append(activeStep.title)
                                        activeStep.description?.takeIf { it.isNotBlank() }?.let {
                                            append(". Описание: ").append(it)
                                        }
                                    }
                                    val answerRequested = isAnswerRequest(text)
                                    if (answerRequested) {
                                        val answerText = sanitizeAssistantReply(
                                            openRouterClient.generateTaskAnswer(taskText)
                                        )
                                        val feedback = "$answerText Шаг пропущен, прогресс не увеличен."
                                        val assistantData = mapOf(
                                            "role" to "assistant",
                                            "content" to feedback,
                                            "createdAtMillis" to System.currentTimeMillis()
                                        )
                                        firestore.collection("users").document(uid)
                                            .collection("chats").document(resolvedChatId)
                                            .collection("messages")
                                            .add(assistantData)
                                        firestore.collection("users").document(uid)
                                            .collection("chats").document(resolvedChatId)
                                            .update(
                                                mapOf(
                                                    "updatedAtMillis" to System.currentTimeMillis(),
                                                    "lastMessage" to feedback
                                                )
                                            )
                                        val updatedSteps = steps.map { step ->
                                            if (step.order == activeStep.order) {
                                                step.copy(
                                                    isCompleted = true,
                                                    isSkipped = true,
                                                    completedAtMillis = System.currentTimeMillis()
                                                )
                                            } else {
                                                step
                                            }
                                        }
                                        val updateData = buildPlanUpdateData(updatedSteps)
                                        firestore.collection("users").document(uid)
                                            .collection("learningPlans")
                                            .document(activePlanId)
                                            .update(updateData)
                                            .addOnSuccessListener {
                                                updateChatPlanProgress(resolvedChatId, updatedSteps)
                                                currentPlanTaskOrder = null
                                                onReplyTaskChange(null)
                                                AiRequestScope.scope.launch {
                                                    sendNextPlanStep(
                                                        uid = uid,
                                                        chatId = resolvedChatId,
                                                        planId = activePlanId
                                                    )
                                                    AiRequestTracker.setSending(resolvedChatId, false)
                                                }
                                            }
                                        return@launch
                                    }

                                    val continueRequested = isContinueLearningPlan(text)
                                    if (continueRequested) {
                                        val feedback = "Хорошо, пропускаем шаг и идем дальше."
                                        val assistantData = mapOf(
                                            "role" to "assistant",
                                            "content" to feedback,
                                            "createdAtMillis" to System.currentTimeMillis()
                                        )
                                        firestore.collection("users").document(uid)
                                            .collection("chats").document(resolvedChatId)
                                            .collection("messages")
                                            .add(assistantData)
                                        firestore.collection("users").document(uid)
                                            .collection("chats").document(resolvedChatId)
                                            .update(
                                                mapOf(
                                                    "updatedAtMillis" to System.currentTimeMillis(),
                                                    "lastMessage" to feedback
                                                )
                                            )
                                        val updatedSteps = steps.map { step ->
                                            if (step.order == activeStep.order) {
                                                step.copy(
                                                    isCompleted = true,
                                                    isSkipped = true,
                                                    completedAtMillis = System.currentTimeMillis()
                                                )
                                            } else {
                                                step
                                            }
                                        }
                                        val updateData = buildPlanUpdateData(updatedSteps)
                                        firestore.collection("users").document(uid)
                                            .collection("learningPlans")
                                            .document(activePlanId)
                                            .update(updateData)
                                            .addOnSuccessListener {
                                                updateChatPlanProgress(resolvedChatId, updatedSteps)
                                                currentPlanTaskOrder = null
                                                onReplyTaskChange(null)
                                                AiRequestScope.scope.launch {
                                                    sendNextPlanStep(
                                                        uid = uid,
                                                        chatId = resolvedChatId,
                                                        planId = activePlanId
                                                    )
                                                    AiRequestTracker.setSending(resolvedChatId, false)
                                                }
                                            }
                                        return@launch
                                    }

                                    val evaluation = openRouterClient.evaluateAnswer(taskText, text)
                                    val resolvedProgress = evaluation.progressPercent.coerceIn(0, 100)
                                    val feedback = evaluation.feedback.ifBlank {
                                        if (resolvedProgress >= 100) "Да, все верно." else "Есть ошибки. Попробуйте уточнить решение."
                                    }.let { base ->
                                        if (resolvedProgress >= 100) "$base Прогресс увеличен." else base
                                    }
                                    val assistantData = mapOf(
                                        "role" to "assistant",
                                        "content" to feedback,
                                        "createdAtMillis" to System.currentTimeMillis()
                                    )
                                    firestore.collection("users").document(uid)
                                        .collection("chats").document(resolvedChatId)
                                        .collection("messages")
                                        .add(assistantData)
                                    firestore.collection("users").document(uid)
                                        .collection("chats").document(resolvedChatId)
                                        .update(
                                            mapOf(
                                                "updatedAtMillis" to System.currentTimeMillis(),
                                                "lastMessage" to feedback
                                            )
                                        )
                                    if (resolvedProgress >= 100) {
                                        val updatedSteps = steps.map { step ->
                                            if (step.order == activeStep.order) {
                                                step.copy(
                                                    isCompleted = true,
                                                    completedAtMillis = System.currentTimeMillis()
                                                )
                                            } else {
                                                step
                                            }
                                        }
                                        val updateData = buildPlanUpdateData(updatedSteps)
                                        firestore.collection("users").document(uid)
                                            .collection("learningPlans")
                                            .document(activePlanId)
                                            .update(updateData)
                                        updateChatPlanProgress(resolvedChatId, updatedSteps)
                                        currentPlanTaskOrder = null
                                        onReplyTaskChange(null)
                                        sendNextPlanStep(
                                            uid = uid,
                                            chatId = resolvedChatId,
                                            planId = activePlanId
                                        )
                                    }
                                    AiRequestTracker.setSending(resolvedChatId, false)
                                    return@launch
                                }

                                try {
                                    val history = messages.takeLast(12).map {
                                        mapOf("role" to it.role, "content" to it.content)
                                    } + listOf(mapOf("role" to "user", "content" to text))
                                    val followUpMode = isFollowUpRequest(text, messages.lastOrNull { it.role == "assistant" }?.content)
                                    val allowPlanOffer = !followUpMode &&
                                        planOfferDeclinedByChatId[resolvedChatId] != true &&
                                        planOfferShownByChatId[resolvedChatId] != true
                                    val reply = sanitizeAssistantReply(
                                        openRouterClient.generateResponse(
                                        history,
                                        includePlanOffer = allowPlanOffer,
                                        followUpMode = followUpMode
                                        ) { call ->
                                        AiRequestTracker.setCall(resolvedChatId, call)
                                        }
                                    )
                                    latestReply = reply
                                    val assistantData = mapOf(
                                        "role" to "assistant",
                                        "content" to reply,
                                        "createdAtMillis" to System.currentTimeMillis()
                                    )
                                    val assistantMessageRef = firestore.collection("users").document(uid)
                                        .collection("chats").document(resolvedChatId)
                                        .collection("messages")
                                        .document()
                                    assistantMessageRef.set(assistantData)
                                    if (allowPlanOffer) {
                                        planOfferShownByChatId[resolvedChatId] = true
                                        setPlanOfferShown(uid, resolvedChatId, true)
                                    }
                                    firestore.collection("users").document(uid)
                                        .collection("chats").document(resolvedChatId)
                                        .update(
                                            mapOf(
                                                "updatedAtMillis" to System.currentTimeMillis(),
                                                "lastMessage" to reply
                                            )
                                        )
                                    if (shouldAutoTitle && !isPositiveReply(text)) {
                                        val title = openRouterClient.generateChatTitle(text, reply)
                                        if (title.isNotBlank()) {
                                            firestore.collection("users").document(uid)
                                                .collection("chats").document(resolvedChatId)
                                                .update(
                                                    mapOf(
                                                        "title" to title,
                                                        "updatedAtMillis" to System.currentTimeMillis()
                                                    )
                                                )
                                            onChatCreated(resolvedChatId, title)
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    infoMessage = "Запрос остановлен пользователем."
                                    throw e
                                } catch (e: Exception) {
                                    if (AiRequestTracker.isSending(resolvedChatId)) {
                                        errorMessage = "AI request failed."
                                    }
                                } finally {
                                    AiRequestTracker.setSending(resolvedChatId, false)
                                }

                                if (latestReply != null && isPlanOffer(latestReply!!)) {
                                    if (planOfferDeclinedByChatId[resolvedChatId] != true) {
                                        val normalizedTopic = text.trim()
                                        if (!isPositiveReply(normalizedTopic) && normalizedTopic.length >= 4) {
                                            pendingPlanTopic = normalizedTopic
                                            pendingPlanChatId = resolvedChatId
                                        }
                                    }
                                }
                            }
                            if (chatId != null) {
                                AiRequestTracker.setJob(chatId, job)
                                AiRequestTracker.setSending(chatId, true)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Отправить")
                    }
                    if (isSending) {
                        IconButton(
                            onClick = {
                                AiRequestTracker.cancel(chatId)
                                errorMessage = null
                                infoMessage = "Запрос остановлен пользователем."
                            }
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Остановить")
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (isVoiceInputActive) return@IconButton
                                if (hasRecordAudioPermission) {
                                    startVoiceRecognition()
                                } else {
                                    recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = !isVoiceInputActive
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод")
                        }
                    }
                }
            }

            if (isVoiceInputActive) {
                Text(
                    text = "Голосовой ввод активен. Говорите.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (infoMessage != null) {
                Text(
                    text = infoMessage ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (errorMessage != null) {
                Text(text = errorMessage ?: "", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun isPositiveReply(text: String): Boolean {
    val normalized = text.trim().lowercase()
    if (normalized.isBlank()) return false
    val yesTokens = listOf(
        "да", "ага", "ок", "окей", "хорошо", "конечно", "ладно", "давай",
        "хочу", "начнем", "начинаем", "продолжим", "продолжаем",
        "yes", "ok", "okay", "sure", "yep", "yeah", "let's", "lets",
        "continue", "go on", "start"
    )
    return yesTokens.any { token ->
        normalized == token || normalized.contains(" $token") || normalized.startsWith("$token ")
    }
}

private fun isNegativeReply(text: String): Boolean {
    val normalized = text.trim().lowercase()
    if (normalized.isBlank()) return false
    val noTokens = listOf(
        "нет", "неа", "не хочу", "не надо", "не нужно", "не сейчас",
        "позже", "отмена", "stop", "no", "nope", "not now"
    )
    return noTokens.any { token ->
        normalized == token || normalized.contains(" $token") || normalized.startsWith("$token ")
    }
}

private fun isPlanRequest(text: String): Boolean {
    val normalized = text.lowercase()
        .replace(Regex("[^a-zа-я0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isBlank()) return false
    val ruPlan = normalized.contains("план") && (normalized.contains("обуч") || normalized.contains("занят"))
    val ruIntent = normalized.contains("хочу") || normalized.contains("создай") ||
        normalized.contains("сделай") || normalized.contains("составь") ||
        normalized.contains("нужен") || normalized.contains("мне нужен")
    val enPlan = normalized.contains("training plan") || normalized.contains("learning plan")
    val enIntent = normalized.contains("create") || normalized.contains("make") || normalized.contains("build") ||
        normalized.contains("i want") || normalized.contains("i need")
    return (ruPlan && ruIntent) || (enPlan && enIntent)
}

private fun isPlanAcceptance(text: String): Boolean {
    return isPositiveReply(text) || isPlanRequest(text)
}

private fun isPlanOffer(text: String): Boolean {
    val normalized = text.lowercase()
    val ruPlan = normalized.contains("план обучения") ||
        normalized.contains("учебный план") ||
        normalized.contains("план занятий") ||
        (normalized.contains("план") && normalized.contains("обуч"))
    val ruOffer = normalized.contains("создам") || normalized.contains("составлю") ||
        normalized.contains("сделаю") || normalized.contains("подготовлю")
    val ruProfile = normalized.contains("профил")
    val enPlan = normalized.contains("training plan") || normalized.contains("learning plan") ||
        (normalized.contains("plan") && normalized.contains("learn"))
    val enOffer = normalized.contains("create") || normalized.contains("make") ||
        normalized.contains("build") || normalized.contains("prepare")
    val enProfile = normalized.contains("profile")
    return (ruPlan && (ruProfile || ruOffer)) || (enPlan && (enProfile || enOffer))
}

private fun isContinueLearningPlan(text: String): Boolean {
    val normalized = text.trim().lowercase()
    val ruContinue = normalized.contains("продолж") && normalized.contains("план")
    val enContinue = normalized.contains("continue") && normalized.contains("plan")
    val quickTokens = listOf(
        "дальше", "далее", "поехали дальше", "идем дальше",
        "продолжим", "продолжаем", "давай продолжим",
        "next", "go on", "continue", "let's continue", "lets continue"
    )
    return ruContinue || enContinue || quickTokens.any { token ->
        normalized == token || normalized.contains(" $token") || normalized.startsWith("$token ")
    }
}

private fun isAnswerRequest(text: String): Boolean {
    val normalized = text.lowercase()
        .replace(Regex("[^a-zа-я0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isBlank()) return false
    val tokens = listOf(
        "ответ", "ответь", "решение", "правильный ответ", "покажи ответ",
        "дай ответ", "дай решение", "что ответ", "как ответить",
        "ответ сразу", "дай ответ сразу", "сразу ответ", "ответ сейчас", "ответ немедленно",
        "не знаю", "не знаю ответ", "не знаю ответа", "не знаю как решить",
        "i don't know", "i dont know", "i don't know the answer", "i dont know the answer",
        "need an answer", "need the answer", "i need an answer", "i need the answer",
        "give me an answer", "give me the answer", "give me answer", "give answer",
        "answer", "solution", "answer please", "solution please",
        "give me the answer right away", "give the answer right away", "answer right away",
        "give the answer immediately", "give me the answer immediately", "answer immediately",
        "answer now", "right now"
    )
    if (tokens.any { token ->
        normalized == token || normalized.contains(" $token") || normalized.startsWith("$token ")
    }) {
        return true
    }
    val hasAnswerWord = normalized.contains("ответ") || normalized.contains("answer")
    val hasAskVerb = normalized.contains("дай") || normalized.contains("покажи") ||
        normalized.contains("скажи") || normalized.contains("give") ||
        normalized.contains("show") || normalized.contains("provide")
    val hasNeedVerb = normalized.contains("нужен") || normalized.contains("нужно") ||
        normalized.contains("need") || normalized.contains("require")
    val hasDontKnow = normalized.contains("не знаю") || normalized.contains("dont know") || normalized.contains("don't know")
    return hasAnswerWord && (hasAskVerb || hasNeedVerb || hasDontKnow)
}

private fun sanitizeAssistantReply(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return "Ответ не получился. Попробуйте переформулировать."
    val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.size < 24) return trimmed
    val lower = tokens.map { it.lowercase() }
    val counts = lower.groupingBy { it }.eachCount()
    val topEntry = counts.maxByOrNull { it.value }
    val topTokenCount = topEntry?.value ?: 0
    val topTokenRatio = topTokenCount.toDouble() / tokens.size.toDouble()
    var maxRun = 1
    var currentRun = 1
    for (i in 1 until lower.size) {
        if (lower[i] == lower[i - 1]) {
            currentRun += 1
            if (currentRun > maxRun) maxRun = currentRun
        } else {
            currentRun = 1
        }
    }
    val looksLooped = topTokenRatio >= 0.45 || maxRun >= 8
    return if (looksLooped) {
        "Похоже, ответ получился некорректным. Переформулируйте вопрос, я отвечу иначе."
    } else {
        trimmed
    }
}

private data class PlanStepData(
    val title: String,
    val order: Int,
    val kind: String,
    val description: String?,
    val isCompleted: Boolean,
    val isSkipped: Boolean,
    val completedAtMillis: Long?
)

private fun parsePlanSteps(doc: DocumentSnapshot): List<PlanStepData> {
    return (doc.get("steps") as? List<*>)?.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val title = map["title"] as? String ?: return@mapNotNull null
        val order = (map["order"] as? Number)?.toInt() ?: 0
        val kind = map["kind"] as? String ?: "step"
        val description = map["description"] as? String
        val isCompleted = map["isCompleted"] as? Boolean ?: false
        val isSkipped = map["isSkipped"] as? Boolean ?: false
        val completedAtMillis = (map["completedAtMillis"] as? Number)?.toLong()
        PlanStepData(
            title = title,
            order = order,
            kind = kind,
            description = description,
            isCompleted = isCompleted,
            isSkipped = isSkipped,
            completedAtMillis = completedAtMillis
        )
    }?.sortedBy { it.order }.orEmpty()
}

private fun buildPlanUpdateData(
    steps: List<PlanStepData>,
    activeStepOrder: Int? = null
): Map<String, Any?> {
    val completedSteps = steps.count { it.isCompleted && !it.isSkipped }
    val totalSteps = steps.size.coerceAtLeast(1)
    val progressPercent = (completedSteps * 100 / totalSteps).coerceIn(0, 100)
    val isCompleted = progressPercent >= 100
    val completedAtMillis = if (isCompleted) System.currentTimeMillis() else null
    val resolvedActiveOrder = activeStepOrder
        ?: steps.firstOrNull { !it.isCompleted && !it.isSkipped }?.order
    return mapOf(
        "steps" to steps.map { step ->
            mapOf(
                "title" to step.title,
                "order" to step.order,
                "kind" to step.kind,
                "description" to step.description,
                "isCompleted" to step.isCompleted,
                "isSkipped" to step.isSkipped,
                "completedAtMillis" to step.completedAtMillis
            )
        },
        "activeStepOrder" to resolvedActiveOrder,
        "progressPercent" to progressPercent,
        "isCompleted" to isCompleted,
        "completedAtMillis" to completedAtMillis,
        "updatedAtMillis" to System.currentTimeMillis()
    )
}

