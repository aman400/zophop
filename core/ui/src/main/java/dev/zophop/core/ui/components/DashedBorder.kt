package dev.zophop.core.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.dashedBorder(
    color: Color,
    strokeWidth: Dp = 2.dp,
    cornerRadius: Dp = 16.dp,
    dashLength: Dp = 8.dp,
    gapLength: Dp = 4.dp,
): Modifier =
    this.drawBehind {
        val strokeWidthPx = strokeWidth.toPx()
        val dashLengthPx = dashLength.toPx()
        val gapLengthPx = gapLength.toPx()
        val cornerRadiusPx = cornerRadius.toPx()

        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLengthPx, gapLengthPx), 0f)

        drawRoundRect(
            color = color,
            style =
                Stroke(
                    width = strokeWidthPx,
                    pathEffect = pathEffect,
                ),
            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
        )
    }
