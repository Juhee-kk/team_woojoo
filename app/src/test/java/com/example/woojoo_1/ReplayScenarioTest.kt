package com.example.woojoo_1

import com.example.woojoo_1.domain.ReplayScenario
import com.example.woojoo_1.domain.ScoreEngine
import com.example.woojoo_1.domain.TripReport
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * 시연용 재생 시나리오 점수 검증
 * 목표: soft 90점대, rough 50~60점대
 */
class ReplayScenarioTest {

    private fun load(name: String): ReplayScenario {
        val f = File("src/main/assets/replay/$name.csv")
        return ReplayScenario.parse(name, f.readText(Charsets.UTF_8))
    }

    private fun describe(name: String, r: TripReport) = buildString {
        appendLine("── $name ─────────────────────────────")
        appendLine("최종 ${r.finalScore} (${r.grade.label}) = 지수 ${r.smoothIndex} − 깜빡이 ${r.penalty}")
        for (e in r.elements) {
            appendLine("  ${e.kind.label} ${"%.1f".format(e.score)}점, 이벤트 ${e.events.size}개")
            for (ev in e.events) {
                appendLine(
                    "    t=%5.1fs %.1fs  세기 %.2f→%3d  저크 %.2f→%3d  = %3d%s".format(
                        ev.startSec, ev.durationSec, ev.peakAccel, ev.intensityScore.roundToInt(),
                        ev.peakJerk, ev.suddennessScore.roundToInt(), ev.score.roundToInt(),
                        if (ev.dangerous) " 위험" else "",
                    )
                )
            }
        }
        val s = r.signal
        appendLine("  깜빡이 ${s.compliant}/${s.evaluated} (제외 ${s.excludedCount}) 감점 ${s.penalty}")
        for (m in s.maneuvers) {
            appendLine(
                "    t=%5.1fs %s %s %.0f° 선행=%s%s".format(
                    m.startSec, m.direction, m.type.label, m.peakAngleDeg, m.signalled,
                    if (m.wrongSignal) " (반대쪽)" else "",
                )
            )
        }
        appendLine("  요약: ${r.summary}")
        appendLine("  조언: ${r.advice}")
    }

    @Test
    fun soft_는_90점대() {
        val r = ScoreEngine.evaluate(load("soft").toRecordedTrip())
        println(describe("soft", r))
        assertTrue("soft=${r.finalScore}", r.finalScore >= 90.0 && r.finalScore < 100.0)
    }

    @Test
    fun rough_는_50에서_60점대() {
        val r = ScoreEngine.evaluate(load("rough").toRecordedTrip())
        println(describe("rough", r))
        assertTrue("rough=${r.finalScore}", r.finalScore >= 50.0 && r.finalScore < 70.0)
    }
}
