package ru.zakon.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun RussianFlagMark() {
    Canvas(
        Modifier.padding(end = 2.dp).size(width = 30.dp, height = 20.dp)
            .clip(RoundedCornerShape(3.dp))
    ) {
        val stripe = size.height / 3f
        drawRect(Color.White, topLeft = Offset.Zero, size = Size(size.width, stripe))
        drawRect(Color(0xFF0039A6), topLeft = Offset(0f, stripe), size = Size(size.width, stripe))
        drawRect(Color(0xFFD52B1E), topLeft = Offset(0f, stripe * 2f), size = Size(size.width, stripe))
    }
}
