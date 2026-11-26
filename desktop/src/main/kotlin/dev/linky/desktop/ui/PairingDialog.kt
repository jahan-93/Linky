package dev.linky.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.linky.desktop.SasPrompt

/**
 * 숫자 비교 페어링 (설계서 1.4의 4~5단계). 바깥을 눌러도 닫히지 않는다.
 * 숫자와 함께 양쪽 ID를 보여줘, 폰 화면에 보이는 ID와도 비교하게 한다.
 */
@Composable
fun PairingDialog(prompt: SasPrompt, onAnswer: (Boolean) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("페어링 확인: ${prompt.phoneName}") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                prompt.warning?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Text("폰에도 같은 숫자가 보이면 [일치]를 누르세요", style = MaterialTheme.typography.bodyLarge)
                Text(
                    prompt.code.chunked(3).joinToString(" "),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    IdLine("폰 ID", prompt.phoneId)
                    IdLine("이 노트북 ID", prompt.laptopId)
                    Text(
                        "폰 앱에 보이는 폰 ID, 폰의 확인 창에 보이는 노트북 ID와 같은지도 확인하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (prompt.answered) {
                    Text(
                        "폰에서도 [일치]를 누르면 연결됩니다…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAnswer(true) }, enabled = !prompt.answered) { Text("일치") }
        },
        dismissButton = {
            if (prompt.answered) {
                TextButton(onClick = onCancel) { Text("취소") }
            } else {
                OutlinedButton(onClick = { onAnswer(false) }) { Text("다름") }
            }
        },
    )
}

@Composable
private fun IdLine(label: String, id: String) {
    Text("$label  $id", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
}
