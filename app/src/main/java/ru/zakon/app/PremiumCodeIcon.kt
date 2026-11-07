package ru.zakon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class CodeBookStyle(val leather: Color, val shade: Color, val emblem: String, val ornament: String = "✦")

private fun codeBookStyle(id: String): CodeBookStyle = when (id) {
    "uk", "upk", "uik" -> CodeBookStyle(Color(0xFF8B2028), Color(0xFF3D0D16), "⚖")
    "gk", "gpk" -> CodeBookStyle(Color(0xFF173F76), Color(0xFF071B3B), "✒")
    "apk", "kas" -> CodeBookStyle(Color(0xFF1D5B39), Color(0xFF092819), "⚒")
    "koap" -> CodeBookStyle(Color(0xFF56316F), Color(0xFF21102F), "⚖")
    "tk", "grk" -> CodeBookStyle(Color(0xFF12636A), Color(0xFF073039), "⌂")
    "nk" -> CodeBookStyle(Color(0xFF183D68), Color(0xFF091D35), "₽")
    "zhk", "sk" -> CodeBookStyle(Color(0xFF8A4824), Color(0xFF3B1B0D), "⌂")
    "zk", "lk" -> CodeBookStyle(Color(0xFF176B55), Color(0xFF07352B), "♧")
    "bk" -> CodeBookStyle(Color(0xFF4B5420), Color(0xFF20260B), "₽")
    "vk", "air", "ktm" -> CodeBookStyle(Color(0xFF14566C), Color(0xFF082B3A), "◇")
    else -> CodeBookStyle(Color(0xFF34445C), Color(0xFF121D2C), "§")
}

@Composable
internal fun PremiumCodeTile(code: RfCode, onClick: () -> Unit) {
    val style = codeBookStyle(code.id)
    Column(
        modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF172437).copy(alpha = 0.96f), Color(0xFF091321).copy(alpha = 0.98f))))
            .border(1.dp, Color(0xFFD7B56D).copy(alpha = 0.72f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PremiumBookCover(code = code, style = style, modifier = Modifier.width(86.dp).height(104.dp))
        Text(code.shortName, color = Color(0xFFFFD98A), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (code.parts.isNotEmpty()) Text("${code.parts.size} части", color = Color(0xFFD5DCE7), fontSize = 10.sp)
    }
}

@Composable
internal fun PremiumCodeHero(code: RfCode) {
    val style = codeBookStyle(code.id)
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF101B2B), Color(0xFF24334A))))
            .border(1.dp, Color(0xFFD7B56D).copy(alpha = 0.65f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PremiumBookCover(code, style, Modifier.width(104.dp).height(132.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(code.shortName, color = Color(0xFFFFD98A), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(code.fullName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp)
            Text("ПРАВОВОЙ КОДЕКС РФ", color = Color(0xFFC5CDD8), fontSize = 9.sp, letterSpacing = 1.2.sp)
        }
    }
}

@Composable
private fun PremiumBookCover(code: RfCode, style: CodeBookStyle, modifier: Modifier = Modifier) {
    Box(modifier.shadow(7.dp, RoundedCornerShape(7.dp)).clip(RoundedCornerShape(7.dp))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                brush = Brush.linearGradient(listOf(style.leather, style.shade, style.leather)),
                size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
            )
            drawRoundRect(
                color = Color(0xFFFFD77A), topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
                size = Size(w - 8.dp.toPx(), h - 8.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = Stroke(1.2.dp.toPx())
            )
            drawRoundRect(
                color = Color(0xFFFFD77A).copy(alpha = 0.55f),
                topLeft = Offset(8.dp.toPx(), 8.dp.toPx()),
                size = Size(w - 16.dp.toPx(), h - 16.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(0.7.dp.toPx())
            )
            // Raised spine and gold tooling.
            drawRoundRect(color = style.shade.copy(alpha = 0.8f), topLeft = Offset(4.dp.toPx(), 3.dp.toPx()), size = Size(9.dp.toPx(), h - 6.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            for (y in listOf(15f, 22f, 82f, 89f)) {
                drawLine(Color(0xFFFFD77A).copy(alpha = 0.8f), Offset(5.dp.toPx(), y.dp.toPx()), Offset(13.dp.toPx(), y.dp.toPx()), 1.dp.toPx())
            }
            // Fine corner ornaments and central embossed medallion.
            val gold = Color(0xFFFFD77A)
            val left = 15.dp.toPx()
            val right = w - 15.dp.toPx()
            val top = 14.dp.toPx()
            val bottom = h - 14.dp.toPx()
            drawLine(gold, Offset(left, top), Offset(left + 8.dp.toPx(), top), 1.dp.toPx())
            drawLine(gold, Offset(left, top), Offset(left, top + 8.dp.toPx()), 1.dp.toPx())
            drawLine(gold, Offset(right, top), Offset(right - 8.dp.toPx(), top), 1.dp.toPx())
            drawLine(gold, Offset(right, top), Offset(right, top + 8.dp.toPx()), 1.dp.toPx())
            drawLine(gold, Offset(left, bottom), Offset(left + 8.dp.toPx(), bottom), 1.dp.toPx())
            drawLine(gold, Offset(left, bottom), Offset(left, bottom - 8.dp.toPx()), 1.dp.toPx())
            drawLine(gold, Offset(right, bottom), Offset(right - 8.dp.toPx(), bottom), 1.dp.toPx())
            drawLine(gold, Offset(right, bottom), Offset(right, bottom - 8.dp.toPx()), 1.dp.toPx())
            drawCircle(gold.copy(alpha = 0.9f), radius = 17.dp.toPx(), center = Offset(w * 0.59f, h * 0.53f), style = Stroke(1.dp.toPx()))
            drawCircle(gold.copy(alpha = 0.35f), radius = 13.dp.toPx(), center = Offset(w * 0.59f, h * 0.53f), style = Stroke(0.7.dp.toPx()))
            drawLine(gold.copy(alpha = 0.8f), Offset(20.dp.toPx(), h - 22.dp.toPx()), Offset(w - 20.dp.toPx(), h - 22.dp.toPx()), 0.8.dp.toPx())
        }
        Column(Modifier.fillMaxSize().padding(top = 10.dp, bottom = 8.dp, start = 13.dp, end = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✥", color = Color(0xFFFFD77A), fontSize = 15.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(code.symbol.uppercase(), color = Color(0xFFFFE4A6), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            Spacer(Modifier.weight(0.3f))
            Text(style.emblem, color = Color(0xFFFFD77A), fontSize = 25.sp)
            Spacer(Modifier.weight(0.3f))
            Text("ЗАКОНЪ", color = Color(0xFFFFD77A), fontWeight = FontWeight.Bold, fontSize = 7.sp, letterSpacing = 1.sp)
        }
    }
}
