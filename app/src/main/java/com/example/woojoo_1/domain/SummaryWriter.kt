package com.example.woojoo_1.domain

import java.util.Locale

/** 감점 요인을 한 줄 자연어로 요약 */
object SummaryWriter {

    private class Factor(val lost: Double, val text: String)

    fun write(r: TripReport): String {
        val factors = ArrayList<Factor>()

        for (e in r.elements) {
            val deducted = e.deducted
            if (deducted.isEmpty() || e.lostPoints < 0.5) continue
            factors.add(Factor(e.lostPoints, describe(e, deducted)))
        }
        if (r.signal.applied && r.signal.penalty > 0) {
            factors.add(Factor(r.signal.penalty, "깜빡이 없이 방향을 바꾼 ${r.signal.missed}회"))
        }

        val top = factors.sortedByDescending { it.lost }.take(2)
        return when (top.size) {
            0 -> when {
                r.totalEvents == 0 && !r.hadSpeedData -> "속도 변화가 기록되지 않아 감점 요인이 없어요."
                r.totalEvents == 0 -> "울컥하거나 쏠린 순간 없이 고르게 운행했어요."
                else -> "감점 요인 없이 부드럽게 운행했어요."
            }
            1 -> "${top[0].text}가 점수를 깎았어요 (${pts(top[0].lost)})."
            else -> "${top[0].text}(${pts(top[0].lost)}), ${top[1].text}(${pts(top[1].lost)})가 주요 감점 요인이에요."
        }
    }

    /** 세기와 갑작스러움 중 무엇이 더 많이 깎았는지에 따라 표현을 고른다 */
    private fun describe(e: ElementResult, deducted: List<ScoredEvent>): String {
        if (e.dangerCount > 0) {
            val name = when (e.kind) {
                ElementKind.ACCEL -> "위험 기준을 넘은 급가속"
                ElementKind.DECEL -> "위험 기준을 넘은 급감속"
                ElementKind.STEERING -> "위험 기준을 넘은 급회전"
            }
            return "$name ${e.dangerCount}회"
        }
        val intensityLoss = deducted.sumOf { (100 - it.intensityScore) * ScoreConfig.INTENSITY_WEIGHT }
        val suddenLoss = deducted.sumOf { (100 - it.suddennessScore) * ScoreConfig.SUDDENNESS_WEIGHT }
        val sudden = suddenLoss >= intensityLoss
        val name = when (e.kind) {
            ElementKind.ACCEL -> if (sudden) "출발할 때 울컥한 순간" else "세게 가속한 순간"
            ElementKind.DECEL -> if (sudden) "멈출 때 울컥한 제동" else "세게 감속한 순간"
            ElementKind.STEERING -> if (sudden) "핸들을 급하게 꺾은 순간" else "옆으로 크게 쏠린 회전"
        }
        return "$name ${deducted.size}회"
    }

    private fun pts(x: Double) = String.format(Locale.KOREA, "−%.1f점", x)
}
