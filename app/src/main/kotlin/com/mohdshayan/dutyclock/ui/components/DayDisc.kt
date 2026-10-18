package com.mohdshayan.dutyclock.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohdshayan.dutyclock.core.engine.Piece
import com.mohdshayan.dutyclock.core.model.Mode
import com.mohdshayan.dutyclock.ui.theme.LocalDiscColors
import com.mohdshayan.dutyclock.ui.theme.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.sin

/** Where the hour labels sit, in from the rim: past the rim inset, the driving band and a margin. */
private val LABEL_RADIUS_INSET = 50.dp

/** The inner edge of the driving band, in from the rim. */
private val BAND_INSET = 38.dp

private data class Clear(val halfWidth: Float, val halfHeight: Float, val radius: Float)

/** What the centre of the disc shows: the most urgent counter. */
data class DiscCentre(val key: String, val big: String, val small: String, val label: String, val over: Boolean)

/**
 * The Day Disc: the analogue tachograph chart redrawn as a live instrument. Midnight on top,
 * the day clockwise. Past activity is a band from the rim inward, as thick as the mode is heavy
 * (driving 28dp, other work 18dp, availability 10dp, rest none) in the plate ink; the stretch
 * under way is HiVis, and a 2dp HiVis hand marks now.
 */
@Composable
fun DayDisc(
    pieces: List<Piece>,
    currentMode: Mode?,
    currentFromMin: Int?,
    nowMin: Int,
    centre: DiscCentre?,
    modifier: Modifier = Modifier,
    diameter: Dp = 280.dp,
    /** Shown in the centre when there is no counter yet, wrapped and sized to the inner circle. */
    hint: String? = null,
) {
    val colors = LocalDiscColors.current
    val reduced = LocalReducedMotion.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.ink)
    val bigStyle = MaterialTheme.typography.displayLarge
    val smallStyle = MaterialTheme.typography.headlineMedium.copy(fontSize = 22.sp)
    val hintStyle = MaterialTheme.typography.titleMedium.copy(color = colors.ink, textAlign = TextAlign.Center)
    val density = LocalDensity.current
    // The clear space inside the hour labels, from where they are drawn and how big they are at
    // this font scale: half its width (inside 06 and 18), half its height (inside 00 and 12), and
    // the radius inside the driving band, for the corners of the text.
    val clear = remember(diameter, density, labelStyle) {
        with(density) {
            val lr = diameter.toPx() / 2f - LABEL_RADIUS_INSET.toPx()
            val label = listOf("00", "06", "12", "18").map { measurer.measure(it, labelStyle).size }
            val gap = 4.dp.toPx()
            Clear(
                halfWidth = lr - label.maxOf { it.width } / 2f - gap,
                halfHeight = lr - label.maxOf { it.height } / 2f - gap,
                radius = diameter.toPx() / 2f - BAND_INSET.toPx() - gap,
            )
        }
    }
    // Numerals stay inside the 06 and 18 hour labels.
    val centreWidthPx = minOf(with(density) { (diameter * 0.5f).toPx() }, clear.halfWidth * 2f)

    // The one first-run moment: the hand settles from midnight to now.
    var settled by rememberSaveable { mutableStateOf(false) }
    val hand = remember { Animatable(if (settled || reduced) nowMin.toFloat() else 0f) }
    LaunchedEffect(nowMin) {
        if (!settled && !reduced) {
            hand.animateTo(nowMin.toFloat(), tween(400, easing = FastOutSlowInEasing))
            settled = true
        } else {
            hand.snapTo(nowMin.toFloat())
            settled = true
        }
    }

    val description = buildString {
        append("Day disc. ")
        if (centre != null) append("${centre.label}: ${centre.big}${centre.small}. ")
        if (currentMode != null) append("Now: ${currentMode.label}.")
    }

    Box(modifier.size(diameter).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(colors.plate, r, c)

            val rimInset = 10.dp.toPx()
            fun band(mode: Mode) = when (mode) {
                Mode.DRIVE -> 28.dp.toPx()
                Mode.WORK -> 18.dp.toPx()
                Mode.AVAILABLE -> 10.dp.toPx()
                Mode.REST -> 0f
            }
            fun arc(fromMin: Int, toMin: Int, mode: Mode, live: Boolean) {
                val w = band(mode)
                if (w == 0f || toMin <= fromMin) return
                val rr = r - rimInset - w / 2f
                drawArc(
                    color = if (live) colors.live else colors.ink,
                    startAngle = -90f + fromMin / 4f,
                    sweepAngle = ((toMin - fromMin) / 4f).coerceAtLeast(0.6f),
                    useCenter = false,
                    topLeft = Offset(c.x - rr, c.y - rr),
                    size = Size(rr * 2, rr * 2),
                    style = Stroke(width = w, cap = StrokeCap.Butt),
                )
            }
            for (p in pieces) {
                val live = currentFromMin != null && p.toMin >= nowMin && p.fromMin <= nowMin && p.mode == currentMode
                arc(p.fromMin, p.toMin, p.mode, live)
            }

            // Hour ticks on the rim; the four quarter hours labelled.
            for (hr in 0 until 24) {
                val a = Math.toRadians((hr * 15 - 90).toDouble())
                val outer = r - 2.dp.toPx()
                val inner = r - (if (hr % 6 == 0) 9.dp.toPx() else 6.dp.toPx())
                drawLine(
                    colors.ink,
                    Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()),
                    Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                    strokeWidth = if (hr % 6 == 0) 2.dp.toPx() else 1.dp.toPx(),
                )
                if (hr % 6 == 0) {
                    val text = hr.toString().padStart(2, '0')
                    val lr = r - LABEL_RADIUS_INSET.toPx()
                    val m = measurer.measure(text, labelStyle)
                    drawText(
                        m,
                        topLeft = Offset(
                            c.x + lr * cos(a).toFloat() - m.size.width / 2f,
                            c.y + lr * sin(a).toFloat() - m.size.height / 2f,
                        ),
                    )
                }
            }

            // The hand at now.
            rotate(hand.value / 4f, c) {
                drawLine(colors.live, Offset(c.x, c.y - r + 2.dp.toPx()), Offset(c.x, c.y - r + rimInset + 30.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }

        if (centre == null && hint != null) {
            // Shrink the hint until its wrapped lines fit inside the labels, at any size and font scale.
            val fit = remember(hint, clear, hintStyle) {
                val maxWidth = (clear.halfWidth * 2f).toInt().coerceAtLeast(1)
                var f = 1f
                while (f > 0.5f) {
                    val m = measurer.measure(hint, hintStyle.copy(fontSize = hintStyle.fontSize * f), constraints = Constraints(maxWidth = maxWidth))
                    val hw = (0 until m.lineCount).maxOf { m.getLineRight(it) - m.getLineLeft(it) } / 2f
                    val hh = m.size.height / 2f
                    if (!m.didOverflowWidth && hh <= clear.halfHeight && hw * hw + hh * hh <= clear.radius * clear.radius) break
                    f -= 0.05f
                }
                f
            }
            Text(
                hint,
                style = hintStyle.copy(fontSize = hintStyle.fontSize * fit),
                modifier = Modifier.widthIn(max = with(density) { (clear.halfWidth * 2f).toDp() }),
            )
        }

        if (centre != null) {
            AnimatedContent(
                targetState = centre,
                contentKey = { it.key },
                transitionSpec = {
                    if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                },
                label = "disc-centre",
            ) { cc ->
                // Shrink the numerals until they fit the centre, at whatever font scale the phone uses.
                val scale = remember(cc.big, cc.small, centreWidthPx, bigStyle, smallStyle) {
                    var f = 1f
                    while (f > 0.5f) {
                        val w = measurer.measure(cc.big, bigStyle.copy(fontSize = bigStyle.fontSize * f)).size.width +
                            (if (cc.small.isEmpty()) 0 else measurer.measure(cc.small, smallStyle.copy(fontSize = smallStyle.fontSize * f)).size.width)
                        if (w <= centreWidthPx) break
                        f -= 0.05f
                    }
                    f
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = minOf(diameter * 0.62f, with(density) { (clear.halfWidth * 2f).toDp() }))) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(cc.big, style = bigStyle.copy(fontSize = bigStyle.fontSize * scale), color = colors.ink, maxLines = 1, softWrap = false)
                        if (cc.small.isNotEmpty()) {
                            Text(
                                cc.small,
                                style = smallStyle.copy(fontSize = smallStyle.fontSize * scale),
                                maxLines = 1,
                                softWrap = false,
                                color = colors.ink,
                                modifier = Modifier.padding(bottom = 8.dp, start = 1.dp),
                            )
                        }
                    }
                    Text(
                        (if (cc.over) "Over. " else "") + cc.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.ink,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
