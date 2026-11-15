package com.example.woojoo_1.domain

import kotlin.math.roundToInt

// ═══════════════════════ 수집 데이터 ═══════════════════════

enum class TurnSignal { OFF, LEFT, RIGHT }

/** 시간(ms, elapsedRealtime) - 값 시계열 */
class Series(val times: LongArray, val values: FloatArray) {
    val size: Int get() = times.size
}

data class StateChange<T>(val timeMs: Long, val value: T)

/** 운행 1회의 원시 기록 */
class RecordedTrip(
    val startMs: Long,
    val endMs: Long,
    val startWallMs: Long,
    val endWallMs: Long,
    val speedKmh: Series,
    val steeringDeg: Series,
    val brake: List<StateChange<Boolean>>,
    val signal: List<StateChange<TurnSignal>>,
)

// ═══════════════════════ 점수 결과 ═══════════════════════

/** 요소별 가중치와 기준값 (5.2, 6.4) */
enum class ElementKind(
    val label: String,
    val weight: Double,
    val smoothLimit: Double,   // 부드러움 경계 (100점)
    val dangerLimit: Double,   // 위험선 (0점)
) {
    ACCEL("가속", 0.25, 1.5, 3.1),
    DECEL("감속", 0.25, 1.0, 2.1),
    STEERING("핸들", 0.50, 1.0, 2.1),
}

data class ScoredEvent(
    val kind: ElementKind,
    val startSec: Double,       // 운행 시작 기준 경과 초
    val durationSec: Double,
    val peakAccel: Double,      // 구간 내 최대 가속도 (m/s², 절댓값)
    val peakJerk: Double,       // 구간 내 최대 저크 (m/s³, 절댓값)
    val intensityScore: Double,
    val suddennessScore: Double,
    val score: Double,
    val dangerous: Boolean,     // 세기가 위험선 이상 → 0점
    val brakeOnSec: Double,     // 구간 중 브레이크 ON 시간
)

class ElementResult(val kind: ElementKind, val events: List<ScoredEvent>) {
    /** 이벤트가 없으면 흔들림이 없었던 것이므로 100점 */
    val score: Double = if (events.isEmpty()) 100.0 else events.sumOf { it.score } / events.size
    val contribution: Double get() = score * kind.weight
    val lostPoints: Double get() = (100.0 - score) * kind.weight
    val deducted: List<ScoredEvent> get() = events.filter { it.score < 100.0 }
    val dangerCount: Int get() = events.count { it.dangerous }
    val maxPeak: Double? get() = events.maxOfOrNull { it.peakAccel }
    val maxJerk: Double? get() = events.maxOfOrNull { it.peakJerk }
    val avgDurationSec: Double? get() = events.takeIf { it.isNotEmpty() }?.map { it.durationSec }?.average()

    /** 이 이벤트 하나가 최종 점수에서 깎은 점수 */
    fun pointsLostBy(e: ScoredEvent): Double = (100.0 - e.score) / events.size * kind.weight
}

enum class ManeuverType(val label: String) { LANE_CHANGE("차선 변경"), TURN("회전") }

data class Maneuver(
    val type: ManeuverType,
    val direction: TurnSignal,  // 진행 방향 (LEFT / RIGHT)
    val startSec: Double,
    val durationSec: Double,
    val peakAngleDeg: Double,
    val signalled: Boolean,     // 같은 방향 깜빡이를 먼저 켰는지
    val leadSec: Double?,       // 깜빡이가 조작보다 몇 초 먼저 켜졌는지
    val wrongSignal: Boolean = false,   // 반대 방향 깜빡이를 켬 (미준수)
)

val TurnSignal.label: String
    get() = when (this) {
        TurnSignal.LEFT -> "왼쪽"
        TurnSignal.RIGHT -> "오른쪽"
        TurnSignal.OFF -> "-"
    }

class SignalResult(val maneuvers: List<Maneuver>, val excludedCount: Int) {
    val evaluated: Int get() = maneuvers.size
    val compliant: Int get() = maneuvers.count { it.signalled }
    val missed: Int get() = evaluated - compliant
    val rate: Double? get() = if (evaluated == 0) null else compliant.toDouble() / evaluated
    val applied: Boolean get() = evaluated >= ScoreConfig.SIGNAL_MIN_SAMPLES
    val penalty: Double get() = if (applied) ScoreConfig.signalPenalty(rate ?: 1.0) else 0.0
    val avgLeadSec: Double?
        get() = maneuvers.mapNotNull { it.leadSec }.takeIf { it.isNotEmpty() }?.average()
}

enum class Grade(val label: String, val certified: Boolean) {
    EXCELLENT("매우 부드러움", true),
    SMOOTH("부드러움", true),
    NORMAL("보통", false),
    NEEDS_WORK("개선 필요", false);

    companion object {
        fun of(score: Double): Grade = when {
            score >= 90.0 -> EXCELLENT
            score >= 80.0 -> SMOOTH
            score >= 70.0 -> NORMAL
            else -> NEEDS_WORK
        }
    }
}

class TripReport(
    val startWallMs: Long,
    val endWallMs: Long,
    val durationSec: Double,
    val distanceKm: Double,
    val maxSpeedKmh: Double,
    val hadSpeedData: Boolean,
    val accel: ElementResult,
    val decel: ElementResult,
    val steering: ElementResult,
    val signal: SignalResult,
) {
    val elements: List<ElementResult> get() = listOf(accel, decel, steering)

    /** 부드러움 지수 = 가속×25% + 감속×25% + 핸들×50% */
    val smoothIndex: Double = round1(accel.contribution + decel.contribution + steering.contribution)
    val penalty: Double = signal.penalty
    val finalScore: Double = round1((smoothIndex - penalty).coerceIn(0.0, 100.0))
    val grade: Grade = Grade.of(finalScore)
    /** 증명서용: 감점 요인과 깎인 점수 */
    val summary: String = SummaryWriter.write(this)
    /** 결과 화면용: 낮은 항목과 운전 요령 */
    val advice: String = AdviceWriter.write(this)

    val totalEvents: Int get() = elements.sumOf { it.events.size }
}

fun round1(x: Double): Double = (x * 10).roundToInt() / 10.0
