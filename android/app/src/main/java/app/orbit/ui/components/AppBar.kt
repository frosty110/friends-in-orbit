package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

@Composable
fun OrbitAppBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    subtle: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (subtle) Color.Transparent else OrbitTheme.colors.bg)
            .padding(start = 16.dp, end = 8.dp),
    ) {
        if (leading != null) {
            Box { leading() }
        }
        Text(
            text = title,
            style = OrbitTheme.type.h3.copy(
                color = OrbitTheme.colors.fg,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(start = if (leading != null) 4.dp else 0.dp),
        )
        if (trailing != null) {
            Box { trailing() }
        }
    }
}
