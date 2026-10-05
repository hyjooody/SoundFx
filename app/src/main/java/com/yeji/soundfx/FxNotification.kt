package com.yeji.soundfx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** 상단바(알림창)에 뜨는 이펙터 조절 패널 */
object FxNotification {
    const val CH = "fx"
    const val ID = 1
    const val ACTION_TOGGLE = "com.yeji.soundfx.TOGGLE"
    const val ACTION_UP = "com.yeji.soundfx.UP"
    const val ACTION_DOWN = "com.yeji.soundfx.DOWN"
    const val ACTION_NOISE = "com.yeji.soundfx.NOISE"
    const val ACTION_MASTER_UP = "com.yeji.soundfx.MASTER_UP"
    const val ACTION_MASTER_DOWN = "com.yeji.soundfx.MASTER_DOWN"
    const val EXTRA_IDX = "idx"
    const val STEP = 0.1f

    private val NOISE_LABELS = listOf("🌧️ 빗소리", "☕ 카페", "🚗 거리")

    private val LBL = intArrayOf(R.id.lbl_0, R.id.lbl_1, R.id.lbl_2, R.id.lbl_3, R.id.lbl_4, R.id.lbl_5)
    private val MINUS = intArrayOf(R.id.minus_0, R.id.minus_1, R.id.minus_2, R.id.minus_3, R.id.minus_4, R.id.minus_5)
    private val PLUS = intArrayOf(R.id.plus_0, R.id.plus_1, R.id.plus_2, R.id.plus_3, R.id.plus_4, R.id.plus_5)
    private val BAR = intArrayOf(R.id.bar_0, R.id.bar_1, R.id.bar_2, R.id.bar_3, R.id.bar_4, R.id.bar_5)
    private val PCT = intArrayOf(R.id.pct_0, R.id.pct_1, R.id.pct_2, R.id.pct_3, R.id.pct_4, R.id.pct_5)
    private val CHIP = intArrayOf(R.id.chip_0, R.id.chip_1, R.id.chip_2, R.id.chip_3, R.id.chip_4, R.id.chip_5)

    private const val ON_TEXT = 0xFFFFFFFF.toInt()
    private const val OFF_TEXT = 0xFF8A8AA0.toInt()

    private fun pi(ctx: Context, action: String, code: Int, idx: Int = 0): PendingIntent =
        PendingIntent.getService(
            ctx, code * 16 + idx,
            Intent(ctx, EffectService::class.java).setAction(action).putExtra(EXTRA_IDX, idx),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun label(fx: Fx, p: Params) =
        if (fx == Fx.AMBIENT) NOISE_LABELS[p.noiseType] else "${fx.emoji} ${fx.title}"

    fun build(ctx: Context, p: Params): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "이펙터 조절 패널", NotificationManager.IMPORTANCE_LOW))

        val stopPi = pi(ctx, EffectService.ACTION_STOP, 1)

        // 접힌 상태
        val small = RemoteViews(ctx.packageName, R.layout.notif_small)
        for (fx in Fx.entries) {
            val i = fx.ordinal
            small.setTextViewText(CHIP[i], fx.emoji)
            small.setInt(CHIP[i], "setBackgroundResource", if (p.on[i]) R.drawable.pill_on else R.drawable.pill_off)
            small.setOnClickPendingIntent(CHIP[i], pi(ctx, ACTION_TOGGLE, 2, i))
        }
        small.setOnClickPendingIntent(R.id.chip_stop, stopPi)

        // 펼친 상태
        val big = RemoteViews(ctx.packageName, R.layout.notif_big)
        for (fx in Fx.entries) {
            val i = fx.ordinal
            val on = p.on[i]
            val pct = (p.amount[i] * 100).roundToInt()
            big.setTextViewText(LBL[i], label(fx, p))
            big.setTextColor(LBL[i], if (on) ON_TEXT else OFF_TEXT)
            big.setInt(LBL[i], "setBackgroundResource", if (on) R.drawable.pill_on else R.drawable.pill_off)
            big.setProgressBar(BAR[i], 100, pct, false)
            big.setTextViewText(PCT[i], "$pct%")
            big.setOnClickPendingIntent(LBL[i], pi(ctx, ACTION_TOGGLE, 2, i))
            big.setOnClickPendingIntent(MINUS[i], pi(ctx, ACTION_DOWN, 3, i))
            big.setOnClickPendingIntent(PLUS[i], pi(ctx, ACTION_UP, 4, i))
        }
        big.setOnClickPendingIntent(R.id.noise_type, pi(ctx, ACTION_NOISE, 5))
        val mPct = (p.master * 100).roundToInt()
        big.setProgressBar(R.id.master_bar, 150, mPct, false)
        big.setTextViewText(R.id.master_pct, "$mPct%")
        big.setOnClickPendingIntent(R.id.master_minus, pi(ctx, ACTION_MASTER_DOWN, 6))
        big.setOnClickPendingIntent(R.id.master_plus, pi(ctx, ACTION_MASTER_UP, 7))

        val openPi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("소리 이펙터")
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null as Icon?, "■ 이펙터 끄기", stopPi).build())
            .build()
    }
}
