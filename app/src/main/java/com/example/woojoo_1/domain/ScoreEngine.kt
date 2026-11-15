package com.example.woojoo_1.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/** 원시 운행 기록 → 부드러움 지수 리포트 (명세서 6장) */
object ScoreEngine {

    fun evaluate(trip: RecordedTrip): TripReport {
        val p = SignalProcessor.process(trip)

        // 주행거리 = Σ(10Hz 격자 속도 m/s × 0.1초). 이동평균은 면적(거리)을 거의 바꾸지 않는다
        val distanceKm = p.speedMs.sumOf { it * p.dt } / 1000.0
        // 최고 속도는 필터 전 수신값 기준 (이동평균은 순간 최고값을 깎는다)
        val maxSpeedKmh = (trip.speedKmh.values.maxOrNull() ?: 0f).toDouble()
        val hadSpeedData = trip.speedKmh.values.any { it > 0f }

        return TripReport(
            startWallMs = trip.startWallMs,
            endWallMs = trip.endWallMs,
            durationSec = (trip.endMs - trip.startMs) / 1000.0,
            distanceKm = distanceKm,
            maxSpeedKmh = maxSpeedKmh,
            hadSpeedData = hadSpeedData,
            accel = ElementResult(ElementKind.ACCEL, detectEvents(ElementKind.ACCEL, p)),
            decel = ElementResult(ElementKind.DECEL, detectEvents(ElementKind.DECEL, p)),
            steering = ElementResult(ElementKind.STEERING, detectEvents(ElementKind.STEERING, p)),
            signal = TurnSignalEvaluator.evaluate(p),
        )
    }

    /** 디버그 패널용: 지금까지 감지된 (가속, 감속, 핸들) 이벤트 수 */
    fun liveEventCounts(trip: RecordedTrip): Triple<Int, Int, Int> {
        val p = SignalProcessor.process(trip)
        return Triple(
            detectEvents(ElementKind.ACCEL, p).size,
            detectEvents(ElementKind.DECEL, p).size,
            detectEvents(ElementKind.STEERING, p).size,
        )
    }

    // ── 이벤트 판정 (6.2) ────────────────────────────────────────

    fun detectEvents(kind: ElementKind, p: ProcessedTrip): List<ScoredEvent> {
        val magnitude: DoubleArray
        val jerk: DoubleArray
        when (kind) {
            ElementKind.ACCEL -> { magnitude = p.longAcc; jerk = p.longJerk }
            ElementKind.DECEL -> { magnitude = DoubleArray(p.n) { -p.longAcc[it] }; jerk = p.longJerk }
            ElementKind.STEERING -> { magnitude = DoubleArray(p.n) { abs(p.latAcc[it]) }; jerk = p.latJerk }
        }
        val minLen = (ScoreConfig.EVENT_MIN_SEC / p.dt).roundToInt()

        return findRuns(magnitude, ScoreConfig.EVENT_THRESHOLD, minLen).map { run ->
            var peak = 0.0
            var peakJerk = 0.0
            var brakeSamples = 0
            for (i in run) {
                if (magnitude[i] > peak) peak = magnitude[i]
                if (abs(jerk[i]) > peakJerk) peakJerk = abs(jerk[i])
                if (p.brake[i]) brakeSamples++
            }
            scoreEvent(
                kind = kind,
                startSec = run.first * p.dt,
                durationSec = (run.last - run.first + 1) * p.dt,
                peak = peak,
                peakJerk = peakJerk,
                brakeOnSec = brakeSamples * p.dt,
            )
        }
    }

    /** 값이 threshold 이상인 구간 중 길이가 minLen 이상인 것 */
    fun findRuns(x: DoubleArray, threshold: Double, minLen: Int): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var start = -1
        for (i in x.indices) {
            if (x[i] >= threshold) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= minLen) runs.add(start until i)
                start = -1
            }
        }
        if (start >= 0 && x.size - start >= minLen) runs.add(start until x.size)
        return runs
    }

    // ── 이벤트 채점 (6.3) ────────────────────────────────────────

    fun intensityScore(kind: ElementKind, peak: Double): Double =
        clamp100(100.0 * (kind.dangerLimit - peak) / (kind.dangerLimit - kind.smoothLimit))

    fun suddennessScore(peakJerk: Double): Double =
        clamp100(100.0 * (ScoreConfig.JERK_LIMIT - peakJerk) / (ScoreConfig.JERK_LIMIT - ScoreConfig.JERK_SMOOTH))

    fun scoreEvent(
        kind: ElementKind,
        startSec: Double,
        durationSec: Double,
        peak: Double,
        peakJerk: Double,
        brakeOnSec: Double = 0.0,
    ): ScoredEvent {
        val intensity = intensityScore(kind, peak)
        val sudden = suddennessScore(peakJerk)
        val dangerous = peak >= kind.dangerLimit
        val score = if (dangerous) 0.0
        else intensity * ScoreConfig.INTENSITY_WEIGHT + sudden * ScoreConfig.SUDDENNESS_WEIGHT
        return ScoredEvent(kind, startSec, durationSec, peak, peakJerk, intensity, sudden, score, dangerous, brakeOnSec)
    }

    private fun clamp100(x: Double) = x.coerceIn(0.0, 100.0)
}
