package com.miruronative.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's loading indicator: a No-Face mask that rocks side to side, lets its eyes wander,
 * and blinks now and then — waiting, like the user. Drawn on a 200x200 viewport scaled to
 * [size]; the backdrop is tinted from the active theme so it blends with the UI.
 */
@Composable
fun NoFaceLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    // Solid signature-purple backdrop, with two progressively darker purples for the split
    // shading — mirroring the source icon's three blues.
    val accent = MaterialTheme.colorScheme.primary
    val halo = accent
    val haloShade = androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.10f)
    val robeShadow = androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.18f)
    val body = Color(0xFF383B4E)
    val mask = Color(0xFFFFFFFF)
    val maskShade = Color(0xFFE2E4F1)
    val marks = Color(0xFF9EA3C0)

    val transition = rememberInfiniteTransition(label = "noFaceLoading")

    // Brisk side-to-side rocking, pivoting near the bottom of the figure.
    val sway by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "sway",
    )

    // Eyes drift left and right on their own rhythm, so the face reads as "looking around".
    val gaze by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "gaze",
    )

    // Mostly open, with one quick blink per cycle. 0 = open, 1 = closed.
    val blink by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3200
                0f at 0
                0f at 2300
                1f at 2460
                0f at 2600
            },
        ),
        label = "blink",
    )

    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "Loading" }
            .graphicsLayer {
                rotationZ = sway * 4f
                translationX = sway * this.size.width * 0.0075f
                transformOrigin = TransformOrigin(0.5f, 0.9f)
            },
    ) {
        val u = this.size.minDimension / 200f
        fun x(value: Float) = (this.size.width - 200f * u) / 2f + value * u
        fun y(value: Float) = (this.size.height - 200f * u) / 2f + value * u
        fun p(build: Path.() -> Unit) = Path().apply(build)

        val circle = p {
            addOval(Rect(x(8f), y(8f), x(192f), y(192f)))
        }
        drawPath(circle, halo)
        clipPath(circle) {
            // Split shading on the backdrop and behind the robe's right side, for depth.
            drawPath(
                p {
                    moveTo(x(100f), y(4f))
                    cubicTo(x(170f), y(10f), x(196f), y(100f), x(188f), y(150f))
                    lineTo(x(192f), y(192f))
                    lineTo(x(100f), y(192f))
                    close()
                },
                haloShade,
            )
            drawPath(
                p {
                    moveTo(x(100f), y(22f))
                    cubicTo(x(154f), y(22f), x(164f), y(110f), x(164f), y(192f))
                    lineTo(x(192f), y(192f))
                    lineTo(x(192f), y(70f))
                    close()
                },
                robeShadow,
            )

            // Robe.
            drawPath(
                p {
                    moveTo(x(36f), y(192f))
                    cubicTo(x(36f), y(110f), x(46f), y(22f), x(100f), y(22f))
                    cubicTo(x(154f), y(22f), x(164f), y(110f), x(164f), y(192f))
                    close()
                },
                body,
            )

            // Mask, with a soft shade on its right half clipped to the mask shape.
            val maskOval = Rect(x(63f), y(34f), x(137f), y(150f))
            drawOval(mask, topLeft = maskOval.topLeft, size = maskOval.size)
            clipPath(p { addOval(maskOval) }) {
                drawPath(
                    p {
                        moveTo(x(100f), y(34f))
                        cubicTo(x(116f), y(48f), x(120f), y(90f), x(110f), y(150f))
                        lineTo(x(140f), y(150f))
                        lineTo(x(140f), y(34f))
                        close()
                    },
                    maskShade,
                )
            }

            // Lavender teardrops above and below each eye, tapering toward it.
            listOf(79f, 121f).forEach { cx ->
                drawPath(
                    p {
                        moveTo(x(cx), y(75f))
                        cubicTo(x(cx - 5f), y(70f), x(cx - 5.6f), y(52f), x(cx), y(52f))
                        cubicTo(x(cx + 5.6f), y(52f), x(cx + 5f), y(70f), x(cx), y(75f))
                        close()
                    },
                    marks,
                )
                drawPath(
                    p {
                        moveTo(x(cx), y(97f))
                        cubicTo(x(cx - 5f), y(102f), x(cx - 5.6f), y(124f), x(cx), y(124f))
                        cubicTo(x(cx + 5.6f), y(124f), x(cx + 5f), y(102f), x(cx), y(97f))
                        close()
                    },
                    marks,
                )
            }

            // Eyes: a horizontal pill with a smaller underline pill. They wander with [gaze]
            // and squash shut around their own center when [blink] closes them.
            translate(left = gaze * 2.4f * u) {
                scale(scaleX = 1f, scaleY = 1f - 0.85f * blink, pivot = Offset(x(100f), y(85f))) {
                    listOf(79f, 121f).forEach { cx ->
                        drawOval(
                            color = body,
                            topLeft = Offset(x(cx - 7.6f), y(77.6f)),
                            size = Size(15.2f * u, 6.8f * u),
                        )
                        drawOval(
                            color = body,
                            topLeft = Offset(x(cx - 6.2f), y(87f)),
                            size = Size(12.4f * u, 4f * u),
                        )
                    }
                }
            }

            // Mouth and the small chin mark beneath it.
            drawOval(
                color = body,
                topLeft = Offset(x(89f), y(124f)),
                size = Size(22f * u, 8.8f * u),
            )
            drawOval(
                color = marks,
                topLeft = Offset(x(93f), y(138.2f)),
                size = Size(14f * u, 4.4f * u),
            )
        }
    }
}
