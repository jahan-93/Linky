package dev.linky.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.linky.desktop.Banner

/** 화면 위쪽 알림 띠: 폰의 안내(NOTICE)와 오류 */
@Composable
fun NoticeBanner(banner: Banner, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (banner.error) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (banner.error) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(banner.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("닫기") }
        }
    }
}
