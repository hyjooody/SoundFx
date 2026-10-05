package com.yeji.soundfx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var hideOriginal = true

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val i = Intent(this, EffectService::class.java)
                .putExtra(EffectService.EXTRA_CODE, r.resultCode)
                .putExtra(EffectService.EXTRA_DATA, r.data)
                .putExtra(EffectService.EXTRA_HIDE, hideOriginal)
            startForegroundService(i)
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) launchProjection()
        else Toast.makeText(this, "소리를 가져오려면 오디오 권한이 필요해요", Toast.LENGTH_LONG).show()
    }

    private fun start(hide: Boolean) {
        hideOriginal = hide
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun launchProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        projLauncher.launch(intent)
    }

    private fun stop() {
        startService(Intent(this, EffectService::class.java).setAction(EffectService.ACTION_STOP))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EffectService.autoOff.value = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_off", true)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB69CFF), secondary = Color(0xFF7FD1C7))) {
                FxScreen(onStart = { start(it) }, onStop = { stop() })
            }
        }
    }
}

private val TextSub = Color(0xFFB8B4CC)

@Composable
fun FxScreen(onStart: (Boolean) -> Unit, onStop: () -> Unit) {
    val running by EffectService.running.collectAsState()
    // 상단바에서 바꾼 값도 화면에 바로 반영
    val p by EffectService.paramsFlow.collectAsState()
    val set: (Params) -> Unit = { np -> EffectService.paramsFlow.value = np }
    var hide by rememberSaveable { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1530), Color(0xFF0E1A24))))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("소리 이펙터", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(APP_VERSION, fontSize = 14.sp, color = TextSub, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(
            if (running) "● 작동 중 — 폰에서 나는 소리에 이펙트가 걸리고 있어요" else "○ 꺼져 있어요",
            color = if (running) Color(0xFF7FD1C7) else TextSub, fontSize = 14.sp
        )
        val dbg by EffectService.status.collectAsState()
        if (running && dbg.isNotEmpty()) {
            Text(dbg, color = TextSub, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Button(
            onClick = { if (running) onStop() else onStart(hide) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Color(0xFFE57373) else MaterialTheme.colorScheme.primary
            )
        ) { Text(if (running) "정지" else "시작", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2A))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("원본 소리 숨기기", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "켜면 원래 소리는 안 들리고 이펙트 걸린 소리만 들려요. (시작 전에만 변경 가능)",
                        color = TextSub, fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = hide, onCheckedChange = { hide = it }, enabled = !running)
            }
        }

        val a11yOn by FxA11yService.on.collectAsState()
        val ctx = LocalContext.current
        Card(colors = CardDefaults.cardColors(containerColor = if (a11yOn) Color(0xFF1E2A26) else Color(0xFF2A1E22))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🎧 이어폰 출력 모드  " + if (a11yOn) "켜짐 ✓" else "꺼짐",
                    color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    if (a11yOn) "이펙트 소리가 일반 음악처럼 나가서, 이어폰을 꽂으면 이어폰으로만 나와요."
                    else "켜면 이어폰을 꽂았을 때 폰 스피커에서도 소리가 나는 문제가 사라져요. " +
                        "접근성 → 설치된 앱 → '소리 이펙터 (이어폰 출력 모드)'를 켜 주세요. " +
                        "회색으로 안 눌리면: 설정 → 애플리케이션 → 소리 이펙터 → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 시도.",
                    color = TextSub, fontSize = 12.sp, lineHeight = 17.sp
                )
                if (!a11yOn) {
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("접근성 설정 열기") }
                }
                if (running) Text("※ 바꾼 경우 이펙터를 정지했다가 다시 시작해야 적용돼요.", color = TextSub, fontSize = 11.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2A))) {
            Column(Modifier.padding(16.dp)) {
                Text("🔊 음량  ${(p.master.coerceIn(0f, 1f) * 100).roundToInt()}%", color = Color.White, fontWeight = FontWeight.Bold)
                Text("작동 중엔 이 음량 바로만 조절하세요. 폰 옆 음량 버튼은 정확하지 않아서 쓰지 않는 게 좋아요. " +
                    "상단바 알림의 🔊 버튼을 누르면 음량 바만 바로 열려요. 끄면 원래 음량으로 돌아가요.",
                    color = TextSub, fontSize = 12.sp, lineHeight = 17.sp)
                Slider(value = p.master.coerceIn(0f, 1f), onValueChange = { set(p.copy(master = it)) }, valueRange = 0f..1f)
            }
        }

        // 자동 끄기 (잠들었을 때 알람 걱정 방지)
        val autoOff by EffectService.autoOff.collectAsState()
        val ctx2 = LocalContext.current
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2A))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("😴 소리 없으면 10분 뒤 자동으로 끄기", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("음악이 멈춘 채로 10분이 지나면 이펙터가 꺼지고 알람·미디어 음량이 원래대로 돌아가요. 켜 놓고 잠들어도 아침 알람이 정상으로 울려요.",
                        color = TextSub, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = autoOff, onCheckedChange = { v ->
                    EffectService.autoOff.value = v
                    ctx2.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("auto_off", v).apply()
                })
            }
        }

        Fx.entries.forEach { fx -> FxCard(fx, p, set) }

        // 전체 초기화: 모든 이펙트 끄기 + 강도 50% + 빗소리 (음량은 유지)
        var askReset by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { askReset = true },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE57373))
        ) { Text("↺ 전체 이펙터 초기화", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        if (askReset) {
            AlertDialog(
                onDismissRequest = { askReset = false },
                title = { Text("전체 초기화") },
                text = { Text("모든 이펙트를 끄고 강도를 50%로 되돌릴까요? (음량은 그대로예요)") },
                confirmButton = {
                    TextButton(onClick = { set(Params(master = p.master)); askReset = false }) {
                        Text("초기화", color = Color(0xFFE57373))
                    }
                },
                dismissButton = { TextButton(onClick = { askReset = false }) { Text("취소") } }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FxCard(fx: Fx, p: Params, set: (Params) -> Unit) {
    val i = fx.ordinal
    val on = p.on[i]
    val amt = p.amount[i]
    Card(colors = CardDefaults.cardColors(containerColor = if (on) Color(0xFF2E2650) else Color(0xFF1E1E2A))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fx.emoji, fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(fx.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(fx.desc, color = TextSub, fontSize = 13.sp)
                }
                Switch(checked = on, onCheckedChange = { v ->
                    set(p.withOn(i, v))
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = amt,
                    onValueChange = { v -> set(p.withAmount(i, v)) },
                    enabled = on,
                    modifier = Modifier.weight(1f)
                )
                Text("${(amt * 100).roundToInt()}%", color = if (on) Color.White else TextSub,
                    modifier = Modifier.width(52.dp), fontSize = 14.sp)
            }
            if (fx == Fx.AMBIENT) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("🌧️ 빗소리", "☕ 카페", "🚗 거리").forEachIndexed { t, label ->
                        FilterChip(
                            selected = p.noiseType == t,
                            onClick = { set(p.copy(noiseType = t)) },
                            label = { Text(label) },
                            enabled = on
                        )
                    }
                }
            }
        }
    }
}
