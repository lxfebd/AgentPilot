package com.agentpilot.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow
import com.agentpilot.app.R
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search

/**
 * 全局会话搜索浮层：输入关键字按标题/项目过滤所有已知会话，点击直达会话详情。
 * 手机和平板共用。
 */
@OptIn(FlowPreview::class)
@Composable
internal fun HomeSearchOverlay(
    sessions: List<AgentSession>,
    projectNamesById: Map<String, String>,
    onDismiss: () -> Unit,
    onOpenSession: (AgentSession) -> Unit,
) {
    val colors = LocalAAColors.current
    var query by remember { mutableStateOf("") }
    // 打开即聚焦：浮层的目的就是输入关键词。不主动聚焦时焦点可能停在
    // 底层列表上，点击输入框收不到 IME 文本（真机复现：adb 注入与软键盘
    // 均不进字），键盘也不会自动弹出。
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    // Precompute a lowercase haystack per session once per inventory change, then
    // filter off the main thread with a small debounce. A dashboard snapshot
    // replaces the list reference frequently, so filtering inline on every
    // keystroke (and every snapshot) is what made typing stutter.
    val searchIndex by remember(sessions, projectNamesById) {
        mutableStateOf(
            sessions.map { session ->
                session.id to buildString {
                    append(session.title).append('\n')
                    append(projectNamesById[session.projectId].orEmpty()).append('\n')
                    append(session.deviceName)
                }.lowercase()
            }.toMap(),
        )
    }
    val results = remember { mutableStateOf<List<AgentSession>>(emptyList()) }
    LaunchedEffect(sessions, searchIndex) {
        // Read query (a snapshot state) directly so typing retriggers the flow.
        val queryFlow: kotlinx.coroutines.flow.Flow<String> = snapshotFlow { query.trim() }
        queryFlow
            .map { q ->
                if (q.isEmpty()) emptyList()
                else sessions.filter { session -> searchIndex[session.id]?.contains(q.lowercase()) == true }
            }
            .debounce(120L)
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .collectLatest { matches -> results.value = matches }
    }
    val visibleResults = results.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.raisedSurface)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Search,
                        contentDescription = null,
                        tint = colors.muted,
                        modifier = Modifier.size(18.dp),
                    )
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.rail_search_sessions),
                            color = colors.muted,
                            fontSize = 15.sp,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = colors.ink, fontSize = 15.sp),
                            cursorBrush = SolidColor(colors.ink),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = colors.muted,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            if (query.isNotEmpty() && visibleResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.home_search_no_results),
                        color = colors.muted,
                        fontSize = 14.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visibleResults, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onOpenSession(session)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title.ifBlank { session.runtime },
                                    color = colors.ink,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val projectName = projectNamesById[session.projectId]
                                if (!projectName.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = projectName,
                                        color = colors.muted,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            HomeSessionStatusDot(session = session)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSessionStatusDot(session: AgentSession) {
    val colors = LocalAAColors.current
    val status = com.agentpilot.app.feature.sessions.railSessionStatus(session)
    val dotColor = if (status.color == androidx.compose.ui.graphics.Color.Unspecified) colors.muted else status.color
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(dotColor),
    )
}
