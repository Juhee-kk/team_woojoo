package com.example.woojoo_1.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.woojoo_1.R
import com.example.woojoo_1.domain.ElementKind
import com.example.woojoo_1.domain.ElementResult
import com.example.woojoo_1.domain.Grade
import com.example.woojoo_1.domain.ScoreConfig
import com.example.woojoo_1.domain.TripReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Format {
    private val date = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
    private val time = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val timeSec = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun score(x: Double): String =
        if (abs(x - x.roundToInt()) < 0.05) x.roundToInt().toString() else String.format(Locale.KOREA, "%.1f", x)

    fun one(x: Double): String = String.format(Locale.KOREA, "%.1f", x)
    fun two(x: Double): String = String.format(Locale.KOREA, "%.2f", x)

    fun duration(sec: Double): String {
        val total = sec.roundToInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}시간 ${m}분"
            m > 0 -> "${m}분 ${s}초"
            else -> "${s}초"
        }
    }

    fun period(r: TripReport): String =
        "${date.format(Date(r.startWallMs))} ${time.format(Date(r.startWallMs))} ~ ${time.format(Date(r.endWallMs))}"

    fun clockAt(r: TripReport, offsetSec: Double): String =
        timeSec.format(Date(r.startWallMs + (offsetSec * 1000).toLong()))

    fun certificateNo(r: TripReport): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.KOREA).format(Date(r.startWallMs))
        val check = ((r.startWallMs xor r.endWallMs) and 0xFFFF).toString(16).uppercase(Locale.ROOT).padStart(4, '0')
        return "SM-$stamp-$check"
    }

    fun signalLine(r: TripReport): String {
        val s = r.signal
        val rate = s.rate
        return when {
            s.evaluated == 0 -> "깜빡이 평가 대상 조작 없음"
            !s.applied -> "깜빡이 준수 ${s.compliant}/${s.evaluated}회 · 표본 ${ScoreConfig.SIGNAL_MIN_SAMPLES}회 미만이라 감점 미적용"
            s.penalty == 0.0 -> "깜빡이 준수율 ${pct(rate)} · 감점 없음"
            else -> "깜빡이 준수율 ${pct(rate)} · −${score(s.penalty)}점"
        }
    }

    /** 요소별 이벤트 통계 한 줄 */
    fun elementDetail(e: ElementResult): String {
        val noun = when (e.kind) {
            ElementKind.ACCEL -> "가속"
            ElementKind.DECEL -> "감속"
            ElementKind.STEERING -> "좌우 쏠림"
        }
        if (e.events.isEmpty()) {
            return "기준(${ScoreConfig.EVENT_THRESHOLD}m/s², ${ScoreConfig.EVENT_MIN_SEC.toInt()}초)을 넘는 $noun 없음 → 100점"
        }
        val parts = mutableListOf("$noun ${e.events.size}회", "감점 ${e.deducted.size}회", "위험 ${e.dangerCount}회")
        e.maxPeak?.let { parts += "최대 ${two(it)}m/s²" }
        e.maxJerk?.let { parts += "최대 저크 ${two(it)}m/s³" }
        e.avgDurationSec?.let { parts += "평균 ${one(it)}초 지속" }
        if (e.kind == ElementKind.DECEL) {
            val braked = e.events.count { it.brakeOnSec > 0 }
            parts += "브레이크 사용 ${braked}회 · 엔진·회생 제동 ${e.events.size - braked}회"
        }
        return parts.joinToString(" · ")
    }

    fun pct(rate: Double?): String = if (rate == null) "-" else "${(rate * 100).roundToInt()}%"

    fun gradeColor(ctx: Context, g: Grade): Int = ContextCompat.getColor(
        ctx, when (g) {
            Grade.EXCELLENT -> R.color.grade_excellent
            Grade.SMOOTH -> R.color.grade_smooth
            Grade.NORMAL -> R.color.grade_normal
            Grade.NEEDS_WORK -> R.color.grade_poor
        }
    )

    fun scoreColor(ctx: Context, score: Double): Int = gradeColor(ctx, Grade.of(score))
}
