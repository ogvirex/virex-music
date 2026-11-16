package com.virexdl.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Separate blurred artwork under a neutral translucent pane; controls stay sharp. */
@Composable internal fun GlassPanel(modifier: Modifier = Modifier, art: Song? = null, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.clip(shape).background(Color(0xFF14171D)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).border(1.dp, Color.White.copy(alpha = .18f), shape)) {
        if (!art?.image.isNullOrBlank()) AsyncImage(art?.image, null, Modifier.matchParentSize().blur(28.dp).graphicsLayer(alpha = .28f, scaleX = 1.2f, scaleY = 1.2f), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .09f), Color(0xD9101217), Color.White.copy(alpha = .025f)))))
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}
