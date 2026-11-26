package dev.linky.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.linky.desktop.AppController
import dev.linky.desktop.input.MouseInput
import dev.linky.desktop.net.ConnPhase
import dev.linky.desktop.video.fitRect
import dev.linky.protocol.Keys

private const val GUIDE = "폰의 Linky 앱에서 [화면 공유 시작]을 누르세요. 처음이면 [새 노트북 연결]도 누르세요."

/** 가운데: 폰 화면(마우스 조작) 또는 안내 문구 */
@Composable
fun VideoView(app: AppController, modifier: Modifier = Modifier) {
    val surface = app.surface
    val video = app.videoSize
    val live = app.inSession && video != IntSize.Zero && surface != null
    val input = remember(app) { MouseInput(app) }

    // 창 포커스를 잃거나 영상이 끝나면 누르고 있던 손가락을 뗀다
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, live) { if (!focused || !live) input.cancel() }

    val videoState = rememberUpdatedState(video)
    val liveState = rememberUpdatedState(live)
    Box(
        modifier
            .background(if (live) Color.Black else MaterialTheme.colorScheme.surfaceVariant)
            .mouseControl(input, videoState, liveState),
        contentAlignment = Alignment.Center,
    ) {
        if (surface != null) {
            Canvas(Modifier.fillMaxSize()) {
                app.frameTick // 새 영상마다 다시 그린다
                surface.viewport = IntSize(size.width.toInt(), size.height.toInt())
                drawIntoCanvas { surface.draw(it.skiaCanvas, size.width, size.height) }
            }
        }
        if (!live) Guide(app.phase)
    }
}

@Composable
private fun Guide(phase: ConnPhase) {
    val text = when (phase) {
        ConnPhase.IDLE -> GUIDE
        ConnPhase.CONNECTING -> "연결하는 중…"
        ConnPhase.AUTH -> "폰과 확인하는 중…"
        ConnPhase.PAIRING -> "페어링 중입니다. 노트북과 폰에 보이는 숫자를 비교하세요."
        ConnPhase.SESSION -> "영상을 기다리는 중…"
    }
    Column(
        Modifier.widthIn(max = 520.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (phase != ConnPhase.IDLE) CircularProgressIndicator(Modifier.size(36.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 마우스 처리 (설계서 3장). 좌표는 그려진 영상 사각형 안의 0~1 비율.
 * 왼쪽 드래그 → 터치, 오른쪽 → 뒤로, 가운데 → 홈, 휠 → 스크롤.
 */
private fun Modifier.mouseControl(input: MouseInput, video: State<IntSize>, live: State<Boolean>) =
    pointerInput(input) {
        awaitPointerEventScope {
            var wasPrimary = false
            var wasSecondary = false
            var wasTertiary = false
            var dragId: PointerId? = null
            while (true) {
                val event = awaitPointerEvent()
                val buttons = event.buttons
                val area = fitRect(size.width.toFloat(), size.height.toFloat(), video.value.width, video.value.height)
                val change = event.changes.firstOrNull { it.id == dragId } ?: event.changes.first()
                if (live.value && area != null) {
                    when (event.type) {
                        PointerEventType.Press -> when {
                            buttons.isPrimaryPressed && !wasPrimary && !input.dragging ->
                                if (area.contains(change.position)) {
                                    dragId = change.id
                                    input.down(area.ratio(change.position))
                                }
                            input.dragging -> Unit // 드래그 중 다른 버튼은 무시
                            buttons.isSecondaryPressed && !wasSecondary -> input.key(Keys.BACK)
                            buttons.isTertiaryPressed && !wasTertiary -> input.key(Keys.HOME)
                        }
                        PointerEventType.Move ->
                            if (input.dragging && change.id == dragId) input.move(area.points(change))
                        PointerEventType.Release ->
                            // 영상 밖(창 밖 포함)에서 놓아도 UP을 보낸다
                            if (input.dragging && !buttons.isPrimaryPressed) {
                                input.up(area.ratio(change.position))
                                dragId = null
                            }
                        PointerEventType.Scroll -> input.wheel(area.ratio(change.position), change.scrollDelta.y)
                    }
                }
                if (!input.dragging) dragId = null
                wasPrimary = buttons.isPrimaryPressed
                wasSecondary = buttons.isSecondaryPressed
                wasTertiary = buttons.isTertiaryPressed
            }
        }
    }

private fun Rect.ratio(p: Offset) = Offset(
    ((p.x - left) / width).coerceIn(0f, 1f),
    ((p.y - top) / height).coerceIn(0f, 1f),
)

/** historical 점까지 (비율, 시각). 시각은 지금을 기준으로 이벤트 사이 간격만큼 앞당긴다. */
private fun Rect.points(change: PointerInputChange): List<Pair<Offset, Double>> {
    val now = MouseInput.nowMs()
    val latest = change.uptimeMillis
    return change.historical.map { ratio(it.position) to now - (latest - it.uptimeMillis) } +
        (ratio(change.position) to now)
}
