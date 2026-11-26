package dev.linky.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.linky.desktop.AppController

/** 창 전체: 위 도구줄, 왼쪽 폰 목록, 가운데 영상, 아래 입력과 상태, 맨 아래 로그. */
@Composable
fun LinkyApp(app: AppController) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Toolbar(app)
                HorizontalDivider()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    DevicePanel(app, Modifier.width(300.dp).fillMaxHeight())
                    VerticalDivider()
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        app.banners.forEach { b -> NoticeBanner(b, onClose = { app.dismissBanner(b) }) }
                        VideoView(app, Modifier.weight(1f).fillMaxWidth())
                        HorizontalDivider()
                        BottomBar(app)
                    }
                }
                LogPanel(app.logs)
            }
        }
        app.sas?.let { PairingDialog(it, onAnswer = app::confirmPairing, onCancel = app::disconnect) }
    }
}
