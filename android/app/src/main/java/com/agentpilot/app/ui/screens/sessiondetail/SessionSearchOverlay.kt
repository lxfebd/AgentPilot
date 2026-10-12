package com.agentpilot.app.ui.screens.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import com.agentpilot.app.feature.sessiondetail.MessageAuthor
import com.agentpilot.app.feature.sessiondetail.TimelineMessage
import com.agentpilot.app.R
import androidx.compose.ui.res.stringResource
import com.agentpilot.app.ui.designsystem.AAShapes
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.agentpilot.app.ui.designsystem.noRippleClickable
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X

/**
 * 会话内消息搜索（2.0.8）：在已加载的消息中按文本过滤，
 * 点击结果跳转到对应消息。query 为空时展示最近搜索词。
 */
@Composable
internal fun SessionSearchOverlay(
    messages: List<TimelineMessage>,
    darkMode: Boolean,
    history: List<String>,
    onRecordSearch: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
    onJumpToMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    var query by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase()
    val results by remember(messages, normalizedQuery) {
        derivedStateOf {
            if (normalizedQuery.isEmpty()) {
                emptyList()
            } else {
                messages.filter { message ->
                    searchableText(message).contains(normalizedQuery)
                }
            }
        }
    }
    val resultCount by remember(results) { derivedStateOf { results.size } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(colors.subtle)
                    .noRippleClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Lucide.ArrowLeft,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(AAShapes.smallShape)
                    .background(colors.subtle)
                    .border(1.dp, colors.border, AAShapes.smallShape)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Lucide.Search,
                        contentDescription = null,
                        tint = colors.faint,
                        modifier = Modifier.size(16.dp),
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = colors.ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (normalizedQuery.isNotEmpty()) onRecordSearch(normalizedQuery)
                                onJumpToMessage(results.firstOrNull()?.id.orEmpty())
                            },
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.session_search_hint),
                                        color = colors.faint,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .noRippleClickable { query = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Lucide.X,
                                contentDescription = null,
                                tint = colors.faint,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = if (normalizedQuery.isEmpty()) {
                stringResource(R.string.session_search_empty_hint)
            } else {
                stringResource(R.string.session_search_results, resultCount)
            },
            color = colors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )

        if (normalizedQuery.isNotEmpty() && results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.session_search_no_results),
                    color = colors.faint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else if (normalizedQuery.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(results, key = { it.id }) { message ->
                    SearchResultRow(
                        message = message,
                        query = normalizedQuery,
                        darkMode = darkMode,
                        onClick = {
                            onRecordSearch(normalizedQuery)
                            onJumpToMessage(message.id)
                        },
                    )
                }
            }
        } else if (history.isNotEmpty()) {
            SearchHistoryPanel(
                history = history,
                darkMode = darkMode,
                onSelect = { term ->
                    query = term
                },
                onClear = onClearHistory,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SearchHistoryPanel(
    history: List<String>,
    darkMode: Boolean,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalAAColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.session_search_recent),
                color = colors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.common_clear),
                color = if (darkMode) Color(0xFF8B949E) else Color(0xFF777777),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .noRippleClickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        history.forEach { term ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AAShapes.smallShape)
                    .noRippleClickable(onClick = { onSelect(term) })
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Lucide.Search,
                    contentDescription = null,
                    tint = colors.faint,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = term,
                    color = colors.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun searchableText(message: TimelineMessage): String {
    return listOf(
        message.text,
        message.title,
        message.subtitle,
        message.detail,
        message.body,
        message.command,
        message.output,
        message.input,
        message.toolError,
        message.rawContent,
    ).filterNotNull().joinToString(" ").lowercase()
}

@Composable
private fun SearchResultRow(
    message: TimelineMessage,
    query: String,
    darkMode: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    val snippet = buildSearchSnippet(message, query)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(AAShapes.smallShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (message.author) {
                    MessageAuthor.User -> stringResource(R.string.session_search_role_user)
                    MessageAuthor.Agent -> stringResource(R.string.session_search_role_agent)
                    else -> stringResource(R.string.session_search_role_system)
                },
                color = if (darkMode) Color(0xFF8B949E) else Color(0xFF777777),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (message.orderSeq > 0) "#${message.orderSeq}" else "",
                color = colors.faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = snippet,
            color = colors.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 生成带命中高亮的摘要（简单实现：返回命中附近的一段文本）。
 */
private fun buildSearchSnippet(message: TimelineMessage, query: String): String {
    val haystack = searchableText(message)
    val index = haystack.indexOf(query)
    if (index < 0) return message.text.takeIf { it.isNotBlank() } ?: message.title.takeIf { it.isNotBlank() } ?: "(无内容)"
    val start = (index - 60).coerceAtLeast(0)
    val end = (index + query.length + 120).coerceAtMost(haystack.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < haystack.length) "…" else ""
    return prefix + haystack.substring(start, end) + suffix
}
