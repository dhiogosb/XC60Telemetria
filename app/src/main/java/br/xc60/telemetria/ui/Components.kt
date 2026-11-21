package br.xc60.telemetria.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.xc60.telemetria.obd.Pids
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ---------- Paleta "nórdica": grafite, aço e um azul gelo discreto ----------
val Bg = Color(0xFF0A0D11)
val Panel = Color(0xFF12171D)
val PanelHi = Color(0xFF1A2129)
val Hairline = Color(0xFF232B35)
val TextHi = Color(0xFFEAEEF2)
val Muted = Color(0xFF8A95A3)
val Accent = Color(0xFF9DBEDF)
val AccentDeep = Color(0xFF4F7BA8)
val Warn = Color(0xFFE2664F)
val Ok = Color(0xFF5DBB8A)

val AppColors = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    secondary = AccentDeep,
    background = Bg,
    surface = Panel,
    surfaceVariant = PanelHi,
    onBackground = TextHi,
    onSurface = TextHi,
    outline = Hairline,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = AppColors) {
        // Sem isso o texto padrão sai preto sobre o fundo escuro.
        CompositionLocalProvider(LocalContentColor provides TextHi, content = content)
    }

private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

fun fmt(v: Double?, decimals: Int = 0): String =
    v?.let { String.format(PT_BR, "%.${decimals}f", it) } ?: "—"

fun decimalsFor(key: String) = when (key) {
    "voltage", "maf", "stft1", "ltft1", "timing", "fuelRate" -> 1
    else -> 0
}

/** Rótulo pequeno em caixa alta com espaçamento largo. */
@Composable
fun Eyebrow(text: String, color: Color = Muted, modifier: Modifier = Modifier) =
    Text(text.uppercase(), color = color, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium, modifier = modifier)

@Composable
fun SectionTitle(text: String, size: TextUnit = 20.sp) =
    Text(text, color = TextHi, fontSize = size, fontWeight = FontWeight.Light, letterSpacing = 0.5.sp, modifier = Modifier.padding(vertical = 6.dp))

/** Cartão base: fundo grafite, borda fina, cantos suaves. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(Panel)
            .border(BorderStroke(1.dp, Hairline), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
        content = content,
    )
}

@Composable
fun ValueTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    big: Boolean = false,
) {
    Card(modifier, padding = 16.dp) {
        Eyebrow(label, if (warn) Warn else Muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                value,
                color = if (warn) Warn else TextHi,
                fontSize = if (big) 48.sp else 28.sp,
                fontWeight = FontWeight.Light,
            )
            Text(unit, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
fun PidTile(key: String, values: Map<String, Double>, modifier: Modifier = Modifier, big: Boolean = false) {
    val def = Pids.byKey[key] ?: return
    val v = values[key]
    val warn = v != null && ((def.warnAbove?.let { v > it } == true) || (def.warnBelow?.let { v < it } == true))
    ValueTile(def.label, fmt(v, decimalsFor(key)), def.unit, modifier.fillMaxWidth(), warn, big)
}

/**
 * Mostrador circular: arco de 240°, trilho fino, marcações discretas,
 * faixa vermelha opcional e valor central em peso leve.
 */
@Composable
fun ArcGauge(
    value: Double?,
    max: Double,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    redFrom: Double? = null,
    majorTicks: Int = 8,
    valueSize: TextUnit = 64.sp,
) {
    val target = ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat()
    val frac by animateFloatAsState(target, tween(350), label = "gauge")
    val inRed = redFrom != null && (value ?: 0.0) >= redFrom

    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        // Texto proporcional ao tamanho do mostrador: nunca invade o arco
        val dim = minOf(maxWidth, maxHeight).value
        val vSize = minOf(valueSize.value, dim * 0.22f).sp
        val small = dim < 220f
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = size.minDimension * 0.035f
            val inset = stroke * 2.2f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val start = 150f
            val sweep = 240f

            drawArc(Hairline, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            redFrom?.let {
                val f = (it / max).toFloat()
                drawArc(Warn.copy(alpha = 0.35f), start + sweep * f, sweep * (1 - f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            if (frac > 0.002f) {
                drawArc(
                    Brush.sweepGradient(listOf(AccentDeep, Accent, AccentDeep), center),
                    start, sweep * frac, false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            // Marcações
            val r = arcSize.width / 2
            val c = center
            for (i in 0..majorTicks * 4) {
                val major = i % 4 == 0
                val a = Math.toRadians((start + sweep * i / (majorTicks * 4)).toDouble())
                val outer = r - stroke * 1.6f
                val inner = outer - if (major) stroke * 1.6f else stroke * 0.8f
                drawLine(
                    if (major) Muted else Hairline,
                    Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                    Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()),
                    strokeWidth = if (major) 2.5f else 1.5f,
                )
            }
            // Ponteiro: um ponto luminoso na ponta do arco
            val a = Math.toRadians((start + sweep * frac).toDouble())
            val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            drawCircle(Color.White, stroke * 0.9f, p)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label.uppercase(), color = Muted, fontSize = if (small) 9.sp else 11.sp,
                letterSpacing = if (small) 1.sp else 2.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Text(
                fmt(value, decimals), fontSize = vSize, fontWeight = FontWeight.Thin, maxLines = 1,
                color = if (inRed) Warn else TextHi, textAlign = TextAlign.Center,
            )
            Text(unit, color = Muted, fontSize = if (small) 11.sp else 14.sp, letterSpacing = 1.sp, maxLines = 1)
        }
    }
}

/** Barra fina horizontal (turbo, tanque…). */
@Composable
fun MiniBar(label: String, value: Double?, min: Double, max: Double, text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    val f = (((value ?: min) - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    val anim by animateFloatAsState(f, tween(350), label = "bar")
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Eyebrow(label, modifier = Modifier.weight(1f))
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Light, color = if (warn) Warn else TextHi)
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val h = size.height
                drawLine(Hairline, Offset(0f, h / 2), Offset(size.width, h / 2), h, StrokeCap.Round)
                if (anim > 0.005f) drawLine(if (warn) Warn else Accent, Offset(0f, h / 2), Offset(size.width * anim, h / 2), h, StrokeCap.Round)
            }
        }
    }
}

/** Botão pílula com estado ligado/desligado. */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) Accent else PanelHi)
            .border(1.dp, if (selected) Accent else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Bg else TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
