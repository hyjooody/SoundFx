package com.yeji.soundfx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
        Text("소리 이펙터", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            if (running) "● 작동 중 — 폰에서 나는 소리에 이펙트가 걸리고 있어요" else "○ 꺼져 있어요",
            color = if (running) Color(0xFF7FD1C7) else TextSub, fontSize = 14.sp
        )
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
                        "켜면 원래 소리는 안 들리고 이펙트 걸린 소리만 들려요. 작동 중엔 음량 버튼이 이펙트 소리 크기를 조절해요. (시작 전에만 변경 가능)",
                        color = TextSub, fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = hide, onCheckedChange = { hide = it }, enabled = !running)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2A))) {
            Column(Modifier.padding(16.dp)) {
                Text("🔊 전체 음량  ${(p.master * 100).roundToInt()}%", color = Color.White, fontWeight = FontWeight.Bold)
                Slider(value = p.master, onValueChange = { set(p.copy(master = it)) }, valueRange = 0f..1.5f)
            }
        }

        Fx.entries.forEach { fx -> FxCard(fx, p, set) }
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
