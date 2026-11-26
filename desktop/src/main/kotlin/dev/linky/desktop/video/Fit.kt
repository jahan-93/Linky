package dev.linky.desktop.video

import androidx.compose.ui.geometry.Rect

/** 영역(areaW×areaH) 안에 영상 비율을 유지해 가운데 맞춘 사각형. 영상 크기를 모르면 null. */
fun fitRect(areaW: Float, areaH: Float, videoW: Int, videoH: Int): Rect? {
    if (videoW <= 0 || videoH <= 0 || areaW <= 0f || areaH <= 0f) return null
    val scale = minOf(areaW / videoW, areaH / videoH)
    val w = videoW * scale
    val h = videoH * scale
    val left = (areaW - w) / 2
    val top = (areaH - h) / 2
    return Rect(left, top, left + w, top + h)
}
