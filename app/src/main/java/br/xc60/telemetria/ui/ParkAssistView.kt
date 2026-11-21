package br.xc60.telemetria.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.xc60.telemetria.park.SensorPos
import br.xc60.telemetria.park.Zone
import br.xc60.telemetria.park.zoneFor

private val ZFar = Color(0xFF5DBB8A)
private val ZMid = Color(0xFFE5B53D)
private val ZNear = Color(0xFFE8893A)

private fun zoneColor(z: Zone) = when (z) {
    Zone.FAR -> ZFar
    Zone.MID -> ZMid
    Zone.NEAR -> ZNear
    Zone.STOP -> Warn
    Zone.NONE -> Hairline
}

/** Faixa de ângulo (graus do Canvas: 0° = direita, 90° = baixo) de cada sensor, com o carro apontando para cima. */
private fun sectorOf(p: SensorPos): Pair<Float, Float> = when (p) {
    SensorPos.REAR_RIGHT -> 22f to 36f
    SensorPos.REAR_RIGHT_CENTER -> 58f to 32f
    SensorPos.REAR_LEFT_CENTER -> 90f to 32f
    SensorPos.REAR_LEFT -> 122f to 36f
    SensorPos.FRONT_LEFT -> 202f to 36f
    SensorPos.FRONT_LEFT_CENTER -> 238f to 32f
    SensorPos.FRONT_RIGHT_CENTER -> 270f to 32f
    SensorPos.FRONT_RIGHT -> 302f to 36f
}

/**
 * Tela de ré: carro visto de cima, faixas por sensor e a menor distância em destaque.
 * Apoio visual apenas — o bipe e a tela originais do carro continuam valendo.
 */
@Composable
fun ParkAssistView(park: Map<SensorPos, Double?>, showFront: Boolean, modifier: Modifier = Modifier, subtitle: String? = null) {
    val rear = park.filterKeys { !it.front }.values.filterNotNull().minOrNull()
    val front = if (showFront) park.filterKeys { it.front }.values.filterNotNull().minOrNull() else null
    val closestZone = zoneFor(rear)
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "a",
    )

    BoxWithConstraints(modifier.background(Bg)) {
        val wide = maxWidth > maxHeight
        // Tela baixa (celular deitado) ou estreita: números menores para tudo caber sem rolar
        val tight = maxHeight < 500.dp || maxWidth < 420.dp
        val bigSize = if (tight) 60.sp else 96.sp
        val canvas: @Composable (Modifier) -> Unit = { m ->
            Canvas(m) { drawPark(park, showFront, if (closestZone == Zone.STOP) pulse else 1f) }
        }
        val info: @Composable () -> Unit = {
            Column(horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
                Eyebrow("Ré engatada", Accent)
                Spacer(Modifier.height(8.dp))
                Eyebrow("Traseira")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(rear?.let { fmt(it) } ?: "—", fontSize = bigSize, fontWeight = FontWeight.Thin, color = if (rear == null) TextHi else zoneColor(closestZone))
                    Text(" cm", color = Muted, fontSize = 22.sp, modifier = Modifier.padding(bottom = 18.dp))
                }
                Text(
                    when (closestZone) {
                        Zone.STOP -> "PARE"
                        Zone.NEAR -> "Muito perto"
                        Zone.MID -> "Atenção"
                        Zone.FAR -> "Livre por enquanto"
                        Zone.NONE -> "Nada detectado"
                    },
                    fontSize = 22.sp, fontWeight = FontWeight.Light, color = if (closestZone == Zone.NONE) Muted else zoneColor(closestZone),
                )
                if (showFront) {
                    Spacer(Modifier.height(if (tight) 8.dp else 24.dp))
                    Eyebrow("Dianteira")
                    Text(front?.let { "${fmt(it)} cm" } ?: "—", fontSize = 32.sp, fontWeight = FontWeight.Thin, color = zoneColor(zoneFor(front)).takeIf { front != null } ?: TextHi)
                }
                Spacer(Modifier.height(if (tight) 10.dp else 28.dp))
                Text(
                    subtitle ?: "Apoio visual. Confira espelhos, câmera e os bipes do carro.",
                    color = Muted, fontSize = 13.sp,
                )
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                canvas(Modifier.weight(1.3f).fillMaxHeight())
                Box(Modifier.weight(1f)) { info() }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                canvas(Modifier.weight(1f))
                info()
            }
        }
    }
}

private fun DrawScope.drawPark(park: Map<SensorPos, Double?>, showFront: Boolean, alpha: Float) {
    val h = size.height
    val cw = h * 0.2f          // largura do carro
    val cl = cw * 2.35f        // comprimento
    val c = center
    val top = c.y - cl / 2
    val bottom = c.y + cl / 2

    // Faixas: 3 anéis por sensor (longe → perto), acesos conforme a zona
    val bandW = cw * 0.16f
    val gap = cw * 0.06f
    fun rings(origin: Offset, positions: List<SensorPos>) {
        for (p in positions) {
            val (start, sweep) = sectorOf(p)
            val zone = zoneFor(park[p])
            // anel 0 = perto (dentro), 2 = longe (fora)
            for (ring in 0..2) {
                val r = cw * 0.72f + ring * (bandW + gap)
                val lit = when (zone) {
                    Zone.STOP, Zone.NEAR -> ring == 0
                    Zone.MID -> ring == 1
                    Zone.FAR -> ring == 2
                    Zone.NONE -> false
                }
                val color = if (lit) zoneColor(zone).copy(alpha = if (zone == Zone.STOP) alpha else 1f) else Hairline.copy(alpha = 0.55f)
                drawArc(
                    color, start + 1.5f, sweep - 3f, false,
                    Offset(origin.x - r, origin.y - r), Size(r * 2, r * 2),
                    style = Stroke(bandW),
                )
            }
        }
    }
    rings(Offset(c.x, bottom - cw * 0.42f), SensorPos.entries.filter { !it.front })
    if (showFront) rings(Offset(c.x, top + cw * 0.42f), SensorPos.entries.filter { it.front })

    // Carro visto de cima
    val body = Size(cw, cl)
    val tl = Offset(c.x - cw / 2, top)
    drawRoundRect(PanelHi, tl, body, CornerRadius(cw * 0.32f))
    drawRoundRect(Muted.copy(alpha = 0.6f), tl, body, CornerRadius(cw * 0.32f), style = Stroke(2.5f))
    // para-brisa, teto e vidro traseiro
    val glass = Color(0xFF2B3542)
    drawRoundRect(glass, Offset(c.x - cw * 0.38f, top + cl * 0.22f), Size(cw * 0.76f, cl * 0.13f), CornerRadius(cw * 0.1f))
    drawRoundRect(Color(0xFF1F2731), Offset(c.x - cw * 0.36f, top + cl * 0.37f), Size(cw * 0.72f, cl * 0.30f), CornerRadius(cw * 0.08f))
    drawRoundRect(glass, Offset(c.x - cw * 0.36f, top + cl * 0.69f), Size(cw * 0.72f, cl * 0.10f), CornerRadius(cw * 0.1f))
    // retrovisores
    drawRoundRect(PanelHi, Offset(c.x - cw * 0.62f, top + cl * 0.27f), Size(cw * 0.14f, cl * 0.05f), CornerRadius(6f))
    drawRoundRect(PanelHi, Offset(c.x + cw * 0.48f, top + cl * 0.27f), Size(cw * 0.14f, cl * 0.05f), CornerRadius(6f))
    // lanternas
    drawRoundRect(Warn.copy(alpha = 0.8f), Offset(c.x - cw * 0.44f, bottom - cl * 0.035f), Size(cw * 0.22f, cl * 0.02f), CornerRadius(4f))
    drawRoundRect(Warn.copy(alpha = 0.8f), Offset(c.x + cw * 0.22f, bottom - cl * 0.035f), Size(cw * 0.22f, cl * 0.02f), CornerRadius(4f))
    // faróis
    drawRoundRect(TextHi.copy(alpha = 0.7f), Offset(c.x - cw * 0.42f, top + cl * 0.015f), Size(cw * 0.2f, cl * 0.02f), CornerRadius(4f))
    drawRoundRect(TextHi.copy(alpha = 0.7f), Offset(c.x + cw * 0.22f, top + cl * 0.015f), Size(cw * 0.2f, cl * 0.02f), CornerRadius(4f))
}
