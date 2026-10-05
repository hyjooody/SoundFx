package com.yeji.soundfx

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 상단바에서 여는 슬라이더 패널 — 보던 앱 위에 반투명하게 뜨고, 바깥을 누르면 닫힘 */
class PanelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB69CFF), secondary = Color(0xFF7FD1C7))) {
                PanelScreen(
                    onClose = { finish() },
                    onStop = {
                        startService(Intent(this@PanelActivity, EffectService::class.java).setAction(EffectService.ACTION_STOP))
                        finish()
                    }
                )
            }
        }
    }
}

private val PanelSub = Color(0xFFB8B4CC)

@Composable
fun PanelScreen(onClose: () -> Unit, onStop: () -> Unit) {
    val p by EffectService.paramsFlow.collectAsState()
    val running by EffectService.running.collectAsState()
    val set: (Params) -> Unit = { np -> EffectService.paramsFlow.value = np }
    val scrimSource = remember { MutableInteractionSource() }
    val sheetSource = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .clickable(interactionSource = scrimSource, indication = null) { onClose() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .background(Color(0xFF1B1530), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(interactionSource = sheetSource, indication = null) { }
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎛 소리 이펙터", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (running) TextButton(onClick = onStop) { Text("이펙터 끄기", color = Color(0xFFE57373)) }
                TextButton(onClick = onClose) { Text("닫기") }
            }
            if (!running) Text("이펙터가 꺼져 있어요. 앱에서 시작을 눌러 주세요.", color = PanelSub, fontSize = 13.sp)

            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                // 전체 음량
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("🔊", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("전체 음량", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${(p.master * 100).roundToInt()}%", color = PanelSub, fontSize = 13.sp)
                }
                Slider(value = p.master, onValueChange = { set(p.copy(master = it)) },
                    valueRange = 0f..1.5f, modifier = Modifier.fillMaxWidth())
                HorizontalDivider(color = Color(0x22FFFFFF))

                Fx.entries.forEach { fx -> PanelRow(fx, p, set) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelRow(fx: Fx, p: Params, set: (Params) -> Unit) {
    val i = fx.ordinal
    val on = p.on[i]
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fx.emoji, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Text(fx.title, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (on) "${(p.amount[i] * 100).roundToInt()}%" else "꺼짐", color = PanelSub, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Switch(checked = on, onCheckedChange = { v -> set(p.withOn(i, v)) })
        }
        // 바를 움직이면 꺼져 있던 이펙트도 자동으로 켜짐
        Slider(
            value = p.amount[i],
            onValueChange = { v -> set(p.withAmount(i, v).withOn(i, true)) },
            modifier = Modifier.fillMaxWidth()
        )
        if (fx == Fx.AMBIENT) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("🌧️ 빗소리", "☕ 카페", "🚗 거리").forEachIndexed { t, label ->
                    FilterChip(
                        selected = p.noiseType == t,
                        onClick = { set(p.copy(noiseType = t).withOn(i, true)) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}
