package com.example.woojoo_1.domain

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 깜빡이 준수율 (명세서 7장)
 *
 * 원칙: 확실할 때만 평가하고, 애매하면 제외.
 * - 차선 변경: 핸들 한쪽 → 반대쪽 → 원위치 (S자, 3~6초), 시속 20km 이상
 * - 회전: 90° 이상 크게 꺾었다가 20초 안에 원위치 (내비 TBT 연동 전 임시 판별)
 * - 굽은 길(일정 각도 유지)·기타 패턴은 제외 구간으로 집계
 * - 준수 = 핸들 조작 시작 '전에' 진행 방향과 같은 쪽 깜빡이를 켬
 * - 핸들 각도 부호: 좌회전 = 음수 (Virtual Console 실측)
 */
object TurnSignalEvaluator {

    private class Segment(val start: Int, val endExclusive: Int, val sign: Double, val peakAbs: Double)

    fun evaluate(p: ProcessedTrip): SignalResult {
        val dt = p.dt
        val segments = steeringSegments(p)
        val groups = groupSegments(segments, (ScoreConfig.STEER_GROUP_GAP_SEC / dt).roundToInt())
        val signalOn = mapOf(
            TurnSignal.LEFT to signalIntervals(p, TurnSignal.LEFT),
            TurnSignal.RIGHT to signalIntervals(p, TurnSignal.RIGHT),
        )

        val maneuvers = ArrayList<Maneuver>()
        var excluded = 0

        for (g in groups) {
            val start = g.first().start
            val end = g.last().endExclusive
            val durationSec = (end - start) * dt
            val peak = g.maxOf { it.peakAbs }
            var speedSum = 0.0
            for (i in start until end) speedSum += p.speedMs[i]
            val avgSpeedKmh = speedSum / (end - start) * 3.6

            val type = when {
                g.size == 2 && g[0].sign != g[1].sign &&
                    durationSec >= ScoreConfig.LANE_CHANGE_MIN_SEC &&
                    durationSec <= ScoreConfig.LANE_CHANGE_MAX_SEC &&
                    avgSpeedKmh >= ScoreConfig.LANE_CHANGE_MIN_SPEED_KMH &&
                    peak <= ScoreConfig.LANE_CHANGE_MAX_DEG -> ManeuverType.LANE_CHANGE

                g.size == 1 && peak >= ScoreConfig.TURN_MIN_DEG &&
                    durationSec <= ScoreConfig.TURN_MAX_SEC &&
                    avgSpeedKmh >= ScoreConfig.MOVING_SPEED_KMH -> ManeuverType.TURN

                else -> null
            }

            if (type == null) {
                if (peak >= ScoreConfig.STEER_MEANINGFUL_DEG && avgSpeedKmh >= ScoreConfig.MOVING_SPEED_KMH) excluded++
                continue
            }

            // 차선 변경은 처음 꺾은 쪽, 회전은 꺾은 쪽이 진행 방향
            val direction = directionOf(g.first().sign)
            val opposite = if (direction == TurnSignal.LEFT) TurnSignal.RIGHT else TurnSignal.LEFT
            val lit = litBefore(signalOn.getValue(direction), start, dt)

            maneuvers.add(
                Maneuver(
                    type = type,
                    direction = direction,
                    startSec = start * dt,
                    durationSec = durationSec,
                    peakAngleDeg = peak,
                    signalled = lit != null,
                    leadSec = lit?.let { (start - it.first) * dt },
                    wrongSignal = lit == null && litBefore(signalOn.getValue(opposite), start, dt) != null,
                )
            )
        }
        return SignalResult(maneuvers, excluded)
    }

    /** 조작 시작 전 15초 안에 켜졌고, 시작 2초 전까지는 켜져 있던 점등 구간 */
    private fun litBefore(intervals: List<IntRange>, start: Int, dt: Double): IntRange? {
        val lookback = (ScoreConfig.SIGNAL_LOOKBACK_SEC / dt).roundToInt()
        val grace = (ScoreConfig.SIGNAL_GRACE_SEC / dt).roundToInt()
        return intervals
            .filter { it.first <= start && it.first >= start - lookback && it.last >= start - grace }
            .maxByOrNull { it.first }
    }

    /** |핸들각| ≥ 3° 인 연속 구간 (부호가 바뀌려면 0을 지나므로 구간 내 부호는 일정) */
    private fun steeringSegments(p: ProcessedTrip): List<Segment> {
        val out = ArrayList<Segment>()
        var start = -1
        var peak = 0.0
        for (i in 0..p.n) {
            val active = i < p.n && abs(p.steerDeg[i]) >= ScoreConfig.STEER_ACTIVE_DEG
            if (active) {
                if (start < 0) { start = i; peak = 0.0 }
                peak = maxOf(peak, abs(p.steerDeg[i]))
            } else if (start >= 0) {
                // 3° 기준으로 찾은 구간을 핸들이 중앙(1° 미만)에 오는 지점까지 넓혀 실제 조작 시간을 잰다
                val sg = sign(p.steerDeg[start])
                var s = start
                while (s > 0 && p.steerDeg[s - 1] * sg >= ScoreConfig.STEER_REST_DEG) s--
                var e = i
                while (e < p.n && p.steerDeg[e] * sg >= ScoreConfig.STEER_REST_DEG) e++
                out.add(Segment(s, e, sg, peak))
                start = -1
            }
        }
        return out
    }

    private fun groupSegments(segments: List<Segment>, maxGap: Int): List<List<Segment>> {
        val groups = ArrayList<MutableList<Segment>>()
        for (s in segments) {
            val last = groups.lastOrNull()
            if (last != null && s.start - last.last().endExclusive <= maxGap) last.add(s)
            else groups.add(mutableListOf(s))
        }
        return groups
    }

    /** 핸들 각도 부호 → 방향. 실측 결과 좌회전이 음수 */
    fun directionOf(sign: Double): TurnSignal =
        if (sign < 0) TurnSignal.LEFT else TurnSignal.RIGHT

    /** 한 방향 깜빡이의 점등 구간. 깜빡임으로 짧게 꺼진 틈은 합친다 */
    private fun signalIntervals(p: ProcessedTrip, dir: TurnSignal): List<IntRange> {
        val raw = ArrayList<IntRange>()
        var start = -1
        for (i in 0..p.n) {
            val on = i < p.n && p.signal[i] == dir
            if (on && start < 0) start = i
            if (!on && start >= 0) { raw.add(start until i); start = -1 }
        }
        val gap = (ScoreConfig.SIGNAL_MERGE_GAP_SEC / p.dt).roundToInt()
        val merged = ArrayList<IntRange>()
        for (r in raw) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.last <= gap) merged[merged.size - 1] = last.first..r.last
            else merged.add(r)
        }
        return merged
    }
}
