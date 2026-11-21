package app.jangkau.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jangkau.editor.Languages

@Composable
fun FileIcon(name: String, isDir: Boolean = false, expanded: Boolean = false, tint: Color? = null, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(width = size + 6.dp, height = size), contentAlignment = Alignment.Center) {
        val viewer = Languages.viewer(name)
        when {
            isDir -> Icon(if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null, tint = tint ?: Ide.folder, modifier = Modifier.size(size - 2.dp))
            viewer == Languages.Viewer.Image -> Icon(Icons.Outlined.Image, null, tint = tint ?: Ide.media, modifier = Modifier.size(size - 2.dp))
            viewer == Languages.Viewer.Pdf -> Icon(Icons.Outlined.PictureAsPdf, null, tint = tint ?: Ide.deleted, modifier = Modifier.size(size - 2.dp))
            else -> {
                val lang = Languages.forName(name)
                if (lang.scope == null) {
                    Icon(Icons.Outlined.Description, null, tint = tint ?: Ide.textDim, modifier = Modifier.size(size - 2.dp))
                } else {
                    val fontSize = when {
                        lang.badge.length >= 3 -> 8.5.sp
                        lang.badge.length == 2 -> 10.sp
                        else -> 12.sp
                    }
                    Text(
                        lang.badge,
                        maxLines = 1,
                        style = TextStyle(
                            fontFamily = Ide.mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSize,
                            letterSpacing = (-0.3).sp,
                            color = tint ?: Color(lang.color),
                        ),
                    )
                }
            }
        }
    }
}
