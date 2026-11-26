package dev.linky.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.linky.desktop.AppController
import dev.linky.desktop.net.ConnPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 아래: 글자 입력칸(Enter로 보내기)과 상태줄 */
@Composable
fun BottomBar(app: AppController) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextInput(app)
        StatusLine(app)
    }
}

@Composable
private fun TextInput(app: AppController) {
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // 보내지 못하면 입력칸을 비우지 않는다
    val submit = { if (app.sendText(text)) text = "" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            enabled = app.inSession,
            placeholder = { Text("폰에 보낼 글자 (Enter로 보내기)") },
            modifier = Modifier.weight(1f).onPreviewKeyEvent {
                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                    // 한글 조합 중 Enter: 조합 확정이 먼저 반영되도록 잠깐 뒤에 보낸다
                    scope.launch {
                        delay(30)
                        submit()
                    }
                    true
                } else {
                    false
                }
            },
        )
        Button(onClick = submit, enabled = app.inSession && text.isNotEmpty()) { Text("보내기") }
    }
}

@Composable
private fun StatusLine(app: AppController) {
    val name = app.phoneName ?: ""
    val head = when (app.phase) {
        ConnPhase.IDLE -> app.endedText ?: "연결 안 됨"
        ConnPhase.CONNECTING -> "연결 중: ${app.lastTarget?.host ?: name}"
        ConnPhase.AUTH -> "확인 중: $name"
        ConnPhase.PAIRING -> "페어링 중: $name"
        ConnPhase.SESSION -> "연결됨: $name"
    }
    val parts = mutableListOf(head)
    if (app.inSession) {
        val s = app.stats
        parts += "%.0f fps".format(s.fps)
        parts += "%,d kbps".format(s.kbps)
        parts += s.rttMs?.let { "RTT $it ms" } ?: "RTT -"
        val v = app.videoSize
        if (v.width > 0) parts += "${v.width}×${v.height}"
    }
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
