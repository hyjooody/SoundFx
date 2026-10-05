package com.yeji.soundfx

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/** 폰에서 재생되는 소리를 캡처 → 이펙트 → 다시 재생하는 포그라운드 서비스 */
class EffectService : Service() {
    companion object {
        val running = MutableStateFlow(false)
        /** 앱 화면과 상단바 패널이 같이 보는 설정값 */
        val paramsFlow = MutableStateFlow(Params())
        val params: Params get() = paramsFlow.value
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HIDE = "hide"
        const val ACTION_STOP = "com.yeji.soundfx.STOP"
        private const val SR = 48000
        private const val FRAMES = 1024
    }

    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var loop = false
    private var savedMusic = -1
    private var savedAlarm = -1
    private val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            FxNotification.ACTION_TOGGLE, FxNotification.ACTION_UP, FxNotification.ACTION_DOWN,
            FxNotification.ACTION_NOISE, FxNotification.ACTION_MASTER_UP, FxNotification.ACTION_MASTER_DOWN -> {
                if (projection == null) stopSelf() else handleControl(intent)
                return START_NOT_STICKY
            }
        }
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val hide = intent!!.getBooleanExtra(EXTRA_HIDE, true)

        startForeground(FxNotification.ID, FxNotification.build(this, params), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data)
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        if (hide) hideOriginal()
        startAudio(mp, hide)
        running.value = true

        // 설정이 바뀔 때마다(앱 화면이든 상단바든) 패널을 다시 그림 — 0.15초 디바운스
        val nm = getSystemService(NotificationManager::class.java)
        scope.launch {
            paramsFlow.collectLatest {
                delay(150)
                if (projection != null) nm.notify(FxNotification.ID, FxNotification.build(this@EffectService, it))
            }
        }
        return START_NOT_STICKY
    }

    /** 상단바 패널 버튼 처리 */
    private fun handleControl(intent: Intent) {
        val i = intent.getIntExtra(FxNotification.EXTRA_IDX, 0)
        val p = params
        val step = FxNotification.STEP
        paramsFlow.value = when (intent.action) {
            FxNotification.ACTION_TOGGLE -> p.toggled(i)
            // +/- 누르면 꺼져 있던 이펙트는 자동으로 켜짐
            FxNotification.ACTION_UP -> p.withAmount(i, p.amount[i] + step).withOn(i, true)
            FxNotification.ACTION_DOWN -> p.withAmount(i, p.amount[i] - step).withOn(i, true)
            FxNotification.ACTION_NOISE -> p.copy(noiseType = (p.noiseType + 1) % 3).withOn(Fx.AMBIENT.ordinal, true)
            FxNotification.ACTION_MASTER_UP -> p.copy(master = (p.master + step).coerceAtMost(1.5f))
            FxNotification.ACTION_MASTER_DOWN -> p.copy(master = (p.master - step).coerceAtLeast(0f))
            else -> p
        }
    }

    /** 미디어 볼륨을 0으로 → 원본은 안 들리고, 이펙트 소리는 알람 채널로 내보냄 */
    private fun hideOriginal() {
        val am = getSystemService(AudioManager::class.java)
        try {
            savedMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val ratio = savedMusic.toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val alarmVol = max(1, (ratio * am.getStreamMaxVolume(AudioManager.STREAM_ALARM)).roundToInt())
            am.setStreamVolume(AudioManager.STREAM_ALARM, alarmVol, 0)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: Exception) { }
    }

    private fun restoreVolumes() {
        val am = getSystemService(AudioManager::class.java)
        try {
            if (savedMusic >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0)
            if (savedAlarm >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarm, 0)
        } catch (_: Exception) { }
        savedMusic = -1; savedAlarm = -1
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(mp: MediaProjection, hide: Boolean) {
        loop = true
        worker = thread(name = "fx-audio") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var rec: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val inFmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minRec = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord.Builder()
                    .setAudioFormat(inFmt)
                    .setBufferSizeInBytes(max(minRec, FRAMES * 4) * 2)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()

                val attrs = AudioAttributes.Builder()
                    .setUsage(if (hide) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val outFmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                val minTrack = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(outFmt)
                    .setBufferSizeInBytes(max(minTrack, FRAMES * 8) * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                val dsp = Dsp(SR)
                val inBuf = ShortArray(FRAMES * 2)
                val fBuf = FloatArray(FRAMES * 2)
                rec.startRecording()
                track.play()
                while (loop) {
                    val n = rec.read(inBuf, 0, inBuf.size)
                    if (n <= 0) continue
                    for (i in 0 until n) fBuf[i] = inBuf[i] / 32768f
                    dsp.process(fBuf, n / 2, params)
                    track.write(fBuf, 0, n, AudioTrack.WRITE_BLOCKING)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { stopSelf() }
            } finally {
                try { rec?.stop() } catch (_: Exception) { }
                rec?.release()
                try { track?.stop() } catch (_: Exception) { }
                track?.release()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        loop = false
        try { worker?.join(800) } catch (_: Exception) { }
        worker = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        restoreVolumes()
        running.value = false
        super.onDestroy()
    }
}
