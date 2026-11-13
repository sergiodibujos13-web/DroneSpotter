package com.example.dronespotter

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private data class Star(
    val x: Float,
    val y: Float,
    val r: Float,
    val phase: Float,
    val speed: Int
)

private data class Ribbon(
    val color: Color,
    val base: Float,
    val amp: Float,
    val thick: Float,
    val freq: Float,
    val phase: Float,
    val dir: Int
)

/** Fondo de noche con aurora boreal en movimiento suave y estrellas que parpadean. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t"
    )

    val stars = remember {
        val rnd = Random(13)
        List(90) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat() * 0.92f,
                r = 0.6f + rnd.nextFloat() * 1.4f,
                phase = rnd.nextFloat() * 6.28f,
                speed = 1 + rnd.nextInt(3)
            )
        }
    }

    val ribbons = remember {
        listOf(
            Ribbon(Color(0xFF8E6CF0), 0.10f, 0.05f, 0.14f, 1.3f, 0.0f, 1),
            Ribbon(Color(0xFF3DDC97), 0.18f, 0.07f, 0.20f, 1.0f, 1.7f, -1),
            Ribbon(Color(0xFF2BB6A3), 0.28f, 0.06f, 0.16f, 1.6f, 3.1f, 1),
            Ribbon(Color(0xFF3DDC97), 0.40f, 0.05f, 0.12f, 1.1f, 4.4f, -1)
        )
    }

    Canvas(modifier) {
        drawRect(Color(0xFF0B1B2E))
        val w = size.width
        val h = size.height
        val twoPi = 2f * PI.toFloat()

        // Estrellas que parpadean despacio
        stars.forEach { s ->
            val a = 0.3f + 0.7f * (0.5f + 0.5f * sin(t * s.speed + s.phase))
            drawCircle(
                color = Color.White.copy(alpha = a),
                radius = s.r * density,
                center = Offset(s.x * w, s.y * h)
            )
        }

        // Cintas de aurora que ondulan
        ribbons.forEach { rb ->
            fun top(x: Float): Float =
                (rb.base + rb.amp * sin(x / w * twoPi * rb.freq + rb.dir * t + rb.phase)) * h

            fun bottom(x: Float): Float =
                top(x) + rb.thick * h * (0.75f + 0.25f * sin(x / w * twoPi * 0.8f + t + rb.phase))

            val path = Path()
            val step = 16f
            path.moveTo(0f, top(0f))
            var x = step
            while (x < w) {
                path.lineTo(x, top(x))
                x += step
            }
            path.lineTo(w, top(w))
            path.lineTo(w, bottom(w))
            x = w - step
            while (x > 0f) {
                path.lineTo(x, bottom(x))
                x -= step
            }
            path.lineTo(0f, bottom(0f))
            path.close()

            val startY = (rb.base - rb.amp) * h
            val endY = (rb.base + rb.amp + rb.thick) * h
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.4f to rb.color.copy(alpha = 0.5f),
                    1f to Color.Transparent,
                    startY = startY,
                    endY = endY
                )
            )
        }
    }
}
