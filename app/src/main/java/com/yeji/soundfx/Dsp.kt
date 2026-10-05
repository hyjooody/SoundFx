package com.yeji.soundfx

import kotlin.math.*

/** 스테레오 interleaved float 버퍼에 이펙트 체인을 거는 엔진 */
class Dsp(private val sr: Int) {
    private val rnd = java.util.Random()
    private val cur = FloatArray(Fx.entries.size)   // 부드럽게 따라가는 현재 강도
    private val s = FloatArray(2)

    private fun noise() = rnd.nextFloat() * 2f - 1f
    private fun coef(fc: Float) = 1f - exp(-2f * PI.toFloat() * fc / sr)

    // 먹먹함
    private val m1 = FloatArray(2); private val m2 = FloatArray(2)
    // 저음질
    private val held = FloatArray(2); private var holdPhase = 0f; private val lofiLp = FloatArray(2)
    // 에코 (핑퐁)
    private val echoLen = (sr * 0.36f).toInt()
    private val echoBuf = Array(2) { FloatArray(echoLen) }
    private var echoPos = 0
    private val echoDamp = FloatArray(2)
    // 울림 (Freeverb 계열)
    private val scale = sr / 44100f
    private val combT = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apT = intArrayOf(556, 441, 341, 225)
    private val combs = Array(2) { ch -> Array(combT.size) { Comb(((combT[it] + ch * 23) * scale).toInt()) } }
    private val aps = Array(2) { ch -> Array(apT.size) { Allpass(((apT[it] + ch * 23) * scale).toInt()) } }
    // 생활 소음
    private val pk = Array(2) { FloatArray(3) }
    private val brown = FloatArray(2)
    private val ambLp = FloatArray(2); private val ambLp2 = FloatArray(2); private val ambLp3 = FloatArray(2)
    private val drop = FloatArray(2)
    private var lfo = 0.5f; private var lfoTarget = 0.5f
    private var clinkEnv = 0f; private var clinkPhase = 0f; private var clinkInc = 0f; private var clinkPan = 0.5f
    private var carT = -1f; private var carDur = 1f; private var carPan = 0.5f
    private val c200 = coef(200f); private val c500 = coef(500f); private val c600 = coef(600f)
    private val c800 = coef(800f); private val c3000 = coef(3000f)
    // LP 잡음
    private var pop = 0f
    private val hissLp = FloatArray(2)

    private fun pink(ch: Int, w: Float): Float {
        val b = pk[ch]
        b[0] = 0.99765f * b[0] + w * 0.0990460f
        b[1] = 0.96300f * b[1] + w * 0.2965164f
        b[2] = 0.57000f * b[2] + w * 1.0526913f
        return (b[0] + b[1] + b[2] + w * 0.1848f) * 0.15f
    }

    fun process(buf: FloatArray, frames: Int, p: Params) {
        for (i in cur.indices) {
            val target = if (p.on[i]) p.amount[i] else 0f
            cur[i] += (target - cur[i]) * 0.25f
            if (target == 0f && cur[i] < 0.0005f) cur[i] = 0f
        }
        val muffle = cur[Fx.MUFFLE.ordinal]
        val lofi = cur[Fx.LOFI.ordinal]
        val echo = cur[Fx.ECHO.ordinal]
        val reverb = cur[Fx.REVERB.ordinal]
        val amb = cur[Fx.AMBIENT.ordinal] * 0.5f
        val vin = cur[Fx.VINYL.ordinal]
        val gain = p.master

        val mC = coef(18000f * (350f / 18000f).pow(muffle))
        val levels = 2f.pow(15f - lofi * 12f)          // 16bit → 4bit
        val step = 1f + lofi * lofi * 14f               // 샘플 홀드(다운샘플)
        val lofiC = coef(16000f * (2200f / 16000f).pow(lofi))
        val echoWet = echo * 0.55f
        val echoFb = 0.25f + echo * 0.45f
        val revWet = reverb * 2.2f
        val room = 0.70f + reverb * 0.26f
        val damp = 0.35f

        for (f in 0 until frames) {
            s[0] = buf[2 * f]; s[1] = buf[2 * f + 1]

            // ── 먹먹함
            for (ch in 0..1) {
                if (muffle > 0f) {
                    m1[ch] += mC * (s[ch] - m1[ch]); m2[ch] += mC * (m1[ch] - m2[ch]); s[ch] = m2[ch]
                } else { m1[ch] = s[ch]; m2[ch] = s[ch] }
            }
            // ── 저음질
            var take = true
            if (lofi > 0f) { holdPhase += 1f; take = holdPhase >= step; if (take) holdPhase -= step }
            for (ch in 0..1) {
                if (lofi > 0f) {
                    if (take) held[ch] = round(s[ch] * levels) / levels
                    lofiLp[ch] += lofiC * (held[ch] - lofiLp[ch]); s[ch] = lofiLp[ch]
                } else { held[ch] = s[ch]; lofiLp[ch] = s[ch] }
            }
            // ── 에코 (항상 돌려서 켤 때 튀는 소리 없게)
            val r0 = echoBuf[0][echoPos]; val r1 = echoBuf[1][echoPos]
            echoDamp[0] += 0.45f * (r0 - echoDamp[0]); echoDamp[1] += 0.45f * (r1 - echoDamp[1])
            echoBuf[0][echoPos] = (s[0] + s[1]) * 0.5f * (if (echo > 0f) 1f else 0f) + echoDamp[1] * echoFb
            echoBuf[1][echoPos] = echoDamp[0] * echoFb
            if (++echoPos >= echoLen) echoPos = 0
            s[0] += r0 * echoWet; s[1] += r1 * echoWet

            // ── 울림
            val revIn = (s[0] + s[1]) * 0.015f * (if (reverb > 0f) 1f else 0f) + 1e-18f
            for (ch in 0..1) {
                var o = 0f
                for (c in combs[ch]) o += c.process(revIn, room, damp)
                for (a in aps[ch]) o = a.process(o)
                s[ch] = s[ch] * (1f - 0.3f * reverb) + o * revWet
            }

            // ── 생활 소음
            if (amb > 0f) {
                when (p.noiseType) {
                    0 -> for (ch in 0..1) { // 빗소리
                        val pn = pink(ch, noise())
                        ambLp[ch] += c500 * (pn - ambLp[ch])
                        if (rnd.nextFloat() < 0.003f) drop[ch] = 0.3f + 0.7f * rnd.nextFloat()
                        val d = noise() * drop[ch]; drop[ch] *= 0.992f
                        s[ch] += ((pn - ambLp[ch]) * 0.6f + d * 0.22f) * amb
                    }
                    1 -> { // 카페
                        lfo += (lfoTarget - lfo) * 0.00005f
                        if (rnd.nextFloat() < 0.0002f) lfoTarget = 0.3f + 0.7f * rnd.nextFloat()
                        if (clinkEnv < 0.001f && rnd.nextFloat() < 0.000008f) {
                            clinkEnv = 0.3f + 0.4f * rnd.nextFloat()
                            clinkInc = 2f * PI.toFloat() * (2500f + 2500f * rnd.nextFloat()) / sr
                            clinkPan = rnd.nextFloat()
                        }
                        val clink = sin(clinkPhase) * clinkEnv
                        clinkPhase += clinkInc; if (clinkPhase > 6.2832f) clinkPhase -= 6.2832f
                        clinkEnv *= 0.9996f
                        for (ch in 0..1) {
                            val pn = pink(ch, noise())
                            ambLp[ch] += c800 * (pn - ambLp[ch]); ambLp2[ch] += c800 * (ambLp[ch] - ambLp2[ch])
                            val pan = if (ch == 0) 1f - clinkPan else clinkPan
                            s[ch] += (ambLp2[ch] * (0.5f + lfo) * 2.5f + clink * pan * 0.5f) * amb
                        }
                    }
                    else -> { // 거리
                        if (carT < 0f && rnd.nextFloat() < 0.000006f) {
                            carT = 0f; carDur = sr * (3f + 3f * rnd.nextFloat()); carPan = rnd.nextFloat()
                        }
                        var env = 0f
                        if (carT >= 0f) {
                            env = sin(PI.toFloat() * carT / carDur); carT += 1f
                            if (carT > carDur) carT = -1f
                        }
                        for (ch in 0..1) {
                            val w = noise()
                            brown[ch] = (brown[ch] + 0.02f * w) / 1.02f
                            ambLp[ch] += c200 * (brown[ch] * 3.5f - ambLp[ch])
                            val pn = pink(ch, w)
                            ambLp3[ch] += c600 * (pn - ambLp3[ch])
                            val pan = if (ch == 0) 1f - carPan else carPan
                            s[ch] += (ambLp[ch] * 1.2f + ambLp3[ch] * env * pan * 3f) * amb
                        }
                    }
                }
            }

            // ── LP 잡음
            if (vin > 0f) {
                if (rnd.nextFloat() < vin * 0.0004f)
                    pop = (0.3f + 0.7f * rnd.nextFloat()) * (if (rnd.nextBoolean()) 1f else -1f)
                val pp = pop; pop *= -0.6f
                for (ch in 0..1) {
                    hissLp[ch] += c3000 * (noise() - hissLp[ch])
                    s[ch] += pp * 0.5f * vin + hissLp[ch] * 0.03f * vin
                }
            }

            // ── 마스터 + 소프트 클리핑
            for (ch in 0..1) {
                val y = s[ch] * gain
                val a = abs(y)
                buf[2 * f + ch] = if (a < 0.8f) y else sign(y) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
            }
        }
    }

    private class Comb(size: Int) {
        val b = FloatArray(max(size, 1)); var i = 0; var store = 0f
        fun process(x: Float, fb: Float, damp: Float): Float {
            val o = b[i]
            store = o * (1f - damp) + store * damp
            b[i] = x + store * fb
            if (++i >= b.size) i = 0
            return o
        }
    }

    private class Allpass(size: Int) {
        val b = FloatArray(max(size, 1)); var i = 0
        fun process(x: Float): Float {
            val bo = b[i]
            b[i] = x + bo * 0.5f
            if (++i >= b.size) i = 0
            return bo - x
        }
    }
}
