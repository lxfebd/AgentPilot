package com.agentpilot.app.ui.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.agentpilot.app.R

/**
 * The product wordmark.
 *
 * It used to be set in Caveat, a handwriting face, which read as a personal
 * project rather than a tool people run agents in. There is no display sans in
 * the font assets (only JetBrains Mono), so the wordmark is set in the system
 * face with tight tracking instead of shipping a typeface for one line.
 */
@Composable
fun AAWordmark(
    modifier: Modifier = Modifier,
    color: Color = LocalAAColors.current.ink,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    textAlign: TextAlign? = null,
) {
    Text(
        text = "AgentPilot",
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        lineHeight = lineHeight,
        letterSpacing = (-0.5).sp,
        textAlign = textAlign,
        maxLines = 1,
    )
}
