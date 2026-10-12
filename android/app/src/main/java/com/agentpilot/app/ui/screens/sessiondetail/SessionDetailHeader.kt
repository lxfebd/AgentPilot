package com.agentpilot.app.ui.screens.sessiondetail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpilot.app.R
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.agentpilot.app.ui.designsystem.noRippleClickable
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2

@Composable
internal fun SessionDetailHeader(
    title: String,
    darkMode: Boolean,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onExportClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    val surface = if (darkMode) colors.raisedSurface else Color(0xF2FFFFFF)
    val border = if (darkMode) colors.border else Color(0xFFE8E5DE)
    val text = if (darkMode) Color(0xFFFAFAFA) else Color(0xFF2F302D)

    // 布局：左钮贴左、右侧三钮成组贴右，标题在剩余空间内水平居中。
    // 宽屏（平板/桌面）两侧避让对称 → 标题正对屏幕中心；窄屏右侧避让更大。
    val rightGroupWidth = 44.dp * 3 + 10.dp * 2
    val avoidEnd = rightGroupWidth + 12.dp
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700
    val avoidStart = if (wide) avoidEnd else 56.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 18.dp),
    ) {
        HeaderImageButton(
            resId = if (darkMode) {
                R.drawable.ic_session_runtime_settings_dark
            } else {
                R.drawable.ic_session_runtime_settings_light
            },
            darkMode = darkMode,
            onClick = onLeftClick,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = avoidStart, end = avoidEnd)
                .widthIn(max = 420.dp)
                .height(42.dp)
                .shadow(18.dp, CircleShape, ambientColor = Color(0x0A000000), spotColor = Color(0x0A000000))
                .clip(CircleShape)
                .background(surface)
                .border(1.dp, border, CircleShape)
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = text,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderSearchButton(
                darkMode = darkMode,
                onClick = onSearchClick,
            )
            HeaderExportButton(
                darkMode = darkMode,
                onClick = onExportClick,
            )
            HeaderImageButton(
                resId = if (darkMode) {
                    R.drawable.ic_session_agent_button_dark
                } else {
                    R.drawable.ic_session_agent_button_light
                },
                darkMode = darkMode,
                onClick = onRightClick,
            )
        }
    }
}

@Composable
internal fun HeaderVeil(
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val base = if (darkMode) Color.Black else Color(0xFFFDFCFB)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(
                Brush.verticalGradient(
                    // 顶部与 header 同高的一段保持完全不透明，避免滚动的消息文字透出来
                    0f to base,
                    0.62f to base,
                    1f to base.copy(alpha = 0f),
                ),
            ),
    )
}

@Composable
private fun HeaderImageButton(
    resId: Int,
    darkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    val surface = if (darkMode) colors.raisedSurface else Color(0xF2FFFFFF)
    val border = if (darkMode) colors.border else Color(0xFFE8E5DE)
    Box(
        modifier = modifier
            .size(44.dp)
            .shadow(14.dp, CircleShape, ambientColor = Color(0x0A000000), spotColor = Color(0x0A000000))
            .clip(CircleShape)
            .background(surface)
            .border(1.dp, border, CircleShape)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(resId),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun HeaderSearchButton(
    darkMode: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    val surface = if (darkMode) colors.raisedSurface else Color(0xF2FFFFFF)
    val border = if (darkMode) colors.border else Color(0xFFE8E5DE)
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(14.dp, CircleShape, ambientColor = Color(0x0A000000), spotColor = Color(0x0A000000))
            .clip(CircleShape)
            .background(surface)
            .border(1.dp, border, CircleShape)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(
                if (darkMode) R.drawable.ic_session_search_dark else R.drawable.ic_session_search_light
            ),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderExportButton(
    darkMode: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    val surface = if (darkMode) colors.raisedSurface else Color(0xF2FFFFFF)
    val border = if (darkMode) colors.border else Color(0xFFE8E5DE)
    val tint = if (darkMode) Color(0xFFFAFAFA) else Color(0xFF2F302D)
    // 分享图标 + 长按提示：替代原「横线+下箭头」位图（被误读为「跳到底部」）
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(
                    text = stringResource(R.string.session_export_chooser_title),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .shadow(14.dp, CircleShape, ambientColor = Color(0x0A000000), spotColor = Color(0x0A000000))
                .clip(CircleShape)
                .background(surface)
                .border(1.dp, border, CircleShape)
                .noRippleClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.Share2,
                contentDescription = stringResource(R.string.session_export_chooser_title),
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
