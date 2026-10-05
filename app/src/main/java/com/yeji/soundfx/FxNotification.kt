package com.yeji.soundfx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.widget.RemoteViews

/** 상단바 알림: 이펙트 켜고 끄기 + 슬라이더 패널 열기 */
object FxNotification {
    const val CH = "fx"
    const val ID = 1
    const val ACTION_TOGGLE = "com.yeji.soundfx.TOGGLE"
    const val ACTION_SET = "com.yeji.soundfx.SET"
    const val ACTION_NOISE = "com.yeji.soundfx.NOISE"
    const val ACTION_MASTER_SET = "com.yeji.soundfx.MASTER_SET"
    const val ACTION_REPOST = "com.yeji.soundfx.REPOST"
    const val EXTRA_IDX = "idx"
    const val EXTRA_LEVEL = "level"
    const val SEGS = 10

    private val NOISE_EMOJI = listOf("🌧️", "☕", "🚗")
    private val CHIP = intArrayOf(R.id.chip_0, R.id.chip_1, R.id.chip_2, R.id.chip_3, R.id.chip_4, R.id.chip_5)

    private fun servicePi(ctx: Context, action: String, code: Int, idx: Int = 0): PendingIntent =
        PendingIntent.getService(
            ctx, code * 1000 + idx,
            Intent(ctx, EffectService::class.java).setAction(action).putExtra(EXTRA_IDX, idx),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun build(ctx: Context, p: Params): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "이펙터 조절 패널", NotificationManager.IMPORTANCE_LOW))

        val stopPi = servicePi(ctx, EffectService.ACTION_STOP, 1)
        // 패널은 액티비티라서 누르면 상단바가 자동으로 접히고, 보던 앱 위에 슬라이더 창이 뜸
        val panelPi = PendingIntent.getActivity(
            ctx, 9,
            Intent(ctx, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val small = RemoteViews(ctx.packageName, R.layout.notif_small)
        for (fx in Fx.entries) {
            val i = fx.ordinal
            small.setTextViewText(CHIP[i], if (fx == Fx.AMBIENT) NOISE_EMOJI[p.noiseType] else fx.emoji)
            small.setInt(CHIP[i], "setBackgroundResource", if (p.on[i]) R.drawable.pill_on else R.drawable.pill_off)
            small.setOnClickPendingIntent(CHIP[i], servicePi(ctx, ACTION_TOGGLE, 2, i))
        }
        small.setOnClickPendingIntent(R.id.chip_panel, panelPi)
        small.setOnClickPendingIntent(R.id.chip_stop, stopPi)

        return Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("소리 이펙터")
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setContentIntent(panelPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setDeleteIntent(servicePi(ctx, ACTION_REPOST, 8))
            .addAction(Notification.Action.Builder(null as Icon?, "🎛 슬라이더 패널", panelPi).build())
            .addAction(Notification.Action.Builder(null as Icon?, "■ 이펙터 끄기", stopPi).build())
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }
}
