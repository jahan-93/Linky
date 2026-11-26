package dev.linky.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import org.jetbrains.skia.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.linky.desktop.store.LocalDevice
import dev.linky.desktop.ui.LinkyApp
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import kotlin.system.exitProcess

fun main() {
    val app = AppController(LocalDevice.open())
    app.start()
    // 기본 1100×900, 화면(작업 표시줄 제외)이 더 작으면 거기에 맞춘다
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val width = minOf(1100, screen.width - 32)
    val height = minOf(900, screen.height - 32)
    // 창 왼쪽 위와 작업 표시줄에 보이는 아이콘 (docs/logo/make-icons.ps1이 만든 linky-512.png 복사본)
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("linky.png")
        ?.use { BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) }
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Linky",
            icon = icon,
            state = rememberWindowState(size = DpSize(width.dp, height.dp), position = WindowPosition(Alignment.Center)),
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(minOf(900, width), minOf(600, height)) }
            LinkyApp(app)
        }
    }
    // 창이 닫힌 뒤 UI 스레드 밖에서 연결과 mDNS를 닫는다 (JmDNS.close는 느릴 수 있음)
    app.shutdown()
    exitProcess(0)
}
