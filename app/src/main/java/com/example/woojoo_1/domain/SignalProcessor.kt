package com.example.woojoo_1.domain

import kotlin.math.max
import kotlin.math.min

/** 10Hz 격자로 정렬·필터링된 운행 데이터 */
class ProcessedTrip(
    val dt: Double,
    val speedMs: DoubleArray,       // m/s (필터 적용)
    val longAcc: DoubleArray,       // 앞뒤 가속도 m/s²
    val longJerk: DoubleArray,      // 앞뒤 저크 m/s³
    val steerDeg: DoubleArray,      // 핸들 각도 (필터 적용)
    val latAcc: DoubleArray,        // 좌우 가속도 m/s² (부호 = 핸들 방향)
    val latJerk: DoubleArray,       // 좌우 저크 m/s³
    val brake: BooleanArray,
    val signal: List<TurnSignal>,
) {
    val n: Int get() = speedMs.size
}

/**
 * 원시 기록 → 가속도·저크 (명세서 6.1)
 *
 * 1. 10Hz 격자로 리샘플 (직전 값 유지). 중복 수신 샘플은 수집 단계에서 이미 제거됨
 * 2. 이동평균으로 노이즈 제거
 * 3. 1초 간격 중앙 차분으로 가속도, 한 번 더 차분해 저크
 * 4. 좌우 가속도 = v² × 핸들각(rad) ÷ (조향비 × 축거)
 */
object SignalProcessor {

    fun process(trip: RecordedTrip): ProcessedTrip {
        val dt = ScoreConfig.SAMPLE_DT_SEC
        val step = ScoreConfig.SAMPLE_DT_MS
        val n = ((trip.endMs - trip.startMs).coerceAtLeast(0L) / step).toInt() + 1

        val speedKmh = hold(trip.speedKmh, trip.startMs, step, n)
        val steerRaw = hold(trip.steeringDeg, trip.startMs, step, n)

        val v = movingAverage(DoubleArray(n) { speedKmh[it] / 3.6 }, ScoreConfig.SMOOTH_WINDOW)
        val steer = movingAverage(steerRaw, ScoreConfig.SMOOTH_WINDOW)

        val h = ScoreConfig.DIFF_HALF_SAMPLES
        val longAcc = derivative(v, h, dt)
        val longJerk = derivative(longAcc, h, dt)

        val k = 1.0 / (ScoreConfig.STEERING_RATIO * ScoreConfig.WHEELBASE_M)
        val lat = DoubleArray(n) { v[it] * v[it] * Math.toRadians(steer[it]) * k }
        val latJerk = derivative(lat, h, dt)

        val brake = holdState(trip.brake, false, trip.startMs, step, n).toBooleanArray()
        val signal = holdState(trip.signal, TurnSignal.OFF, trip.startMs, step, n)

        return ProcessedTrip(dt, v, longAcc, longJerk, steer, lat, latJerk, brake, signal)
    }

    /** 직전 값 유지(zero-order hold) 리샘플 */
    fun hold(series: Series, startMs: Long, stepMs: Long, n: Int): DoubleArray {
        val out = DoubleArray(n)
        var j = 0
        var current = if (series.size > 0) series.values[0].toDouble() else 0.0
        for (i in 0 until n) {
            val t = startMs + i * stepMs
            while (j < series.size && series.times[j] <= t) {
                current = series.values[j].toDouble()
                j++
            }
            out[i] = current
        }
        return out
    }

    fun <T> holdState(changes: List<StateChange<T>>, initial: T, startMs: Long, stepMs: Long, n: Int): List<T> {
        val out = ArrayList<T>(n)
        var j = 0
        var current = initial
        for (i in 0 until n) {
            val t = startMs + i * stepMs
            while (j < changes.size && changes[j].timeMs <= t) {
                current = changes[j].value
                j++
            }
            out.add(current)
        }
        return out
    }

    /** 중앙 이동평균 (가장자리는 창을 잘라서 평균) */
    fun movingAverage(x: DoubleArray, window: Int): DoubleArray {
        val n = x.size
        if (n == 0 || window <= 1) return x.copyOf()
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
        val half = window / 2
        return DoubleArray(n) { i ->
            val lo = max(0, i - half)
            val hi = min(n - 1, i + half)
            (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
    }

    /** 중앙 차분 미분 (가장자리는 한쪽 차분) */
    fun derivative(x: DoubleArray, half: Int, dt: Double): DoubleArray {
        val n = x.size
        return DoubleArray(n) { i ->
            val lo = max(0, i - half)
            val hi = min(n - 1, i + half)
            if (hi == lo) 0.0 else (x[hi] - x[lo]) / ((hi - lo) * dt)
        }
    }
}
