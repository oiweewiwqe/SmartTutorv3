package com.example.smarttutor.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.smarttutor.ui.main.screens.ChatScreen
import com.example.smarttutor.ui.main.screens.ChatsScreen
import com.example.smarttutor.ui.main.screens.ProfileScreen
import com.example.smarttutor.ui.main.screens.RemindersScreen
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay

private data class MainTab(val title: String, val icon: ImageVector)

private val mainTabs = listOf(
    MainTab("Чат", Icons.Default.Chat),
    MainTab("Чаты", Icons.Default.ChatBubble),
    MainTab("Напоминания", Icons.Default.Alarm),
    MainTab("Профиль", Icons.Default.Person)
)

@Composable
fun MainApp(
    modifier: Modifier = Modifier,
    userEmail: String,
    userId: String?,
    userDisplayName: String?,
    userLastSignInMillis: Long?,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onSignOut: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedChatId by remember { mutableStateOf<String?>(null) }
    var selectedChatTitle by remember { mutableStateOf<String?>(null) }
    var selectedReminderId by remember { mutableStateOf<String?>(null) }
    var selectedReminderMessageId by remember { mutableStateOf<String?>(null) }
    var scrollToReminderMessage by remember { mutableStateOf(false) }
    var replyTaskText by remember { mutableStateOf<String?>(null) }
    var replyChatId by remember { mutableStateOf<String?>(null) }
    val firestore = remember { FirebaseFirestore.getInstance() }

    LaunchedEffect(userId) {
        if (userId.isNullOrBlank()) return@LaunchedEffect
        val userDoc = firestore.collection("users").document(userId)
        while (true) {
            userDoc.set(
                mapOf("lastActiveAtMillis" to System.currentTimeMillis()),
                SetOptions.merge()
            )
            delay(60_000)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    mainTabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ChatScreen(
                modifier = Modifier.padding(innerPadding),
                chatId = selectedChatId,
                chatTitle = selectedChatTitle,
                activeReminderId = selectedReminderId,
                activeReminderMessageId = selectedReminderMessageId,
                scrollToReminderMessage = scrollToReminderMessage,
                replyTaskText = if (selectedChatId != null && selectedChatId == replyChatId) replyTaskText else null,
                onReplyTaskChange = { taskText ->
                    replyTaskText = taskText
                    replyChatId = selectedChatId
                },
                onReminderScrollConsumed = { scrollToReminderMessage = false },
                onChatCreated = { id, title ->
                    selectedChatId = id
                    selectedChatTitle = title
                    selectedReminderId = null
                    selectedReminderMessageId = null
                    scrollToReminderMessage = false
                    replyTaskText = null
                    replyChatId = null
                }
            )
            1 -> ChatsScreen(
                modifier = Modifier.padding(innerPadding),
                selectedChatId = selectedChatId,
                onChatSelected = { id, title ->
                    if (id.isBlank()) {
                        selectedChatId = null
                        selectedChatTitle = null
                        selectedReminderId = null
                        selectedReminderMessageId = null
                        replyTaskText = null
                        replyChatId = null
                    } else {
                        selectedChatId = id
                        selectedChatTitle = title
                        selectedReminderId = null
                        selectedReminderMessageId = null
                        if (replyChatId != id) {
                            replyTaskText = null
                            replyChatId = null
                        }
                        selectedTab = 0
                    }
                },
                onChatRenamed = { id, title ->
                    if (selectedChatId == id) {
                        selectedChatTitle = title
                    }
                }
            )
            2 -> RemindersScreen(
                modifier = Modifier.padding(innerPadding),
                onOpenChat = { id, reminderId, messageId ->
                    if (id.isNotBlank()) {
                        selectedChatId = id
                        selectedReminderId = reminderId
                        selectedReminderMessageId = messageId
                        scrollToReminderMessage = true
                        selectedTab = 0
                    }
                }
            )
            3 -> ProfileScreen(
                modifier = Modifier.padding(innerPadding),
                userEmail = userEmail,
                userId = userId,
                userDisplayName = userDisplayName,
                lastSignInMillis = userLastSignInMillis,
                darkTheme = darkTheme,
                onToggleTheme = onToggleTheme,
                onSignOut = onSignOut
            )
        }
    }
}
