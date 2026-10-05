package com.yeji.soundfx

import android.accessibilityservice.AccessibilityService
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 화면은 전혀 안 읽는 빈 접근성 서비스.
 * 켜져 있으면 안드로이드가 '접근성 음량' 채널을 따로 열어주는데,
 * 이 채널은 음악처럼 이어폰으로만 나가고 미디어 음소거에도 영향을 안 받아서 이펙트 소리 출력용으로 씀.
 * 추가로, 이펙터가 꺼진 뒤 못 되돌린 기기별 음량을 그 기기가 다시 연결될 때 되돌려 줌.
 */
class FxA11yService : AccessibilityService() {
    companion object {
        val on = MutableStateFlow(false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { later() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { later() }
    }

    private fun later() {
        handler.postDelayed({ VolumeRestore.restorePendingForCurrent(this) }, 1500)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        on.value = true
        try { getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCb, handler) } catch (_: Exception) { }
        later()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    override fun onDestroy() {
        on.value = false
        handler.removeCallbacksAndMessages(null)
        try { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCb) } catch (_: Exception) { }
        super.onDestroy()
    }
}
