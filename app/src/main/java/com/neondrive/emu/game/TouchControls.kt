package com.neondrive.emu.game

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.emu.data.GameSystem
import com.neondrive.emu.ui.Neon
import kotlin.math.hypot

/**
 * LibretroDroid maps Android gamepad keys to the RetroPad (A->B, B->A, X->Y, Y->X),
 * and Genesis Plus GX maps the RetroPad to Sega buttons (Y->A, B->B, A->C, L->X, X->Y, R->Z, Select->Mode).
 * So each on-screen Sega button sends the Android key below.
 */
object SegaKeys {
    const val A = KeyEvent.KEYCODE_BUTTON_X
    const val B = KeyEvent.KEYCODE_BUTTON_A
    const val C = KeyEvent.KEYCODE_BUTTON_B
    const val X = KeyEvent.KEYCODE_BUTTON_L1
    const val Y = KeyEvent.KEYCODE_BUTTON_Y
    const val Z = KeyEvent.KEYCODE_BUTTON_R1
    const val START = KeyEvent.KEYCODE_BUTTON_START
    const val MODE = KeyEvent.KEYCODE_BUTTON_SELECT
    // Master System / Game Gear: button 1 = RetroPad B, button 2 = RetroPad A
    const val ONE = KeyEvent.KEYCODE_BUTTON_A
    const val TWO = KeyEvent.KEYCODE_BUTTON_B
}

data class ControlsConfig(
    val system: GameSystem,
    val sixButtons: Boolean,
    val opacity: Float,
    val scale: Float,
    val haptics: Boolean,
    val rewindEnabled: Boolean,
)

@Composable
fun TouchControls(
    cfg: ControlsConfig,
    fastForward: Boolean,
    onKey: (keyCode: Int, pressed: Boolean) -> Unit,
    onDpad: (x: Float, y: Float) -> Unit,
    onMenu: () -> Unit,
    onToggleFastForward: () -> Unit,
    onRewind: (Boolean) -> Unit,
) {
    val s = cfg.scale
    Box(Modifier.fillMaxSize().alpha(cfg.opacity).safeDrawingPadding().padding(12.dp)) {
        // Top bar: menu / rewind / fast-forward
        Row(Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (cfg.rewindEnabled) {
                IconPad(Icons.Default.FastRewind, active = false, hold = onRewind, haptics = cfg.haptics)
            }
            IconPad(Icons.Default.Menu, active = false, tap = onMenu, haptics = cfg.haptics)
            IconPad(Icons.Default.FastForward, active = fastForward, tap = onToggleFastForward, haptics = cfg.haptics)
        }

        DPad(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp), 160.dp * s, cfg.haptics, onDpad)

        // Face buttons
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 8.dp)) {
            val b = 58.dp * s
            when {
                cfg.system == GameSystem.GENESIS && cfg.sixButtons -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.padding(start = b / 2), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FaceButton("X", b * 0.85f, Neon.pad.sample(0.6f), SegaKeys.X, cfg.haptics, onKey)
                        FaceButton("Y", b * 0.85f, Neon.pad.sample(0.8f), SegaKeys.Y, cfg.haptics, onKey)
                        FaceButton("Z", b * 0.85f, Neon.pad.sample(1f), SegaKeys.Z, cfg.haptics, onKey)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FaceButton("A", b, Neon.pad.sample(0f), SegaKeys.A, cfg.haptics, onKey)
                        FaceButton("B", b, Neon.pad.sample(0.2f), SegaKeys.B, cfg.haptics, onKey)
                        FaceButton("C", b, Neon.pad.sample(0.4f), SegaKeys.C, cfg.haptics, onKey)
                    }
                }
                cfg.system == GameSystem.GENESIS -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FaceButton("A", b, Neon.pad.sample(0f), SegaKeys.A, cfg.haptics, onKey, Modifier.padding(top = b / 2))
                    FaceButton("B", b, Neon.pad.sample(0.2f), SegaKeys.B, cfg.haptics, onKey, Modifier.padding(top = b / 4))
                    FaceButton("C", b, Neon.pad.sample(0.4f), SegaKeys.C, cfg.haptics, onKey)
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FaceButton("1", b * 1.1f, Neon.pad.sample(0f), SegaKeys.ONE, cfg.haptics, onKey, Modifier.padding(top = b / 3))
                    FaceButton("2", b * 1.1f, Neon.pad.sample(1f), SegaKeys.TWO, cfg.haptics, onKey)
                }
            }
        }

        // Start / Mode
        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (cfg.system == GameSystem.GENESIS && cfg.sixButtons) PillButton("MODE", SegaKeys.MODE, cfg.haptics, onKey)
            PillButton(if (cfg.system == GameSystem.MASTER_SYSTEM) "PAUSE" else "START", SegaKeys.START, cfg.haptics, onKey)
        }
    }
}

@Composable
private fun DPad(modifier: Modifier, size: Dp, haptics: Boolean, onDpad: (Float, Float) -> Unit) {
    val view = LocalView.current
    var dir by remember { mutableStateOf(0 to 0) }

    fun update(nx: Int, ny: Int) {
        if (dir != nx to ny) {
            dir = nx to ny
            onDpad(nx.toFloat(), ny.toFloat())
            if (haptics && (nx != 0 || ny != 0)) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    Box(
        modifier.size(size)
            .drawBehind {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val r = this.size.minDimension / 2
                drawCircle(Neon.Surface.copy(alpha = 0.55f), r, c)
                drawCircle(Neon.pad.sample(0f).copy(alpha = 0.8f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                val arm = r * 0.34f
                // cross
                drawRoundRect(
                    Neon.SurfaceHi, Offset(c.x - arm, c.y - r * 0.82f),
                    androidx.compose.ui.geometry.Size(arm * 2, r * 1.64f),
                    androidx.compose.ui.geometry.CornerRadius(arm * 0.4f),
                )
                drawRoundRect(
                    Neon.SurfaceHi, Offset(c.x - r * 0.82f, c.y - arm),
                    androidx.compose.ui.geometry.Size(r * 1.64f, arm * 2),
                    androidx.compose.ui.geometry.CornerRadius(arm * 0.4f),
                )
                // arrows, lit when pressed
                val (dx, dy) = dir
                fun arrow(ax: Int, ay: Int) {
                    val lit = (ax != 0 && ax == dx) || (ay != 0 && ay == dy)
                    val tip = Offset(c.x + ax * r * 0.72f, c.y + ay * r * 0.72f)
                    val base = Offset(c.x + ax * r * 0.5f, c.y + ay * r * 0.5f)
                    val side = Offset(-ay * arm * 0.55f, ax * arm * 0.55f)
                    val p = Path().apply {
                        moveTo(tip.x, tip.y); lineTo(base.x + side.x, base.y + side.y); lineTo(base.x - side.x, base.y - side.y); close()
                    }
                    drawPath(p, if (lit) Neon.pad.sample(1f) else Neon.pad.sample(0f).copy(alpha = 0.7f))
                }
                arrow(0, -1); arrow(0, 1); arrow(-1, 0); arrow(1, 0)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun handle(p: Offset) {
                        val cx = this.size.width / 2f; val cy = this.size.height / 2f
                        val nx = (p.x - cx) / cx; val ny = (p.y - cy) / cy
                        if (hypot(nx, ny) < 0.2f) { update(0, 0); return }
                        val t = 0.38f
                        update(if (nx > t) 1 else if (nx < -t) -1 else 0, if (ny > t) 1 else if (ny < -t) -1 else 0)
                    }
                    handle(down.position); down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        handle(ch.position); ch.consume()
                    }
                    update(0, 0)
                }
            },
    )
}

@Composable
private fun FaceButton(
    label: String, size: Dp, color: Color, keyCode: Int, haptics: Boolean,
    onKey: (Int, Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box(
        modifier.size(size)
            .background(if (pressed) color.copy(alpha = 0.55f) else Neon.Surface.copy(alpha = 0.6f), CircleShape)
            .border(2.dp, color, CircleShape)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true; onKey(keyCode, true)
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    waitForUpOrCancellation()
                    pressed = false; onKey(keyCode, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) Neon.Bg else color, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
private fun PillButton(label: String, keyCode: Int, haptics: Boolean, onKey: (Int, Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.height(30.dp).widthIn(min = 72.dp)
            .background(if (pressed) Neon.pad.sample(0f).copy(alpha = 0.5f) else Neon.Surface.copy(alpha = 0.6f), shape)
            .border(1.5.dp, Neon.pad.sample(0f), shape)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true; onKey(keyCode, true)
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    waitForUpOrCancellation()
                    pressed = false; onKey(keyCode, false)
                }
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Neon.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun IconPad(
    icon: ImageVector, active: Boolean, haptics: Boolean,
    tap: (() -> Unit)? = null, hold: ((Boolean) -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val lit = pressed || active
    val onTap by rememberUpdatedState(tap)
    val onHold by rememberUpdatedState(hold)
    Box(
        Modifier.size(42.dp)
            .background(if (lit) Neon.pad.sample(1f).copy(alpha = 0.45f) else Neon.Surface.copy(alpha = 0.6f), CircleShape)
            .border(1.5.dp, if (lit) Neon.pad.sample(1f) else Neon.pad.sample(0f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true; onHold?.invoke(true)
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val up = waitForUpOrCancellation()
                    pressed = false; onHold?.invoke(false)
                    if (up != null) onTap?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (lit) Neon.Text else Neon.pad.sample(0f), modifier = Modifier.size(22.dp)) }
}
