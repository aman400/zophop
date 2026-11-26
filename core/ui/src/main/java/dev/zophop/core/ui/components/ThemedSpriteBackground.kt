@file:Suppress("MagicNumber")

package dev.zophop.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import dev.zophop.core.ui.LocalAppIsDark
import kotlin.random.Random

private enum class SpriteKind {
    BOLT,
    SMS_BUBBLE,
    NOTIFICATION_BELL,
    CRON_CLOCK,
    WORKFLOW_DAG,
    WEBHOOK_BRACES,
    REGEX_PATTERN,
    JSON_CARD,
    VAULT_SHIELD,
    KEY_TOKEN,
    PLUGIN_MODULE,
    VPN_TUNNEL,
    GPS_PIN,
    TERMINAL_PROMPT,
    SIGNAL_PULSE,
    BATTERY_NODE,
}

private enum class SpriteTone {
    PRIMARY,
    SECONDARY,
    MUTED,
}

private data class SpritePlacement(
    val kind: SpriteKind,
    val xFraction: Float,
    val yFraction: Float,
    val sizeDp: Float,
    val rotationDeg: Float,
    val tone: SpriteTone,
    val alphaFactor: Float,
    val showAccentTick: Boolean,
)

@Composable
fun ZopHopSpriteBackdrop(
    modifier: Modifier = Modifier,
    seedKey: String = "default",
    showDotGrid: Boolean = true,
    spriteAlphaScale: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        ThemedSpriteCanvas(
            modifier = Modifier.fillMaxSize(),
            seedKey = seedKey,
            showDotGrid = showDotGrid,
            spriteAlphaScale = spriteAlphaScale,
        )
        content()
    }
}

@Composable
fun ThemedSpriteCanvas(
    modifier: Modifier = Modifier,
    seedKey: String = "default",
    showDotGrid: Boolean = true,
    spriteAlphaScale: Float = 1f,
) {
    val isDark = LocalAppIsDark.current ?: isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val primaryAlpha = (if (isDark) 0.082f else 0.092f) * spriteAlphaScale
    val secondaryAlpha = (if (isDark) 0.072f else 0.080f) * spriteAlphaScale
    val mutedAlpha = (if (isDark) 0.062f else 0.068f) * spriteAlphaScale
    val dotAlpha = (if (isDark) 0.055f else 0.065f) * spriteAlphaScale.coerceAtMost(1.25f)
    val auraPrimaryAlpha = if (isDark) 0.11f else 0.08f
    val auraSecondaryAlpha = if (isDark) 0.075f else 0.05f

    val placements = remember(seedKey) { buildRandomizedSpriteSheet(seedKey) }
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        drawAmbientAuras(
            w = w,
            h = h,
            primary = primary.copy(alpha = auraPrimaryAlpha),
            secondary = secondary.copy(alpha = auraSecondaryAlpha),
        )

        if (showDotGrid) {
            drawNodeCanvasDots(
                color = primary.copy(alpha = dotAlpha),
                spacingPx = 26.dp.toPx(),
                radiusPx = 1.dp.toPx(),
            )
        }

        placements.forEach { sprite ->
            val baseColor =
                when (sprite.tone) {
                    SpriteTone.PRIMARY -> primary.copy(alpha = primaryAlpha * sprite.alphaFactor)
                    SpriteTone.SECONDARY -> secondary.copy(alpha = secondaryAlpha * sprite.alphaFactor)
                    SpriteTone.MUTED -> muted.copy(alpha = mutedAlpha * sprite.alphaFactor)
                }
            val sizePx = sprite.sizeDp.dp.toPx()
            val cx = w * sprite.xFraction
            val cy = h * sprite.yFraction

            drawSprite(cx, cy, sizePx, sprite.rotationDeg) {
                drawSpriteGlyph(
                    kind = sprite.kind,
                    color = baseColor,
                    dashEffect = dashEffect,
                    showAccentTick = sprite.showAccentTick,
                )
            }
        }
    }
}

private fun buildRandomizedSpriteSheet(seedKey: String): List<SpritePlacement> {
    val rng = Random(seedKey.hashCode().toLong() * 31L + 17L)
    val cols = 3
    val rows = 5

    val cellIndices = (0 until cols * rows).toMutableList().apply { shuffle(rng) }
    val activeCells = cellIndices.take(11)
    val kinds = SpriteKind.entries.toMutableList().apply { shuffle(rng) }
    val tones = listOf(SpriteTone.PRIMARY, SpriteTone.SECONDARY, SpriteTone.PRIMARY, SpriteTone.MUTED)

    return activeCells.mapIndexed { index, cellIndex ->
        val col = cellIndex % cols
        val row = cellIndex / cols

        val cellWidth = 1f / cols
        val cellHeight = 1f / rows
        val jitterX = 0.24f + rng.nextFloat() * 0.52f
        val jitterY = 0.24f + rng.nextFloat() * 0.52f

        val isCenterLane = col == 1
        val sizeDp =
            if (isCenterLane) {
                34f + rng.nextFloat() * 14f
            } else {
                48f + rng.nextFloat() * 24f
            }
        val alphaFactor =
            if (isCenterLane) {
                0.62f + rng.nextFloat() * 0.16f
            } else {
                0.86f + rng.nextFloat() * 0.24f
            }

        SpritePlacement(
            kind = kinds[index % kinds.size],
            xFraction = (col + jitterX) * cellWidth,
            yFraction = (row + jitterY) * cellHeight,
            sizeDp = sizeDp,
            rotationDeg = -16f + rng.nextFloat() * 32f,
            tone = tones[(index + rng.nextInt(tones.size)) % tones.size],
            alphaFactor = alphaFactor,
            showAccentTick = rng.nextFloat() > 0.45f,
        )
    }
}

private fun DrawScope.drawAmbientAuras(
    w: Float,
    h: Float,
    primary: Color,
    secondary: Color,
) {
    val topCenter = Offset(w * 0.16f, h * 0.10f)
    val topRadius = maxOf(w, h) * 0.65f
    drawCircle(
        brush = Brush.radialGradient(colors = listOf(primary, Color.Transparent), center = topCenter, radius = topRadius),
        center = topCenter,
        radius = topRadius,
    )

    val bottomCenter = Offset(w * 0.88f, h * 0.88f)
    val bottomRadius = maxOf(w, h) * 0.55f
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(secondary, Color.Transparent),
                center = bottomCenter,
                radius = bottomRadius,
            ),
        center = bottomCenter,
        radius = bottomRadius,
    )
}

private fun DrawScope.drawNodeCanvasDots(
    color: Color,
    spacingPx: Float,
    radiusPx: Float,
) {
    if (spacingPx <= 0f) return
    val cols = (size.width / spacingPx).toInt() + 1
    val rows = (size.height / spacingPx).toInt() + 1
    val startX = (size.width - (cols - 1) * spacingPx) * 0.5f
    val startY = (size.height - (rows - 1) * spacingPx) * 0.5f
    for (r in 0 until rows) {
        val y = startY + r * spacingPx
        for (c in 0 until cols) {
            val x = startX + c * spacingPx
            drawCircle(color = color, radius = radiusPx, center = Offset(x, y))
        }
    }
}

private inline fun DrawScope.drawSprite(
    centerX: Float,
    centerY: Float,
    sizePx: Float,
    rotationDeg: Float,
    crossinline block: DrawScope.() -> Unit,
) {
    val factor = sizePx / 100f
    withTransform({
        translate(left = centerX - sizePx * 0.5f, top = centerY - sizePx * 0.5f)
        rotate(degrees = rotationDeg, pivot = Offset(sizePx * 0.5f, sizePx * 0.5f))
        scale(scaleX = factor, scaleY = factor, pivot = Offset.Zero)
    }) {
        block()
    }
}

private fun DrawScope.drawSpriteGlyph(
    kind: SpriteKind,
    color: Color,
    dashEffect: PathEffect,
    showAccentTick: Boolean,
) {
    if (showAccentTick) {
        drawMicroBlueprintMarks(color.copy(alpha = color.alpha * 0.7f))
    }

    when (kind) {
        SpriteKind.BOLT -> drawBoltSprite(color)
        SpriteKind.SMS_BUBBLE -> drawSmsBubbleSprite(color)
        SpriteKind.NOTIFICATION_BELL -> drawPulseBellSprite(color, dashEffect)
        SpriteKind.CRON_CLOCK -> drawCronClockSprite(color, dashEffect)
        SpriteKind.WORKFLOW_DAG -> drawWorkflowGraphSprite(color)
        SpriteKind.WEBHOOK_BRACES -> drawWebhookBracesSprite(color)
        SpriteKind.REGEX_PATTERN -> drawRegexPatternSprite(color, dashEffect)
        SpriteKind.JSON_CARD -> drawJsonCardSprite(color)
        SpriteKind.VAULT_SHIELD -> drawVaultShieldSprite(color)
        SpriteKind.KEY_TOKEN -> drawKeyTokenSprite(color)
        SpriteKind.PLUGIN_MODULE -> drawPluginModuleSprite(color, dashEffect)
        SpriteKind.VPN_TUNNEL -> drawVpnTunnelSprite(color, dashEffect)
        SpriteKind.GPS_PIN -> drawGpsPinSprite(color, dashEffect)
        SpriteKind.TERMINAL_PROMPT -> drawTerminalPromptSprite(color)
        SpriteKind.SIGNAL_PULSE -> drawSignalPulseSprite(color)
        SpriteKind.BATTERY_NODE -> drawBatteryNodeSprite(color)
    }
}

private fun DrawScope.drawMicroBlueprintMarks(color: Color) {
    val stroke = 2.2f
    drawLine(color = color, start = Offset(6f, 12f), end = Offset(14f, 12f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(10f, 8f), end = Offset(10f, 16f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawCircle(color = color, radius = 2.4f, center = Offset(90f, 88f), style = Stroke(width = 1.8f))
}

private fun DrawScope.drawBoltSprite(color: Color) {
    val path =
        Path().apply {
            moveTo(64f, 18f)
            lineTo(36f, 52f)
            lineTo(60f, 52f)
            lineTo(32f, 84f)
        }
    drawPath(path, color, style = Stroke(width = 4.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(color = color.copy(alpha = color.alpha * 0.35f), radius = 8f, center = Offset(64f, 18f))
    drawCircle(color = color, radius = 8f, center = Offset(64f, 18f), style = Stroke(width = 2.8f))
    drawCircle(color = color.copy(alpha = color.alpha * 0.35f), radius = 8f, center = Offset(32f, 84f))
    drawCircle(color = color, radius = 8f, center = Offset(32f, 84f), style = Stroke(width = 2.8f))
}

private fun DrawScope.drawSmsBubbleSprite(color: Color) {
    val path =
        Path().apply {
            moveTo(20f, 24f)
            lineTo(80f, 24f)
            quadraticTo(90f, 24f, 90f, 34f)
            lineTo(90f, 64f)
            quadraticTo(90f, 74f, 80f, 74f)
            lineTo(44f, 74f)
            lineTo(28f, 87f)
            lineTo(28f, 74f)
            lineTo(20f, 74f)
            quadraticTo(10f, 74f, 10f, 64f)
            lineTo(10f, 34f)
            quadraticTo(10f, 24f, 20f, 24f)
            close()
        }
    drawPath(path, color.copy(alpha = color.alpha * 0.16f))
    drawPath(path, color, style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(color = color, radius = 3.2f, center = Offset(34f, 49f))
    drawCircle(color = color, radius = 3.2f, center = Offset(50f, 49f))
    drawCircle(color = color, radius = 3.2f, center = Offset(66f, 49f))
}

private fun DrawScope.drawCronClockSprite(
    color: Color,
    dashEffect: PathEffect,
) {
    drawCircle(color = color.copy(alpha = color.alpha * 0.14f), radius = 31f, center = Offset(50f, 54f))
    drawCircle(
        color = color,
        radius = 31f,
        center = Offset(50f, 54f),
        style = Stroke(width = 3.2f, pathEffect = dashEffect, cap = StrokeCap.Round),
    )
    val hands =
        Path().apply {
            moveTo(50f, 34f)
            lineTo(50f, 54f)
            lineTo(64f, 62f)
        }
    drawPath(hands, color, style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(color = color, start = Offset(38f, 15f), end = Offset(62f, 15f), strokeWidth = 3.2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawWorkflowGraphSprite(color: Color) {
    val stroke = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val branchTop =
        Path().apply {
            moveTo(26f, 50f)
            cubicTo(45f, 50f, 45f, 24f, 68f, 24f)
        }
    val branchBottom =
        Path().apply {
            moveTo(26f, 50f)
            cubicTo(45f, 50f, 45f, 76f, 68f, 76f)
        }
    val merge =
        Path().apply {
            moveTo(76f, 24f)
            lineTo(90f, 50f)
            lineTo(76f, 76f)
        }
    drawPath(branchTop, color, style = stroke)
    drawPath(branchBottom, color, style = stroke)
    drawPath(merge, color, style = stroke)

    drawRoundRect(
        color = color.copy(alpha = color.alpha * 0.25f),
        topLeft = Offset(10f, 40f),
        size = Size(20f, 20f),
        cornerRadius = CornerRadius(6f, 6f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(10f, 40f),
        size = Size(20f, 20f),
        cornerRadius = CornerRadius(6f, 6f),
        style = stroke,
    )
    drawCircle(color = color, radius = 8f, center = Offset(72f, 24f), style = stroke)
    drawCircle(color = color, radius = 8f, center = Offset(72f, 76f), style = stroke)
    drawCircle(color = color.copy(alpha = color.alpha * 0.35f), radius = 7f, center = Offset(90f, 50f))
    drawCircle(color = color, radius = 7f, center = Offset(90f, 50f), style = stroke)
}

private fun DrawScope.drawWebhookBracesSprite(color: Color) {
    val stroke = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val leftBrace =
        Path().apply {
            moveTo(35f, 22f)
            cubicTo(25f, 22f, 25f, 28f, 25f, 36f)
            lineTo(25f, 43f)
            cubicTo(25f, 48f, 21f, 50f, 17f, 50f)
            cubicTo(21f, 50f, 25f, 52f, 25f, 57f)
            lineTo(25f, 64f)
            cubicTo(25f, 72f, 25f, 78f, 35f, 78f)
        }
    val rightBrace =
        Path().apply {
            moveTo(65f, 22f)
            cubicTo(75f, 22f, 75f, 28f, 75f, 36f)
            lineTo(75f, 43f)
            cubicTo(75f, 48f, 79f, 50f, 83f, 50f)
            cubicTo(79f, 50f, 75f, 52f, 75f, 57f)
            lineTo(75f, 64f)
            cubicTo(75f, 72f, 75f, 78f, 65f, 78f)
        }
    drawPath(leftBrace, color, style = stroke)
    drawPath(rightBrace, color, style = stroke)
    drawCircle(color = color, radius = 5f, center = Offset(50f, 50f))
    drawArc(
        color = color,
        startAngle = 220f,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = Offset(36f, 36f),
        size = Size(28f, 28f),
        style = Stroke(width = 2.6f, cap = StrokeCap.Round),
    )
    drawArc(
        color = color,
        startAngle = 40f,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = Offset(36f, 36f),
        size = Size(28f, 28f),
        style = Stroke(width = 2.6f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawRegexPatternSprite(
    color: Color,
    dashEffect: PathEffect,
) {
    drawRoundRect(
        color = color.copy(alpha = color.alpha * 0.14f),
        topLeft = Offset(10f, 28f),
        size = Size(80f, 44f),
        cornerRadius = CornerRadius(12f, 12f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(10f, 28f),
        size = Size(80f, 44f),
        cornerRadius = CornerRadius(12f, 12f),
        style = Stroke(width = 2.8f, pathEffect = dashEffect),
    )
    // Slashes /.../
    drawLine(color = color, start = Offset(28f, 62f), end = Offset(36f, 38f), strokeWidth = 3.2f, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(64f, 62f), end = Offset(72f, 38f), strokeWidth = 3.2f, cap = StrokeCap.Round)
    // Asterisk star + dot in center
    drawCircle(color = color, radius = 3.2f, center = Offset(44f, 56f))
    drawLine(color = color, start = Offset(52f, 42f), end = Offset(58f, 52f), strokeWidth = 2.6f, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(58f, 42f), end = Offset(52f, 52f), strokeWidth = 2.6f, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(49f, 47f), end = Offset(61f, 47f), strokeWidth = 2.6f, cap = StrokeCap.Round)
}

private fun DrawScope.drawJsonCardSprite(color: Color) {
    val stroke = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(
        color = color.copy(alpha = color.alpha * 0.14f),
        topLeft = Offset(16f, 18f),
        size = Size(68f, 64f),
        cornerRadius = CornerRadius(12f, 12f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(16f, 18f),
        size = Size(68f, 64f),
        cornerRadius = CornerRadius(12f, 12f),
        style = stroke,
    )
    drawCircle(color = color, radius = 3.5f, center = Offset(30f, 36f))
    drawLine(color = color, start = Offset(40f, 36f), end = Offset(70f, 36f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawCircle(color = color, radius = 3.5f, center = Offset(30f, 50f))
    drawLine(color = color, start = Offset(40f, 50f), end = Offset(62f, 50f), strokeWidth = 3f, cap = StrokeCap.Round)
    drawCircle(color = color, radius = 3.5f, center = Offset(30f, 64f))
    drawLine(color = color, start = Offset(40f, 64f), end = Offset(68f, 64f), strokeWidth = 3f, cap = StrokeCap.Round)
}

private fun DrawScope.drawPulseBellSprite(
    color: Color,
    dashEffect: PathEffect,
) {
    val stroke = Stroke(width = 3.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val bell =
        Path().apply {
            moveTo(50f, 20f)
            cubicTo(36f, 20f, 28f, 30f, 28f, 44f)
            lineTo(28f, 57f)
            lineTo(22f, 67f)
            lineTo(78f, 67f)
            lineTo(72f, 57f)
            lineTo(72f, 44f)
            cubicTo(72f, 30f, 64f, 20f, 50f, 20f)
            close()
        }
    drawPath(bell, color.copy(alpha = color.alpha * 0.16f))
    drawPath(bell, color, style = stroke)
    drawArc(
        color = color,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(42f, 66f),
        size = Size(16f, 14f),
        style = stroke,
    )
    drawArc(
        color = color,
        startAngle = 190f,
        sweepAngle = 42f,
        useCenter = false,
        topLeft = Offset(14f, 16f),
        size = Size(72f, 72f),
        style = Stroke(width = 2.6f, pathEffect = dashEffect, cap = StrokeCap.Round),
    )
    drawArc(
        color = color,
        startAngle = 308f,
        sweepAngle = 42f,
        useCenter = false,
        topLeft = Offset(14f, 16f),
        size = Size(72f, 72f),
        style = Stroke(width = 2.6f, pathEffect = dashEffect, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawVaultShieldSprite(color: Color) {
    val stroke = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val shield =
        Path().apply {
            moveTo(50f, 14f)
            lineTo(82f, 27f)
            lineTo(82f, 50f)
            cubicTo(82f, 70f, 69f, 82f, 50f, 88f)
            cubicTo(31f, 82f, 18f, 70f, 18f, 50f)
            lineTo(18f, 27f)
            close()
        }
    drawPath(shield, color.copy(alpha = color.alpha * 0.16f))
    drawPath(shield, color, style = stroke)
    drawCircle(color = color.copy(alpha = color.alpha * 0.35f), radius = 7f, center = Offset(50f, 46f))
    drawCircle(color = color, radius = 7f, center = Offset(50f, 46f), style = stroke)
    drawLine(color = color, start = Offset(50f, 53f), end = Offset(50f, 66f), strokeWidth = 3.2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawKeyTokenSprite(color: Color) {
    val stroke = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawCircle(color = color.copy(alpha = color.alpha * 0.22f), radius = 15f, center = Offset(32f, 50f))
    drawCircle(color = color, radius = 15f, center = Offset(32f, 50f), style = stroke)
    drawCircle(color = color, radius = 4.5f, center = Offset(32f, 50f))
    drawLine(color = color, start = Offset(47f, 50f), end = Offset(86f, 50f), strokeWidth = 3.2f, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(70f, 50f), end = Offset(70f, 64f), strokeWidth = 3.2f, cap = StrokeCap.Round)
    drawLine(color = color, start = Offset(82f, 50f), end = Offset(82f, 60f), strokeWidth = 3.2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawPluginModuleSprite(
    color: Color,
    dashEffect: PathEffect,
) {
    val stroke = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val puzzle =
        Path().apply {
            moveTo(26f, 26f)
            lineTo(44f, 26f)
            cubicTo(44f, 18f, 58f, 18f, 58f, 26f)
            lineTo(74f, 26f)
            lineTo(74f, 44f)
            cubicTo(82f, 44f, 82f, 58f, 74f, 58f)
            lineTo(74f, 76f)
            lineTo(26f, 76f)
            lineTo(26f, 58f)
            cubicTo(34f, 58f, 34f, 44f, 26f, 44f)
            close()
        }
    drawPath(puzzle, color.copy(alpha = color.alpha * 0.16f))
    drawPath(puzzle, color, style = stroke)
    drawCircle(
        color = color,
        radius = 9f,
        center = Offset(51f, 51f),
        style = Stroke(width = 2.6f, pathEffect = dashEffect, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawVpnTunnelSprite(
    color: Color,
    dashEffect: PathEffect,
) {
    val stroke = Stroke(width = 2.8f, cap = StrokeCap.Round)
    drawCircle(color = color.copy(alpha = color.alpha * 0.12f), radius = 30f, center = Offset(50f, 50f))
    drawCircle(color = color, radius = 30f, center = Offset(50f, 50f), style = stroke)
    drawOval(
        color = color,
        topLeft = Offset(34f, 20f),
        size = Size(32f, 60f),
        style = Stroke(width = 2.4f, pathEffect = dashEffect),
    )
    drawLine(color = color, start = Offset(21f, 44f), end = Offset(79f, 44f), strokeWidth = 2.4f)
    drawLine(color = color, start = Offset(21f, 56f), end = Offset(79f, 56f), strokeWidth = 2.4f)
}

private fun DrawScope.drawGpsPinSprite(
    color: Color,
    dashEffect: PathEffect,
) {
    val stroke = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val pin =
        Path().apply {
            moveTo(50f, 72f)
            cubicTo(30f, 54f, 26f, 42f, 26f, 34f)
            cubicTo(26f, 20f, 37f, 12f, 50f, 12f)
            cubicTo(63f, 12f, 74f, 20f, 74f, 34f)
            cubicTo(74f, 42f, 70f, 54f, 50f, 72f)
            close()
        }
    drawPath(pin, color.copy(alpha = color.alpha * 0.18f))
    drawPath(pin, color, style = stroke)
    drawCircle(color = color, radius = 7f, center = Offset(50f, 34f), style = stroke)
    drawOval(
        color = color,
        topLeft = Offset(24f, 72f),
        size = Size(52f, 14f),
        style = Stroke(width = 2.4f, pathEffect = dashEffect),
    )
}

private fun DrawScope.drawTerminalPromptSprite(color: Color) {
    val stroke = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(
        color = color.copy(alpha = color.alpha * 0.16f),
        topLeft = Offset(14f, 22f),
        size = Size(72f, 56f),
        cornerRadius = CornerRadius(12f, 12f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(14f, 22f),
        size = Size(72f, 56f),
        cornerRadius = CornerRadius(12f, 12f),
        style = stroke,
    )
    val chevron =
        Path().apply {
            moveTo(28f, 42f)
            lineTo(42f, 52f)
            lineTo(28f, 62f)
        }
    drawPath(chevron, color, style = stroke)
    drawLine(color = color, start = Offset(48f, 62f), end = Offset(68f, 62f), strokeWidth = 3.2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawSignalPulseSprite(color: Color) {
    val stroke = Stroke(width = 3.2f, cap = StrokeCap.Round)
    drawCircle(color = color.copy(alpha = color.alpha * 0.35f), radius = 7f, center = Offset(50f, 66f))
    drawCircle(color = color, radius = 7f, center = Offset(50f, 66f), style = stroke)
    drawArc(
        color = color,
        startAngle = 220f,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = Offset(30f, 46f),
        size = Size(40f, 40f),
        style = stroke,
    )
    drawArc(
        color = color,
        startAngle = 220f,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = Offset(16f, 32f),
        size = Size(68f, 68f),
        style = stroke,
    )
}

private fun DrawScope.drawBatteryNodeSprite(color: Color) {
    val stroke = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawRoundRect(
        color = color.copy(alpha = color.alpha * 0.14f),
        topLeft = Offset(28f, 22f),
        size = Size(44f, 64f),
        cornerRadius = CornerRadius(9f, 9f),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(28f, 22f),
        size = Size(44f, 64f),
        cornerRadius = CornerRadius(9f, 9f),
        style = stroke,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(41f, 14f),
        size = Size(18f, 8f),
        cornerRadius = CornerRadius(3f, 3f),
    )
    val miniBolt =
        Path().apply {
            moveTo(54f, 36f)
            lineTo(43f, 54f)
            lineTo(52f, 54f)
            lineTo(46f, 70f)
        }
    drawPath(miniBolt, color, style = stroke)
}
