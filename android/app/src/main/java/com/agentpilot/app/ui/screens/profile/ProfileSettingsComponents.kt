package com.agentpilot.app.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpilot.app.R
import com.agentpilot.app.ui.designsystem.AACardGroup
import com.agentpilot.app.ui.designsystem.aaCardGroupSeam
import com.agentpilot.app.ui.designsystem.AAAppearanceMode
import com.agentpilot.app.ui.designsystem.AAShapes
import com.agentpilot.app.ui.designsystem.AADropdownMenu
import com.agentpilot.app.ui.designsystem.AADropdownMenuItem
import com.agentpilot.app.ui.designsystem.AAThemeTone
import com.agentpilot.app.ui.designsystem.AgentPilotColors
import com.agentpilot.app.ui.designsystem.BackIconButton
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.agentpilot.app.ui.designsystem.aaCardGroupSeam
import com.agentpilot.app.ui.designsystem.noRippleClickable
import com.agentpilot.app.ui.designsystem.pressScaleClickable
import com.agentpilot.app.ui.screens.home.HomeSidebarViewMode
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.List as ListIcon
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pipette
import com.composables.icons.lucide.Sun

@Composable
internal fun ProfileHeader(
    title: String? = null,
    onClose: () -> Unit,
) {
    val colors = LocalAAColors.current
    val scheme = MaterialTheme.colorScheme
    // RikkaHub 式页面头部：圆形返回按钮在上（填充面、无描边），大号页面标题左对齐在下。
    Column(modifier = Modifier.fillMaxWidth()) {
        BackIconButton(
            onClick = onClose,
            modifier = Modifier.offset(x = (-8).dp),
            containerColor = if (colors.isDark) scheme.surfaceContainerHighest else colors.canvas,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title ?: stringResource(R.string.profile_settings),
            color = colors.ink,
            fontSize = 36.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 44.sp,
            maxLines = 1,
        )
    }
}

/** 分组小标题：RikkaHub 式组前标签，取动态主色（与 RikkaHub 同源动态取色）。 */
@Composable
internal fun ProfileSectionLabel(text: String) {
    val colors = LocalAAColors.current
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        color = colors.primaryAction,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 18.sp,
    )
}

@Composable
internal fun ProfileCard(content: @Composable ColumnScope.() -> Unit) {
    // 分段卡片（AACardGroup）；深色底取动态 tonal 阶梯的 surfaceContainerHighest，
    // 与 RikkaHub 卡片实测色 (54,57,62) 对齐。
    val colors = LocalAAColors.current
    val scheme = MaterialTheme.colorScheme
    AACardGroup(
        background = if (colors.isDark) MaterialTheme.colorScheme.surfaceContainerHighest else colors.canvas,
        content = content,
    )
}

/**
 * RikkaHub 式取值胶囊：固定宽 150dp、高 40dp、内容左对齐（值文本 + 下箭头），
 * 底色为抬升面叠加主色微调色（实测 RH pill (29,37,45)）。
 */
@Composable
internal fun ProfileValuePill(value: String) {
    val colors = LocalAAColors.current
    val pillBg = lerp(colors.raisedSurface, colors.primaryAction, 0.03f)
    Row(
        modifier = Modifier
            .width(150.dp)
            .height(40.dp)
            .clip(CircleShape)
            .background(pillBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            modifier = Modifier.padding(start = 16.dp),
            color = colors.onRaisedSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = Lucide.ChevronDown,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun ProfileRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    trailingPill: String? = null,
    trailingTag: String? = null,
    trailingIcon: ImageVector? = null,
    trailingAttention: Boolean = false,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    topSeam: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalAAColors.current
    val alpha = if (enabled) 1f else 0.5f
    Column {
        if (topSeam) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(aaCardGroupSeam(colors)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (subtitle == null) 64.dp else 72.dp)
                .then(if (onClick != null) Modifier.pressScaleClickable(enabled = enabled, onClick = onClick) else Modifier)
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingIcon(icon = icon, alpha = alpha)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    color = colors.ink.copy(alpha = alpha),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = colors.muted.copy(alpha = alpha),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    color = colors.muted.copy(alpha = alpha),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailingPill != null) {
                ProfileValuePill(value = trailingPill)
            }
            if (trailingTag != null) {
                Text(
                    text = trailingTag,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.errorSurface.copy(alpha = alpha))
                        .border(1.dp, colors.errorBorder.copy(alpha = alpha), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    color = colors.errorText.copy(alpha = alpha),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 14.sp,
                    maxLines = 1,
                )
            }
            if (trailingIcon != null) {
                Box {
                    Icon(
                        trailingIcon,
                        contentDescription = if (trailingAttention) stringResource(R.string.update_new_version_badge) else null,
                        tint = colors.faint.copy(alpha = alpha),
                        modifier = Modifier.size(20.dp),
                    )
                    if (trailingAttention) {
                        Box(Modifier.align(Alignment.TopEnd).size(5.dp).background(colors.errorText, CircleShape))
                    }
                }
            }
            if (showChevron) {
                Icon(Lucide.ChevronRight, contentDescription = null, tint = colors.faint.copy(alpha = alpha), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun AppearancePopup(
    open: Boolean,
    selectedMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ProfileDropdown(open, onDismiss) {
        AADropdownMenuItem(
            text = stringResource(R.string.profile_follow_system),
            icon = Lucide.Circle,
            selected = selectedMode == AAAppearanceMode.System,
            onClick = { onSelect(AAAppearanceMode.System); onDismiss() },
        )
        AADropdownMenuItem(
            text = stringResource(R.string.profile_light),
            icon = Lucide.Sun,
            selected = selectedMode == AAAppearanceMode.Light,
            onClick = { onSelect(AAAppearanceMode.Light); onDismiss() },
        )
        AADropdownMenuItem(
            text = stringResource(R.string.profile_dark),
            icon = Lucide.Moon,
            selected = selectedMode == AAAppearanceMode.Dark,
            onClick = { onSelect(AAAppearanceMode.Dark); onDismiss() },
        )
    }
}

@Composable
internal fun ThemeTonePopup(
    open: Boolean,
    selectedTone: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ProfileDropdown(open, onDismiss) {
        AADropdownMenuItem(
            text = stringResource(R.string.profile_theme_style_dynamic),
            icon = Lucide.Palette,
            selected = selectedTone == AAThemeTone.Dynamic,
            onClick = { onSelect(AAThemeTone.Dynamic); onDismiss() },
        )
        AADropdownMenuItem(
            text = stringResource(R.string.profile_theme_style_classic),
            icon = Lucide.Monitor,
            selected = selectedTone == AAThemeTone.Classic,
            onClick = { onSelect(AAThemeTone.Classic); onDismiss() },
        )
    }
}

/** RikkaHub 风格设置开关：MD3 Switch，checked 走语义 primary，unchecked 走表面色。 */
@Composable
internal fun AASettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalAAColors.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = colors.runtimeSwitchCheckedTrack,
            checkedThumbColor = colors.runtimeSwitchCheckedThumb,
            uncheckedTrackColor = colors.runtimeSwitchUncheckedTrack,
            uncheckedThumbColor = colors.runtimeSwitchUncheckedThumb,
        ),
    )
}

@Composable
internal fun SidebarViewPopup(
    open: Boolean,
    selectedMode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ProfileDropdown(open, onDismiss) {
        AADropdownMenuItem(
            text = stringResource(R.string.profile_project_view),
            icon = Lucide.Folder,
            selected = selectedMode == HomeSidebarViewMode.Project,
            onClick = { onSelect(HomeSidebarViewMode.Project); onDismiss() },
        )
        AADropdownMenuItem(
            text = stringResource(R.string.profile_session_view),
            icon = Lucide.ListIcon,
            selected = selectedMode == HomeSidebarViewMode.Session,
            onClick = { onSelect(HomeSidebarViewMode.Session); onDismiss() },
        )
    }
}

@Composable
private fun ProfileDropdown(
    open: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.width(1.dp)) {
            AADropdownMenu(expanded = open, onDismissRequest = onDismiss, content = content)
        }
    }
}

@Composable
internal fun SettingIcon(icon: ImageVector, alpha: Float = 1f, tint: Color? = null) {
    val colors = LocalAAColors.current
    val iconTint = tint ?: colors.muted
    // RikkaHub 式列表图标：裸 outline 图标（实测 RH 图标 ink 约 81-95px ≈ 36-40dp），无圆形底。
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = iconTint.copy(alpha = alpha),
        modifier = Modifier.size(36.dp),
    )
}

/**
 * 组内细分隔线。
 *
 * 分段卡片通过形状断点表达分组，本分隔线仅在同组内需要更强行间分隔时保留，
 * 默认左右内缩 12dp 对齐内容区起点。
 */
@Composable
internal fun SignOutCard(onClick: () -> Unit) {
    val colors = LocalAAColors.current
    val shape = AAShapes.mediumShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(profileCardBackground(colors))
            .border(0.8.dp, profileCardBorder(colors), shape)
            .pressScaleClickable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingIcon(icon = Lucide.LogOut, tint = colors.errorText)
        Text(
            text = stringResource(R.string.profile_sign_out),
            modifier = Modifier.weight(1f),
            color = colors.errorText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 20.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AmoledDarkRow(
    amoledDark: Boolean,
    onAmoledDarkChange: (Boolean) -> Unit,
    topSeam: Boolean = false,
) {
    val colors = LocalAAColors.current
    Column {
        if (topSeam) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(aaCardGroupSeam(colors)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingIcon(icon = Lucide.Pipette)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = stringResource(R.string.profile_amoled_dark),
                    color = colors.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.profile_amoled_dark_hint),
                    color = colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AASettingsSwitch(
                checked = amoledDark,
                onCheckedChange = onAmoledDarkChange,
            )
        }
    }
}

@Composable
internal fun NotifyCompletionRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val colors = LocalAAColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingIcon(icon = Lucide.Bell)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = stringResource(R.string.notify_pref_completion_title),
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.notify_pref_completion_hint),
                color = colors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AASettingsSwitch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
}

private fun isProfileDark(colors: AgentPilotColors): Boolean =
    colors.isDark

internal fun profilePageBackground(colors: AgentPilotColors): Color =
    if (isProfileDark(colors)) colors.canvas else colors.raisedSurface

private fun profileCardBackground(colors: AgentPilotColors): Color =
    if (isProfileDark(colors)) colors.raisedSurface else colors.canvas

private fun profileCardBorder(colors: AgentPilotColors): Color =
    if (isProfileDark(colors)) colors.border else Color(0xFFEAE8E2).copy(alpha = 0.72f)
