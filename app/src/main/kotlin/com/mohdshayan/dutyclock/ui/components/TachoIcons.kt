package com.mohdshayan.dutyclock.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.mohdshayan.dutyclock.core.model.Mode

/**
 * The four tachograph pictograms, drawn on a 24dp grid with a 2dp stroke: steering wheel,
 * crossed hammers, the availability box and the bed. Tinted by Icon like any other vector.
 */
object TachoIcons {
    private fun icon(name: String, width: Float = 2f, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    val Drive: ImageVector by lazy {
        icon("drive") {
            moveTo(3f, 12f); arcTo(9f, 9f, 0f, false, true, 21f, 12f); arcTo(9f, 9f, 0f, false, true, 3f, 12f); close()
            moveTo(9.5f, 12.5f); arcTo(2.5f, 2.5f, 0f, false, true, 14.5f, 12.5f); arcTo(2.5f, 2.5f, 0f, false, true, 9.5f, 12.5f); close()
            moveTo(3.4f, 11.2f); lineTo(9.6f, 12f)
            moveTo(20.6f, 11.2f); lineTo(14.4f, 12f)
            moveTo(12f, 15f); lineTo(12f, 20.9f)
        }
    }

    val Work: ImageVector by lazy {
        icon("work") {
            moveTo(5f, 20f); lineTo(15f, 10f)
            moveTo(19f, 20f); lineTo(9f, 10f)
            moveTo(12.5f, 6.5f); lineTo(18.5f, 12.5f)
            moveTo(11.5f, 6.5f); lineTo(5.5f, 12.5f)
        }
    }

    val Available: ImageVector by lazy {
        icon("available") {
            moveTo(4.5f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 19.5f); lineTo(4.5f, 19.5f); close()
            moveTo(4.5f, 19.5f); lineTo(19.5f, 4.5f)
        }
    }

    val Rest: ImageVector by lazy {
        icon("rest") {
            moveTo(3f, 7f); lineTo(3f, 19f)
            moveTo(3f, 16f); lineTo(21f, 16f); lineTo(21f, 19f)
            moveTo(3f, 12.5f); lineTo(21f, 12.5f); lineTo(21f, 16f)
            moveTo(5.5f, 10f); lineTo(9.5f, 10f)
        }
    }

    fun of(mode: Mode): ImageVector = when (mode) {
        Mode.DRIVE -> Drive
        Mode.WORK -> Work
        Mode.AVAILABLE -> Available
        Mode.REST -> Rest
    }
}
