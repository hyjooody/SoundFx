package com.yeji.soundfx

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.KeyEvent
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
        val fxParams: Params get() = paramsFlow.value
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HIDE = "hide"
        const val ACTION_STOP = "com.yeji.soundfx.STOP"
        private const val SR = 48000
        private const val FRAMES = 1024
        private val HEADSET_TYPES = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        )
    }

    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var loop = false
    private var savedMusic = -1
    private var savedAlarm = -1
    private val scope = MainScope()
    private var hideMode = false
    private var minA = 0
    private var maxA = 15
    private var lastAlarm = -1
    private var ignoreUntil = 0L   // 앱이 직접 음량을 바꾼 직후엔 '음량 버튼 눌림'으로 착각하지 않게
    @Volatile private var softMute = false

    @Volatile private var rebuildTrack = false     // 출력 기기가 바뀌면 오디오 트랙을 새로 만들기
    @Volatile private var toHeadset = false        // 지금 이어폰으로 내보내는 중인지
    @Volatile private var pausedByUnplug = false   // 이어폰 빠져서 일시정지된 상태
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 이어폰/블루투스가 연결되거나 빠질 때 */
    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scheduleRebuild()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (removedDevices?.any { it.type in HEADSET_TYPES } == true) pauseMedia()
            scheduleRebuild()
        }
    }

    /** 블루투스는 연결 직후 바로 준비가 안 될 때가 있어서 두 번에 나눠 다시 연결 */
    private fun scheduleRebuild() {
        mainHandler.postDelayed({ rebuildTrack = true }, 500)
        mainHandler.postDelayed({ rebuildTrack = true }, 2000)
    }

    /** 이어폰이 빠지면 재생 중인 음악 앱을 일시정지 */
    private fun pauseMedia() {
        if (projection == null) return
        val am = getSystemService(AudioManager::class.java)
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (_: Exception) { }
        // 생활 소음·LP 잡음 같은 이펙트 소리도 음악이 다시 나올 때까지 멈춤
        pausedByUnplug = true
    }

    /**
     * 알람 채널은 기본적으로 '스피커 + 이어폰' 동시 재생이라서,
     * 이어폰이 있으면 그 기기로만 나가도록 직접 지정한다.
     */
    private fun applyRoute(t: AudioTrack) {
        if (!hideMode) { toHeadset = false; return }
        val am = getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var target: AudioDeviceInfo? = null
        for (type in HEADSET_TYPES) {
            target = outs.firstOrNull { it.type == type }
            if (target != null) break
        }
        toHeadset = target != null
        if (target == null) target = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        try { t.setPreferredDevice(target) } catch (_: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            // 상단바 알림을 밀어서 지우면 바로 다시 띄움
            FxNotification.ACTION_REPOST -> {
                if (projection != null) getSystemService(NotificationManager::class.java)
                    .notify(FxNotification.ID, FxNotification.build(this, fxParams))
                else stopSelf()
                return START_NOT_STICKY
            }
            FxNotification.ACTION_TOGGLE, FxNotification.ACTION_SET,
            FxNotification.ACTION_NOISE, FxNotification.ACTION_MASTER_SET -> {
                if (projection == null) stopSelf() else handleControl(intent!!)
                return START_NOT_STICKY
            }
        }
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val hide = intent!!.getBooleanExtra(EXTRA_HIDE, true)

        startForeground(FxNotification.ID, FxNotification.build(this, fxParams), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data)
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        hideMode = hide
        if (hide) hideOriginal()
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(deviceCb, Handler(Looper.getMainLooper()))
        startAudio(mp, hide)
        running.value = true

        // 설정이 바뀔 때마다(앱 화면이든 상단바든) 패널을 다시 그림 — 0.15초 디바운스
        val nm = getSystemService(NotificationManager::class.java)
        // 음량 바 ↔ 폰 알람 음량 연동 (바를 움직이면 알람 음량이, 음량 버튼을 누르면 바가 따라 움직임)
        scope.launch { paramsFlow.collect { applyAlarm(it) } }
        scope.launch { while (true) { delay(300); pollAlarm() } }
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
        val lvl = intent.getIntExtra(FxNotification.EXTRA_LEVEL, 0)
        val p = fxParams
        paramsFlow.value = when (intent.action) {
            FxNotification.ACTION_TOGGLE -> p.toggled(i)
            // 바를 탭하면 그 위치로 강도 설정 + 꺼져 있었으면 자동으로 켜짐
            FxNotification.ACTION_SET -> p.withAmount(i, lvl / FxNotification.SEGS.toFloat()).withOn(i, true)
            FxNotification.ACTION_NOISE -> p.copy(noiseType = (p.noiseType + 1) % 3).withOn(Fx.AMBIENT.ordinal, true)
            FxNotification.ACTION_MASTER_SET -> p.copy(master = lvl / 10f)
            else -> p
        }
    }

    /** 미디어 볼륨을 0으로 → 원본은 안 들리고, 이펙트 소리는 알람 채널로 내보냄 */
    private fun hideOriginal() {
        val am = getSystemService(AudioManager::class.java)
        try {
            savedMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            minA = am.getStreamMinVolume(AudioManager.STREAM_ALARM)
            maxA = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val ratio = savedMusic.toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            lastAlarm = -1
            // 음량 바를 원래 미디어 음량 위치로 맞춤 → 켜기 전과 같은 크기로 시작 (0이었으면 무음)
            paramsFlow.value = fxParams.copy(master = ratio)
            applyAlarm(fxParams)
        } catch (_: Exception) { }
    }

    /** 음량 바 값 → 폰 알람 음량 */
    private fun applyAlarm(p: Params) {
        if (!hideMode || savedAlarm < 0) return
        val target = (p.master.coerceIn(0f, 1f) * maxA).roundToInt()
        // 갤럭시는 알람을 0까지 못 내리는 경우가 있어서, 그 아래는 앱에서 직접 무음 처리
        softMute = target <= 0 || target < minA
        val actual = target.coerceIn(minA, maxA)
        if (actual != lastAlarm) {
            val am = getSystemService(AudioManager::class.java)
            try { am.setStreamVolume(AudioManager.STREAM_ALARM, actual, 0) } catch (_: Exception) { }
            // 음량 변경은 시스템에 조금 늦게 반영될 수 있어서, 1초 동안은 바뀐 값을 내 변경으로 간주
            lastAlarm = actual
            ignoreUntil = SystemClock.uptimeMillis() + 1000
        }
    }

    /** 음량 버튼으로 알람 음량이 바뀌면 음량 바도 따라 움직이게 */
    private fun pollAlarm() {
        if (!hideMode || lastAlarm < 0) return
        val v = getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        if (SystemClock.uptimeMillis() < ignoreUntil) { lastAlarm = v; return }
        if (v != lastAlarm) {
            lastAlarm = v
            paramsFlow.value = fxParams.copy(master = v.toFloat() / maxA)
        }
    }

    private fun restoreVolumes() {
        val am = getSystemService(AudioManager::class.java)
        try {
            if (savedMusic >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0)
            if (savedAlarm >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarm, 0)
        } catch (_: Exception) { }
        savedMusic = -1; savedAlarm = -1
        lastAlarm = -1; softMute = false
    }

    private fun buildTrack(hide: Boolean): AudioTrack {
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
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(outFmt)
            .setBufferSizeInBytes(max(minTrack, FRAMES * 8) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
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
                val r = AudioRecord.Builder()
                    .setAudioFormat(inFmt)
                    .setBufferSizeInBytes(max(minRec, FRAMES * 4) * 2)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                rec = r

                val dsp = Dsp(SR)
                val inBuf = ShortArray(FRAMES * 2)
                val fBuf = FloatArray(FRAMES * 2)
                r.startRecording()
                rebuildTrack = true
                while (loop) {
                    // 출력 기기가 바뀌었으면 트랙을 새로 만들어서 새 기기로만 나가게
                    if (rebuildTrack) {
                        rebuildTrack = false
                        track?.let {
                            try { it.pause(); it.flush(); it.stop() } catch (_: Exception) { }
                            it.release()
                        }
                        val t = buildTrack(hide)
                        applyRoute(t)
                        t.play()
                        track = t
                    }
                    val out = track ?: continue

                    val n = r.read(inBuf, 0, inBuf.size)
                    if (n <= 0) continue

                    if (pausedByUnplug) {
                        // 음악이 다시 재생되면(소리가 들어오면) 자동으로 해제
                        var peak = 0
                        for (i in 0 until n) { val v = kotlin.math.abs(inBuf[i].toInt()); if (v > peak) peak = v }
                        if (peak > 200) pausedByUnplug = false
                    }

                    if (pausedByUnplug || softMute) {
                        java.util.Arrays.fill(fBuf, 0, n, 0f)
                    } else {
                        for (i in 0 until n) fBuf[i] = inBuf[i] / 32768f
                        val pp = fxParams
                        // 스피커: 크기는 폰 알람 음량이 담당 → 앱 증폭 1배
                        // 이어폰: 폰 알람 음량이 이어폰엔 안 먹혀서 → 음량 바 값으로 앱에서 직접 조절
                        val gain = when {
                            !hideMode -> pp.master
                            toHeadset -> pp.master.coerceIn(0f, 1f).let { it * it }
                            else -> 1f
                        }
                        dsp.process(fBuf, n / 2, pp.copy(master = gain))
                    }
                    out.write(fBuf, 0, n, AudioTrack.WRITE_BLOCKING)
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
        mainHandler.removeCallbacksAndMessages(null)
        try { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCb) } catch (_: Exception) { }
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
