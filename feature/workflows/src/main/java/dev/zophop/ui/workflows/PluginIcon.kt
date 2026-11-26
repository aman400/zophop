package dev.zophop.ui.workflows

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PluginIcon(
    packageName: String?,
    iconResName: String?,
    fallbackVector: ImageVector = Icons.Default.Extension,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val drawableState by produceState<Drawable?>(initialValue = null, packageName, iconResName) {
        if (packageName.isNullOrBlank() || iconResName.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value =
            withContext(Dispatchers.IO) {
                try {
                    val appResources = context.packageManager.getResourcesForApplication(packageName)
                    val resId = appResources.getIdentifier(iconResName, "drawable", packageName)
                    if (resId != 0) {
                        androidx.core.content.res.ResourcesCompat
                            .getDrawable(appResources, resId, context.theme)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    val currentDrawable = drawableState
    if (currentDrawable != null) {
        Image(
            bitmap =
                currentDrawable
                    .toBitmap(
                        width = (size.value * 2).toInt().coerceAtLeast(1),
                        height = (size.value * 2).toInt().coerceAtLeast(1),
                    ).asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size),
        )
    } else {
        Icon(
            imageVector = fallbackVector,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size),
        )
    }
}
