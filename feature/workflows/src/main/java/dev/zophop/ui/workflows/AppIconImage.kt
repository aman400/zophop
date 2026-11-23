package dev.zophop.ui.workflows

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

@Composable
fun AppIconImage(
    drawable: Drawable?,
    modifier: Modifier = Modifier.size(24.dp),
) {
    if (drawable != null) {
        val bitmap =
            remember(drawable) {
                try {
                    drawable.toBitmap(width = 48, height = 48).asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = modifier,
            )
            return
        }
    }
    Icon(
        imageVector = Icons.Default.Apps,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
