package com.yeji.soundfx

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 접근성 음량은 스피커·이어폰마다 따로 저장돼서,
 * 작동 중에 기기가 바뀌었으면 끌 때 지금 연결 안 된 기기는 바로 되돌릴 수가 없음.
 * → 기억해 뒀다가 그 기기가 다시 연결되는 순간 원래 값으로 되돌림.
 */
object VolumeRestore {
    private const val PREF = "a11y_volume_restore"
    private val HEADSET_TYPES = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    )

    /** 지금 소리가 나가는 기기 이름표 (스피커 / 이어폰 종류별) */
    fun key(am: AudioManager): String {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (t in HEADSET_TYPES) if (outs.any { it.type == t }) return "hs_$t"
        return "speaker"
    }

    fun savePending(ctx: Context, key: String, idx: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(key, idx).apply()
    }

    /** 아직 못 되돌린 값이 있으면 꺼내고 지움 */
    fun takePending(ctx: Context, key: String): Int? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains(key)) return null
        val v = p.getInt(key, 0)
        p.edit().remove(key).apply()
        return v
    }

    /** 이펙터가 꺼져 있을 때, 지금 기기에 못 되돌린 값이 있으면 되돌림 */
    fun restorePendingForCurrent(ctx: Context) {
        if (EffectService.running.value) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val v = takePending(ctx, key(am)) ?: return
        try { am.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, v, 0) } catch (_: Exception) { }
    }
}
