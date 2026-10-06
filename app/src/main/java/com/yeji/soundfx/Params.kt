package com.yeji.soundfx

enum class Fx(val title: String, val emoji: String, val desc: String) {
    REVERB("울림", "🏛️", "방이나 홀에서 듣는 듯한 잔향"),
    ECHO("에코", "🏔️", "소리가 좌우로 메아리치며 반복돼요"),
    MUFFLE("먹먹함", "🚪", "옆방이나 벽 너머에서 들리는 느낌"),
    LOFI("저음질", "📻", "비트·샘플레이트를 깎아서 낡은 기기처럼"),
    AMBIENT("생활 소음", "🌧️", "빗소리 · 카페 · 거리 소음을 섞어요"),
    VINYL("LP 잡음", "💿", "턴테이블의 지글거림과 틱 소리")
}

/** UI → 오디오 스레드로 넘기는 설정값 (불변 객체를 통째로 교체) */
data class Params(
    val on: List<Boolean> = List(Fx.entries.size) { false },
    val amount: List<Float> = List(Fx.entries.size) { 0.5f },
    val noiseType: Int = 0,      // 0 빗소리, 1 카페, 2 거리
    val master: Float = 1f
)

fun Params.toggled(i: Int) = copy(on = on.toMutableList().also { it[i] = !it[i] })
fun Params.withOn(i: Int, v: Boolean) = copy(on = on.toMutableList().also { it[i] = v })
fun Params.withAmount(i: Int, v: Float) = copy(amount = amount.toMutableList().also { it[i] = v.coerceIn(0f, 1f) })

/** 화면에 표시되는 버전 — 최신 APK가 깔렸는지 확인용 */
const val APP_VERSION = "v2.6"
