package com.yeji.soundfx

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import kotlin.math.roundToInt

/**
 * 상단바에서 바로 끌 수 있는 음량 바.
 * 알림 안에는 슬라이더를 못 넣지만, '음악 플레이어 카드'의 재생 위치 바는 손가락으로 끌 수 있음
 * → 재생 위치를 음량으로 바꿔서 씀 (전체 10:00 = 100%, 4:06 = 41%)
 */
class VolumeMediaControl(private val ctx: Context) {
    companion object {
        const val ID = 2
        // 10분 = 100% → '분' 자리가 10% 단위 (4:xx = 40%대, 10:00 = 100%), 1%는 6초
        private const val DUR = 10L * 60 * 1000
    }

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val session = MediaSession(ctx, "SoundFxVolume")
    private var lastPct = -1

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onSeekTo(pos: Long) {
                val m = (pos.toFloat() / DUR).coerceIn(0f, 1f)
                EffectService.paramsFlow.value = EffectService.paramsFlow.value.copy(master = m)
            }
        })
        session.isActive = true
    }

    fun update(master: Float, force: Boolean = false) {
        val m = master.coerceIn(0f, 1f)
        val pct = (m * 100).roundToInt()
        if (pct == lastPct && !force) return
        lastPct = pct
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "🔊 이펙터 음량 $pct%")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "바를 좌우로 끌어서 조절")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, DUR)
                .build()
        )
        // 재생 중 + 속도 0 → 바가 저절로 움직이지 않고, 오래 둬도 카드가 사라지지 않음
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_SEEK_TO)
                .setState(PlaybackState.STATE_PLAYING, (m * DUR).toLong(), 0f)
                .build()
        )
        val openPi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val repost = PendingIntent.getService(
            ctx, 11,
            Intent(ctx, EffectService::class.java).setAction(FxNotification.ACTION_REPOST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, FxNotification.CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("🔊 이펙터 음량 $pct%")
            .setContentText("바를 좌우로 끌어서 조절")
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .setContentIntent(openPi)
            .setDeleteIntent(repost)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        nm.notify(ID, n)
    }

    fun release() {
        try { nm.cancel(ID) } catch (_: Exception) { }
        session.isActive = false
        session.release()
    }
}
