package com.example.smarttutor.ui.main.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText as ComposeMarkdownText

private data class MarkdownBlock(val isCode: Boolean, val content: String)

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    val blocks = parseMarkdownBlocks(text)
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (block.isCode) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = block.content,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            } else {
                val inlineFixed = block.content.replace(Regex("`([^`]+)`"), "[$1]")
                ComposeMarkdownText(
                    markdown = inlineFixed,
                    maxLines = maxLines,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (index != blocks.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val regex = Regex("```[\\s\\S]*?```")
    val blocks = mutableListOf<MarkdownBlock>()
    var lastIndex = 0

    regex.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        if (start > lastIndex) {
            blocks.add(MarkdownBlock(isCode = false, content = text.substring(lastIndex, start)))
        }
        val raw = match.value
        val code = extractCode(raw)
        blocks.add(MarkdownBlock(isCode = true, content = code))
        lastIndex = end
    }

    if (lastIndex < text.length) {
        blocks.add(MarkdownBlock(isCode = false, content = text.substring(lastIndex)))
    }

    return blocks.ifEmpty { listOf(MarkdownBlock(isCode = false, content = text)) }
}

private fun extractCode(raw: String): String {
    val trimmed = raw.removePrefix("```").removeSuffix("```").trim()
    val lines = trimmed.lines()
    return if (lines.isNotEmpty() && lines.first().length <= 12 && lines.first().all { it.isLetterOrDigit() || it == '-' }) {
        lines.drop(1).joinToString("\n")
    } else {
        trimmed
    }
}
