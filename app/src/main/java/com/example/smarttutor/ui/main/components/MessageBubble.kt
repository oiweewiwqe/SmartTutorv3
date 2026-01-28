package com.example.smarttutor.ui.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable

@Composable
fun MessageBubble(
    text: String,
    isAssistant: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val background = if (isAssistant) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Row(
        modifier = modifier,
        horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End
    ) {
        Surface(
            color = background,
            shape = RoundedCornerShape(16.dp)
        ) {
            MarkdownText(
                text = text,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 320.dp)
                    .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            )
        }
    }
}
