package com.yeji.soundfx

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        /** 소리 없이 10분 지나면 자동으로 끄기 (앱에서 켜고 끔) */
        val autoOff = MutableStateFlow(true)
        private const val AUTO_OFF_MS = 10L * 60 * 1000
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
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        )
        /** 앱 화면에 보여줄 현재 상태 (문제 확인용) */
        val status = MutableStateFlow("")
    }

    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var loop = false
    private var musicMuted = false
    private var a11yMode = false     // 접근성 음량 채널로 내보내는 모드 (이어폰 문제 없는 방식)
    private var maxM = 15
    private var lastMusicIdx = -1
    private var volReceiverOn = false
    @Volatile private var trackA11y = false   // 지금 트랙이 접근성 통로인지 (이어폰일 때만 접근성, 스피커는 알람)
    private val savedA11y = HashMap<String, Int>()   // 기기별 원래 접근성 음량 (끌 때 되돌리기용)
    private var receiverOn = false
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
    @Volatile private var pausedByUnplug = false
    @Volatile private var silentMs = 0L      // 원본 소리가 안 들어온 시간
    @Volatile private var routedInfo = "-"   // 트랙이 실제로 나가고 있는 기기 (확인용)   // 이어폰 빠져서 일시정지된 상태
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
        remute()
        mainHandler.postDelayed({ remute(); boostA11y(); rebuildTrack = true }, 500)
        mainHandler.postDelayed({ remute(); rebuildTrack = true }, 2000)
        mainHandler.postDelayed({ remute(); rebuildTrack = true }, 4000)
    }

    /** 기기가 바뀔 때 시스템이 음소거를 풀어버리는 경우가 있어서 다시 걸어 둠 */
    private fun remute() {
        if (!musicMuted) return
        try {
            getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) { }
    }

    /** 이어폰이 빠지기 직전에 시스템이 보내는 신호 → 바로 일시정지 */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseMedia()
        }
    }

    /** 이어폰이 빠지면 재생 중인 음악 앱을 일시정지 */
    private fun pauseMedia() {
        if (projection == null) return
        val am = getSystemService(AudioManager::class.java)
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (_: Exception) { }
        // 음악 앱들은 '오디오 포커스'를 뺏기면 스스로 멈춤 → 잠깐 가져왔다가 돌려줌
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
            am.requestAudioFocus(req)
            mainHandler.postDelayed({
                try { am.abandonAudioFocusRequest(req) } catch (_: Exception) { }
            }, 400)
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
        if (trackA11y) { toHeadset = true; return }   // 접근성 통로는 시스템이 음악처럼 알아서 연결
        val headset = findHeadset()
        toHeadset = headset != null
        val target = headset ?: getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        try { t.setPreferredDevice(target) } catch (_: Exception) { }
    }

    /** 0~1 음량 바 → 실제 크기 (폰 음량 버튼처럼 데시벨 곡선) */
    private fun volCurve(m: Float): Float {
        val x = m.coerceIn(0f, 1f)
        if (x <= 0.001f) return 0f
        return Math.pow(10.0, -48.0 * (1.0 - x) / 20.0).toFloat()
    }

    private fun findHeadset(): AudioDeviceInfo? {
        val outs = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (type in HEADSET_TYPES) {
            val d = outs.firstOrNull { it.type == type }
            if (d != null) return d
        }
        return null
    }

    /**
     * 연결 신호를 놓치는 경우가 있어서 0.3초마다 직접 확인:
     * 이어폰이 새로 생기거나 없어지면 출력 다시 연결, 빠지면 일시정지
     */
    private var lastHeadset: Boolean? = null
    private fun checkDevices() {
        val hs = findHeadset()
        val now = hs != null
        val prev = lastHeadset
        lastHeadset = now
        if (prev != null && prev != now) {
            if (prev && !now) pauseMedia()
            scheduleRebuild()
        }
        val am = getSystemService(AudioManager::class.java)
        val gainPct = when {
            !hideMode -> (fxParams.master * 100).roundToInt()
            trackA11y || toHeadset -> (volCurve(fxParams.master) * 100).roundToInt()
            else -> 100
        }
        status.value = buildString {
            append("모드: ").append(if (a11yMode) "이어폰 출력 모드(접근성)" else if (hideMode) "알람 채널" else "겹쳐 듣기").append("\n")
            append("출력: ").append(if (hs != null) "이어폰 (${hs.productName}, 종류 ${hs.type})" else "스피커")
            append("\n트랙: ").append(if (trackA11y) "접근성" else if (hideMode) "알람" else "미디어")
            append(" → ").append(if (toHeadset) "이어폰" else "스피커")
            append(" · 원본 음소거: ").append(if (am.isStreamMute(AudioManager.STREAM_MUSIC)) "O" else "X")
            append("\n미디어 ").append(am.getStreamVolume(AudioManager.STREAM_MUSIC))
            append(" · 알람 ").append(am.getStreamVolume(AudioManager.STREAM_ALARM)).append("/").append(maxA)
            append(" · 접근성 ").append(am.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
            append(" · 앱 증폭 ").append(gainPct).append("%")
            if (pausedByUnplug) append(" · 일시정지됨")
            if (autoOff.value && silentMs >= 60_000L)
                append("\n소리 없음 ").append(silentMs / 60_000L).append("분 · 10분 되면 자동으로 꺼져요")
            append("\n실제 출력: ").append(routedInfo)
            append(" · 접근성 음량 ").append(am.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY))
            append("/").append(am.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY))
            append(if (am.isStreamMute(AudioManager.STREAM_ACCESSIBILITY)) "(음소거)" else "")
            append("\n알람 음소거: ").append(if (am.isStreamMute(AudioManager.STREAM_ALARM)) "O" else "X")
            append(" · 방해금지: ").append(
                when (getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
                    NotificationManager.INTERRUPTION_FILTER_ALL -> "꺼짐"
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "켜짐(중요만)"
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "켜짐(알람만)"
                    NotificationManager.INTERRUPTION_FILTER_NONE -> "켜짐(완전 무음)"
                    else -> "?"
                }
            )
            append(" · 바 ").append((fxParams.master * 100).roundToInt()).append("%")
        }
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
        a11yMode = hide && FxA11yService.on.value
        if (hide) hideOriginal()
        if (a11yMode) {
            val vf = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(volReceiver, vf, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(volReceiver, vf)
            volReceiverOn = true
        }
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(deviceCb, Handler(Looper.getMainLooper()))
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisyReceiver, noisyFilter)
        receiverOn = true
        startAudio(mp, hide)
        running.value = true

        // 설정이 바뀔 때마다(앱 화면이든 상단바든) 패널을 다시 그림 — 0.15초 디바운스
        val nm = getSystemService(NotificationManager::class.java)
        // 음량 바 ↔ 폰 알람 음량 연동 (바를 움직이면 알람 음량이, 음량 버튼을 누르면 바가 따라 움직임)
        scope.launch { paramsFlow.collect { applyAlarm(it) } }
        scope.launch { while (true) { delay(300); pollAlarm(); checkMusicVolume(); checkDevices() } }
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
            val music = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            maxM = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            lastMusicIdx = music
            val ratio = music.toFloat() / maxM
            // 미디어는 음량을 건드리지 않고 '음소거'만 → 어느 기기든 원본이 안 들리고, 끄면 원래 음량 그대로
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            musicMuted = true
            // 스피커로 나갈 땐 알람 통로를 쓰니까 알람 음량은 항상 관리 (끄면 복구)
            savedAlarm = am.getStreamVolume(AudioManager.STREAM_ALARM)
            minA = am.getStreamMinVolume(AudioManager.STREAM_ALARM)
            maxA = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            lastAlarm = -1
            // 이어폰으로 나갈 땐 접근성 통로 → 최대로 열어두고 크기는 음량 바로 앱에서 조절
            if (a11yMode) boostA11y()
            // 음량 바를 원래 미디어 음량 위치로 → 켜기 전과 같은 크기로 시작 (0이었으면 무음)
            paramsFlow.value = fxParams.copy(master = ratio)
            applyAlarm(fxParams)
        } catch (_: Exception) { }
    }

    private fun boostA11y() {
        if (!a11yMode) return
        val am = getSystemService(AudioManager::class.java)
        try {
            // 이 기기의 원래 값을 처음 한 번만 기억 (예전에 못 되돌린 값이 남아 있으면 그게 진짜 원래 값)
            val key = VolumeRestore.key(am)
            if (!savedA11y.containsKey(key)) {
                savedA11y[key] = VolumeRestore.takePending(this, key)
                    ?: am.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
            }
            am.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, am.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY), 0)
        } catch (_: Exception) { }
    }

    /**
     * 접근성 모드에서 음량 버튼을 누르면 미디어 음량이 바뀌면서 음소거가 풀림
     * → 바뀐 미디어 음량을 음량 바에 반영하고 바로 다시 음소거
     */
    private fun checkMusicVolume() {
        if (!a11yMode || !musicMuted) return
        val am = getSystemService(AudioManager::class.java)
        try {
            val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
            if (v != lastMusicIdx) {
                lastMusicIdx = v
                paramsFlow.value = fxParams.copy(master = v.toFloat() / maxM)
            }
            if (!muted) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) { }
    }

    private val volReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { checkMusicVolume() }
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
            if (musicMuted) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            if (savedAlarm >= 0) am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarm, 0)
            // 접근성 음량: 지금 연결된 기기는 바로 되돌리고, 나머지는 다시 연결될 때 되돌리도록 기억
            if (savedA11y.isNotEmpty()) {
                val cur = VolumeRestore.key(am)
                for ((k, v) in savedA11y) {
                    if (k == cur) am.setStreamVolume(AudioManager.STREAM_ACCESSIBILITY, v, 0)
                    else VolumeRestore.savePending(this, k, v)
                }
                savedA11y.clear()
            }
        } catch (_: Exception) { }
        musicMuted = false; savedAlarm = -1
        lastAlarm = -1; softMute = false
    }

    private fun buildTrack(hide: Boolean): AudioTrack {
        // 이어폰: 접근성 통로 (알람은 갤럭시가 스피커로도 내보내서 안 됨)
        // 스피커: 알람 통로 (스피커만 쓸 땐 문제없고 확실하게 소리 남)
        val useA11y = hide && a11yMode && findHeadset() != null
        trackA11y = useA11y
        val attrs = AudioAttributes.Builder()
            .setUsage(
                when {
                    !hide -> AudioAttributes.USAGE_MEDIA
                    useA11y -> AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                    else -> AudioAttributes.USAGE_ALARM
                }
            )
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
                var routeTick = 0
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

                    // 약 0.5초마다 '실제로' 어디로 나가는지 확인
                    // 유선(USB) 이어폰은 꽂고 나서 준비되는 데 몇 초 걸려서, 그 전에 연결하면 스피커로 새어 나감
                    // → 원하는 기기와 실제 출력이 다르면 다시 연결
                    routeTick++
                    if (routeTick % 25 == 0) {
                        routedInfo = out.routedDevice?.let { "${it.productName}(${it.type})" } ?: "없음"
                    }
                    if (hideMode && !trackA11y && routeTick % 25 == 0) {
                        val want = findHeadset()
                        val routed = out.routedDevice
                        val wrong = if (want != null) routed == null || routed.id != want.id
                                    else routed != null && routed.type in HEADSET_TYPES
                        if (wrong) rebuildTrack = true
                    }

                    val n = r.read(inBuf, 0, inBuf.size)
                    if (n <= 0) continue

                    var peak = 0
                    for (i in 0 until n) { val v = kotlin.math.abs(inBuf[i].toInt()); if (v > peak) peak = v }
                    // 음악이 다시 재생되면(소리가 들어오면) 일시정지 해제
                    if (pausedByUnplug && peak > 200) pausedByUnplug = false
                    // 무음 시간 재기 → 10분 넘으면 자동으로 끄기 (끄면 알람·미디어 음량도 원래대로)
                    if (peak > 200) silentMs = 0L else silentMs += (n / 2) * 1000L / SR
                    if (autoOff.value && silentMs >= AUTO_OFF_MS && loop) {
                        loop = false
                        mainHandler.post { stopSelf() }
                        break
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
                            trackA11y || toHeadset -> volCurve(pp.master)
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
        if (receiverOn) { try { unregisterReceiver(noisyReceiver) } catch (_: Exception) { }; receiverOn = false }
        if (volReceiverOn) { try { unregisterReceiver(volReceiver) } catch (_: Exception) { }; volReceiverOn = false }
        loop = false
        try { worker?.join(800) } catch (_: Exception) { }
        worker = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        restoreVolumes()
        running.value = false
        status.value = ""
        super.onDestroy()
    }
}
