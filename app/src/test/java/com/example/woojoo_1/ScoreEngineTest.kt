package com.example.woojoo_1

import com.example.woojoo_1.domain.ElementKind
import com.example.woojoo_1.domain.ElementResult
import com.example.woojoo_1.domain.Grade
import com.example.woojoo_1.domain.Maneuver
import com.example.woojoo_1.domain.SignalResult
import com.example.woojoo_1.domain.TripReport
import com.example.woojoo_1.domain.ManeuverType
import com.example.woojoo_1.domain.RecordedTrip
import com.example.woojoo_1.domain.ScoreConfig
import com.example.woojoo_1.domain.ScoreEngine
import com.example.woojoo_1.domain.Series
import com.example.woojoo_1.domain.StateChange
import com.example.woojoo_1.domain.TurnSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** 개발명세서 6.7 계산 검증용 예시를 그대로 재현한다 */
class ScoreEngineTest {

    private fun event(kind: ElementKind, peak: Double, jerk: Double) =
        ScoreEngine.scoreEvent(kind, 0.0, 1.0, peak, jerk)

    @Test
    fun 감속_예시_86점() {
        val e = event(ElementKind.DECEL, 1.39, 0.55)
        assertEquals(65, e.intensityScore.roundToInt())
        assertEquals(100, e.suddennessScore.roundToInt())
        assertEquals(86, e.score.roundToInt())
    }

    @Test
    fun 가속_예시_86점() {
        val e = event(ElementKind.ACCEL, 1.94, 1.11)
        assertEquals(73, e.intensityScore.roundToInt())
        assertEquals(95, e.suddennessScore.roundToInt())
        assertEquals(86, e.score.roundToInt())
    }

    @Test
    fun 핸들_예시_100점() {
        val e = event(ElementKind.STEERING, 0.89, 0.89)
        assertEquals(100.0, e.score, 1e-9)
    }

    @Test
    fun 위험선_이상이면_0점() {
        val e = event(ElementKind.DECEL, 2.3, 0.5)
        assertTrue(e.dangerous)
        assertEquals(0.0, e.score, 1e-9)
    }

    @Test
    fun 운행_종합_예시_89점6() {
        // 6.7 '운행 1회 종합' 표
        fun fake(kind: ElementKind, vararg scores: Double) =
            ElementResult(kind, scores.map { s ->
                ScoreEngine.scoreEvent(kind, 0.0, 1.0, 0.0, 0.0).copy(score = s)
            })
        val accel = fake(ElementKind.ACCEL, 86.0, 100.0)
        val decel = fake(ElementKind.DECEL, 86.0, 100.0, 70.0)
        val steer = fake(ElementKind.STEERING, 100.0, 80.0)
        val index = accel.contribution + decel.contribution + steer.contribution
        assertEquals(89.6, (index * 10).roundToInt() / 10.0, 1e-9)
    }

    @Test
    fun 깜빡이_감점표() {
        assertEquals(0.0, ScoreConfig.signalPenalty(0.93), 0.0)
        assertEquals(3.0, ScoreConfig.signalPenalty(0.85), 0.0)
        assertEquals(6.0, ScoreConfig.signalPenalty(0.75), 0.0)
        assertEquals(9.0, ScoreConfig.signalPenalty(0.65), 0.0)
        assertEquals(12.0, ScoreConfig.signalPenalty(0.55), 0.0)
        assertEquals(15.0, ScoreConfig.signalPenalty(0.2), 0.0)
    }

    // ── 시계열 → 리포트 전체 경로 ──────────────────────────────

    /** 10Hz 합성 주행 데이터 생성기 */
    private class TripBuilder {
        val t = ArrayList<Long>()
        val v = ArrayList<Float>()
        val steer = ArrayList<Float>()
        val signal = ArrayList<StateChange<TurnSignal>>()
        var speed = 0.0
        var angle = 0.0

        fun hold(sec: Double) = repeat((sec * 10).toInt()) { push() }

        /** km/h 를 sec 동안 부드러운 S커브로 변화 */
        fun ramp(toKmh: Double, sec: Double) {
            val from = speed
            val n = (sec * 10).toInt()
            for (i in 1..n) {
                val x = i.toDouble() / n
                speed = from + (toKmh - from) * (1 - kotlin.math.cos(PI * x)) / 2
                push()
            }
        }

        fun laneChange(peakDeg: Double, sec: Double) {
            val n = (sec * 10).toInt()
            for (i in 1..n) {
                angle = peakDeg * sin(2 * PI * i / n)
                push()
            }
            angle = 0.0
        }

        fun signalOn(s: TurnSignal) { signal.add(StateChange(now(), s)) }
        fun now() = t.size * 100L
        private fun push() {
            t.add(now()); v.add(speed.toFloat()); steer.add(angle.toFloat())
        }

        fun build(): RecordedTrip {
            val times = t.toLongArray()
            return RecordedTrip(
                startMs = 0, endMs = times.last(), startWallMs = 0, endWallMs = times.last(),
                speedKmh = Series(times, v.toFloatArray()),
                steeringDeg = Series(times, steer.toFloatArray()),
                brake = emptyList(),
                signal = signal,
            )
        }
    }

    @Test
    fun 부드러운_운전은_인증() {
        val b = TripBuilder()
        b.hold(3.0)
        b.ramp(50.0, 15.0)      // 약 0.93m/s² 최대, 완만
        b.hold(10.0)
        repeat(5) {
            b.signalOn(TurnSignal.LEFT); b.hold(3.0)
            b.laneChange(-6.0, 4.0)     // 좌회전 = 음수
            b.signalOn(TurnSignal.OFF); b.hold(5.0)
        }
        b.ramp(0.0, 18.0)
        b.hold(3.0)
        val r = ScoreEngine.evaluate(b.build())
        println("부드러운 운전: ${r.finalScore}점 / ${r.summary}")

        assertEquals(5, r.signal.maneuvers.count { it.type == ManeuverType.LANE_CHANGE })
        assertTrue(r.signal.maneuvers.all { it.direction == TurnSignal.LEFT })
        assertEquals(5, r.signal.compliant)
        assertEquals(0.0, r.penalty, 0.0)
        assertTrue("score=${r.finalScore}", r.grade.certified)
    }

    @Test
    fun 거친_운전은_감점() {
        val b = TripBuilder()
        b.hold(3.0)
        b.ramp(60.0, 4.0)       // 급가속
        b.hold(10.0)
        repeat(5) {
            b.laneChange(10.0, 3.2)   // 깜빡이 없이 차선 변경
            b.hold(5.0)
        }
        b.ramp(0.0, 3.0)        // 급정거
        b.hold(3.0)
        val r = ScoreEngine.evaluate(b.build())
        println("거친 운전: ${r.finalScore}점 / ${r.summary}")

        assertTrue(r.accel.dangerCount >= 1)
        assertTrue(r.decel.dangerCount >= 1)
        assertEquals(0, r.signal.compliant)
        assertEquals(15.0, r.penalty, 0.0)
        assertEquals(Grade.NEEDS_WORK, r.grade)
        assertTrue(r.summary, r.summary.contains("위험 기준") || r.summary.contains("깜빡이"))
    }

    @Test
    fun 반대쪽_깜빡이는_미준수() {
        val b = TripBuilder()
        b.hold(3.0)
        b.ramp(60.0, 15.0)
        b.hold(5.0)
        repeat(5) {
            b.signalOn(TurnSignal.LEFT); b.hold(3.0)
            b.laneChange(6.0, 4.0)      // 오른쪽으로 차선 변경인데 왼쪽 깜빡이
            b.signalOn(TurnSignal.OFF); b.hold(5.0)
        }
        b.ramp(0.0, 18.0)
        b.hold(3.0)
        val r = ScoreEngine.evaluate(b.build())

        assertEquals(5, r.signal.evaluated)
        assertTrue(r.signal.maneuvers.all { it.direction == TurnSignal.RIGHT && it.wrongSignal })
        assertEquals(0, r.signal.compliant)
        assertEquals(15.0, r.penalty, 0.0)
    }

    @Test
    fun 정차만_한_운행은_100점() {
        val b = TripBuilder()
        b.hold(40.0)
        val r = ScoreEngine.evaluate(b.build())
        assertEquals(100.0, r.finalScore, 0.0)
        assertEquals(0, r.totalEvents)
    }
}

/** 결과 화면 조언 문장 (AdviceWriter) */
class AdviceWriterTest {

    private fun element(kind: ElementKind, score: Double) =
        ElementResult(kind, listOf(ScoreEngine.scoreEvent(kind, 0.0, 1.0, 0.0, 0.0).copy(score = score)))

    private fun report(accel: Double, decel: Double, steer: Double, signal: SignalResult = SignalResult(emptyList(), 0)) =
        TripReport(
            startWallMs = 0, endWallMs = 60_000, durationSec = 60.0, distanceKm = 1.0, maxSpeedKmh = 60.0,
            hadSpeedData = true,
            accel = element(ElementKind.ACCEL, accel),
            decel = element(ElementKind.DECEL, decel),
            steering = element(ElementKind.STEERING, steer),
            signal = signal,
        )

    @Test
    fun 요청_예시_재현() {
        val r = report(82.0, 58.0, 60.0)
        assertEquals(65, kotlin.math.round(r.finalScore).toInt())
        assertEquals(Grade.NEEDS_WORK, r.grade)
        assertEquals("감속과 핸들 점수가 낮아요. 브레이크는 미리 천천히, 핸들은 부드럽게 돌려보세요.", r.advice)
    }

    @Test
    fun 세_요소_모두_낮을_때() {
        val r = report(70.0, 60.0, 50.0)
        assertEquals(
            "가속, 감속과 핸들 점수가 낮아요. 출발할 때 가속 페달은 천천히, 브레이크는 미리 천천히, 핸들은 부드럽게 돌려보세요.",
            r.advice,
        )
    }

    @Test
    fun 깜빡이_감점만_있을_때() {
        val missed = List(5) { Maneuver(ManeuverType.LANE_CHANGE, TurnSignal.LEFT, 0.0, 4.0, 6.0, false, null) }
        val r = report(95.0, 95.0, 95.0, SignalResult(missed, 0))
        assertEquals("방향을 바꾸기 전에 깜빡이를 먼저 켜 주세요.", r.advice)
    }

    @Test
    fun 모두_좋을_때() {
        assertEquals("가속·감속·핸들 모두 부드러워요. 지금처럼 운전해 주세요.", report(95.0, 96.0, 97.0).advice)
    }
}
