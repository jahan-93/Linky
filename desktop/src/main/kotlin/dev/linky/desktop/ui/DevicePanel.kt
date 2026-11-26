package dev.linky.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.linky.desktop.AppController
import dev.linky.desktop.net.FoundPhone
import dev.linky.desktop.store.TrustedPhone
import dev.linky.protocol.Linky

/** 왼쪽 패널: 찾은 폰, IP 직접 입력, 페어링한 폰(삭제) */
@Composable
fun DevicePanel(app: AppController, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("찾은 폰")
        if (app.phones.isEmpty()) {
            Hint("같은 Wi-Fi에서 폰을 찾는 중입니다. 폰에서 [화면 공유 시작]을 누르면 여기에 나타납니다.")
        }
        app.phones.forEach { PhoneCard(app, it) }

        Spacer(Modifier.height(8.dp))
        SectionTitle("IP 직접 입력")
        Hint("목록에 안 보이면 폰 앱에 표시된 IP를 입력하세요. (포트 ${Linky.PORT})")
        ManualConnect(app::connectManual)

        Spacer(Modifier.height(8.dp))
        SectionTitle("페어링한 폰")
        if (app.trusted.isEmpty()) Hint("아직 없습니다.")
        TrustedList(app.trusted, app::forget)
    }
}

@Composable
private fun PhoneCard(app: AppController, phone: FoundPhone) {
    val paired = app.isPaired(phone)
    val current = app.isCurrent(phone)
    val versionOk = phone.version == null || phone.version == Linky.PROTOCOL_VERSION.toString()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(phone.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Badge(if (paired) "페어링됨" else "새 기기", highlight = paired)
            }
            Text("${phone.host}:${phone.port}", style = MaterialTheme.typography.bodySmall)
            // 폰 앱 화면의 ID와 비교할 수 있게 보여준다 (mDNS로 폰이 알린 값)
            phone.fp?.let { Text("ID ${it.take(16)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            if (!versionOk) {
                Text(
                    "앱 버전이 다릅니다 (폰 ${phone.version}, 노트북 ${Linky.PROTOCOL_VERSION})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (current) {
                    Text(if (app.inSession) "연결됨" else "연결 중", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                } else {
                    Button(onClick = { app.connect(phone.toTarget()) }) { Text("연결") }
                }
            }
        }
    }
}

@Composable
private fun ManualConnect(onConnect: (String) -> Unit) {
    var ip by remember { mutableStateOf("") }
    val submit = { if (ip.isNotBlank()) onConnect(ip) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it.trim() },
            singleLine = true,
            placeholder = { Text("192.168.0.10") },
            modifier = Modifier.weight(1f).onPreviewKeyEvent {
                if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                    submit()
                    true
                } else {
                    false
                }
            },
        )
        Button(onClick = submit, enabled = ip.isNotBlank()) { Text("연결") }
    }
}

@Composable
private fun TrustedList(trusted: List<TrustedPhone>, onForget: (String) -> Unit) {
    var asking by remember { mutableStateOf<TrustedPhone?>(null) }
    trusted.sortedBy { it.name.lowercase() }.forEach { phone ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(phone.name, style = MaterialTheme.typography.bodyMedium)
                Text("ID ${phone.shortId}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                phone.host?.let { Text("마지막 주소 $it", style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { asking = phone }) { Text("페어링 삭제") }
        }
    }
    asking?.let { phone ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("페어링 삭제") },
            text = { Text("${phone.name} 페어링을 삭제할까요? 다시 연결하려면 숫자 비교 페어링을 새로 해야 합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    onForget(phone.fp)
                    asking = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Badge(text: String, highlight: Boolean) {
    val bg = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
