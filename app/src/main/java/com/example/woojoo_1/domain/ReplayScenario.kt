package com.example.woojoo_1.domain

/**
 * 재생용 주행 시나리오 (10Hz CSV)
 * 컬럼: time_s, speed_kmh, steering_deg, turn_signal[, cue]
 * - 핸들 각도 부호: 좌회전 = 음수
 * - 브레이크 데이터는 없으므로 '속도가 줄어들면 ON' 으로 간주한다
 */
class ReplayScenario(val name: String, val rows: List<Row>) {

    data class Row(
        val timeSec: Double,
        val speedKmh: Float,
        val steerDeg: Float,
        val signal: TurnSignal,
        val brake: Boolean,
        val cue: String?,
    )

    val durationSec: Double get() = rows.lastOrNull()?.timeSec ?: 0.0

    /** 실시간 재생 없이 곧바로 채점할 때 쓰는 운행 기록 (유닛 테스트·검증용) */
    fun toRecordedTrip(): RecordedTrip {
        val times = LongArray(rows.size) { (rows[it].timeSec * 1000).toLong() }
        val brake = ArrayList<StateChange<Boolean>>()
        val signal = ArrayList<StateChange<TurnSignal>>()
        var lastBrake: Boolean? = null
        var lastSignal: TurnSignal? = null
        rows.forEachIndexed { i, r ->
            if (r.brake != lastBrake) { brake.add(StateChange(times[i], r.brake)); lastBrake = r.brake }
            if (r.signal != lastSignal) { signal.add(StateChange(times[i], r.signal)); lastSignal = r.signal }
        }
        return RecordedTrip(
            startMs = times.firstOrNull() ?: 0L,
            endMs = times.lastOrNull() ?: 0L,
            startWallMs = 0L,
            endWallMs = times.lastOrNull() ?: 0L,
            speedKmh = Series(times, FloatArray(rows.size) { rows[it].speedKmh }),
            steeringDeg = Series(times, FloatArray(rows.size) { rows[it].steerDeg }),
            brake = brake,
            signal = signal,
        )
    }

    companion object {
        /** 속도가 이만큼 이상 줄어들면 브레이크 ON (km/h, 한 샘플 기준) */
        private const val BRAKE_DROP_KMH = 0.01f

        fun parse(name: String, text: String): ReplayScenario {
            val lines = text.removePrefix("﻿").lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() }
                .toList()
            require(lines.isNotEmpty()) { "$name: 빈 파일" }

            val header = lines.first().split(',').map { it.trim().lowercase() }
            fun col(n: String) = header.indexOf(n).also { require(it >= 0) { "$name: '$n' 컬럼 없음" } }
            val cT = col("time_s")
            val cV = col("speed_kmh")
            val cS = col("steering_deg")
            val cSig = col("turn_signal")
            val cCue = header.indexOf("cue")

            val rows = ArrayList<Row>(lines.size)
            var prevSpeed: Float? = null
            for ((i, line) in lines.drop(1).withIndex()) {
                val f = line.split(',', limit = header.size)
                try {
                    val v = f[cV].trim().toFloat()
                    val brake = prevSpeed != null && v < prevSpeed - BRAKE_DROP_KMH
                    prevSpeed = v
                    rows.add(
                        Row(
                            timeSec = f[cT].trim().toDouble(),
                            speedKmh = v,
                            steerDeg = f[cS].trim().toFloat(),
                            signal = when (f[cSig].trim().uppercase()) {
                                "LEFT" -> TurnSignal.LEFT
                                "RIGHT" -> TurnSignal.RIGHT
                                else -> TurnSignal.OFF
                            },
                            brake = brake,
                            cue = if (cCue >= 0) f.getOrNull(cCue)?.trim()?.takeIf { it.isNotEmpty() } else null,
                        )
                    )
                } catch (e: Exception) {
                    throw IllegalArgumentException("$name: ${i + 2}번째 줄을 읽을 수 없음 → '$line'", e)
                }
            }
            return ReplayScenario(name, rows)
        }
    }
}
