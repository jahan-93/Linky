package dev.linky.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.linky.desktop.AppController
import dev.linky.desktop.Quality
import dev.linky.desktop.net.ConnPhase
import dev.linky.protocol.Keys

/** 위 도구줄: 폰 버튼, 화질, 다시 연결 / 연결 끊기 */
@Composable
fun Toolbar(app: AppController) {
    val live = app.inSession
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Linky", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        KeyButton("뒤로", live) { app.key(Keys.BACK) }
        KeyButton("홈", live) { app.key(Keys.HOME) }
        KeyButton("최근 앱", live) { app.key(Keys.RECENTS) }
        KeyButton("알림창", live) { app.key(Keys.NOTIFICATIONS) }
        Spacer(Modifier.width(4.dp))
        QualityMenu(app.quality, app::changeQuality)
        Spacer(Modifier.weight(1f))
        // 자동 재연결을 기다리는 동안에도 [연결 끊기]로 멈출 수 있다
        if (app.phase == ConnPhase.IDLE && !app.retrying) {
            if (app.lastTarget != null) OutlinedButton(onClick = app::reconnect) { Text("다시 연결") }
        } else {
            Button(
                onClick = app::disconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("연결 끊기") }
        }
    }
}

@Composable
private fun KeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) { Text(label) }
}

@Composable
private fun QualityMenu(current: Quality, onPick: (Quality) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("화질: ${current.label} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Quality.entries.forEach { q ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text((if (q == current) "✓ " else "    ") + q.label)
                            Text(q.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        open = false
                        if (q != current) onPick(q)
                    },
                )
            }
        }
    }
}
