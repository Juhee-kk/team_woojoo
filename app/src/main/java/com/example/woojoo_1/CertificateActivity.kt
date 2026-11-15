package com.example.woojoo_1

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.woojoo_1.data.TripRepository
import com.example.woojoo_1.domain.ElementKind
import com.example.woojoo_1.domain.ManeuverType
import com.example.woojoo_1.domain.ScoreConfig
import com.example.woojoo_1.domain.ScoredEvent
import com.example.woojoo_1.domain.TripReport
import com.example.woojoo_1.domain.label
import com.example.woojoo_1.ui.CertificatePdf
import com.example.woojoo_1.ui.Format
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/** 운행 승차감 증명서: 항목별 점수와 감점 내역을 수치로 제공 (명세서 9.2~9.3) */
class CertificateActivity : AppCompatActivity() {

    private lateinit var sections: LinearLayout
    private lateinit var inflater: LayoutInflater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = TripRepository.latest
        if (report == null) {
            Toast.makeText(this, R.string.cert_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_certificate)
        inflater = LayoutInflater.from(this)
        sections = findViewById(R.id.certSections)

        findViewById<MaterialButton>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.buttonPdfOpen).setOnClickListener { openPdf() }

        bindHeader(report)
        addOverview(report)
        addElements(report)
        addSignal(report)
        addDeductions(report)
        addCriteria()
        issuePdf(report)
    }

    // ── PDF 발급 ─────────────────────────────────────────────────

    private var pdf: CertificatePdf.Result? = null

    private fun issuePdf(r: TripReport) {
        val status = findViewById<TextView>(R.id.textPdfStatus)
        val open = findViewById<MaterialButton>(R.id.buttonPdfOpen)
        val saved = findViewById<TextView>(R.id.textPdfSaved)
        status.setText(R.string.pdf_making)
        open.isEnabled = false
        saved.isVisible = false
        Thread {
            val result = runCatching { CertificatePdf.create(applicationContext, r) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    pdf = it
                    status.text = getString(R.string.pdf_done)
                    open.isEnabled = true
                    it.savedLocation?.let { loc ->
                        saved.text = getString(R.string.pdf_saved) + "\n" + loc
                        saved.isVisible = true
                    }
                    if (intent.getBooleanExtra(EXTRA_OPEN_PDF, false)) openPdf()
                }.onFailure { e ->
                    Log.e("증명서", "PDF 생성 실패", e)
                    status.text = getString(R.string.pdf_failed)
                }
            }
        }.start()
    }

    /** 차량에는 PDF 뷰어 앱이 없으므로 앱 안 뷰어로 연다 */
    private fun openPdf() {
        val file = pdf?.file ?: return
        startActivity(Intent(this, PdfViewerActivity::class.java).putExtra(PdfViewerActivity.EXTRA_PATH, file.absolutePath))
    }

    // ── 머리 ─────────────────────────────────────────────────────

    private fun bindHeader(r: TripReport) {
        val color = Format.gradeColor(this, r.grade)
        findViewById<TextView>(R.id.certNo).text = "발급번호 ${Format.certificateNo(r)}"
        findViewById<TextView>(R.id.certPeriod).text =
            "${Format.period(r)} · ${Format.duration(r.durationSec)} · ${Format.one(r.distanceKm)}km"
        findViewById<TextView>(R.id.certScore).apply {
            text = Format.score(r.finalScore)
            setTextColor(color)
        }
        findViewById<TextView>(R.id.certGrade).apply {
            text = r.grade.label
            backgroundTintList = ColorStateList.valueOf(color)
        }
        findViewById<TextView>(R.id.certCertified).apply {
            isVisible = r.grade.certified
            text = "부드러운 운전 인증 (80점 이상)"
        }
        findViewById<TextView>(R.id.certSummary).text = r.summary
    }

    // ── 종합 ─────────────────────────────────────────────────────

    private fun addOverview(r: TripReport) {
        val body = section("종합")
        row(body, "최종 점수", "${Format.score(r.finalScore)}점 (${r.grade.label})")
        row(
            body, "부드러움 지수", "${Format.score(r.smoothIndex)}점",
            r.elements.joinToString(" + ") {
                "${it.kind.label} ${Format.score(it.score)}×${(it.kind.weight * 100).roundToInt()}%"
            },
        )
        row(body, "깜빡이 감점", if (r.penalty > 0) "−${Format.score(r.penalty)}점" else "없음")
        row(body, "운행 시간", Format.duration(r.durationSec))
        row(body, "주행 거리", "${Format.one(r.distanceKm)}km")
        row(body, "최고 속도", "${r.maxSpeedKmh.roundToInt()}km/h")
        if (!r.hadSpeedData) {
            row(body, "참고", "속도 데이터 없음", "속도 변화가 기록되지 않아 가속·감속·좌우 쏠림 이벤트가 생기지 않았어요.")
        }
    }

    // ── 요소별 ───────────────────────────────────────────────────

    private fun addElements(r: TripReport) {
        val body = section("요소별 점수")
        for (e in r.elements) {
            row(
                body,
                "${e.kind.label} (가중치 ${(e.kind.weight * 100).roundToInt()}%)",
                "${Format.score(e.score)}점 → ${Format.one(e.contribution)}점 반영",
                Format.elementDetail(e),
            )
        }
    }

    // ── 깜빡이 ───────────────────────────────────────────────────

    private fun addSignal(r: TripReport) {
        val s = r.signal
        val body = section("예고 운전 (깜빡이)")
        val lanes = s.maneuvers.count { it.type == ManeuverType.LANE_CHANGE }
        val turns = s.evaluated - lanes
        row(body, "확인된 조작", "${s.evaluated}회", "차선 변경 ${lanes}회 · 회전 ${turns}회")
        row(body, "깜빡이 선행", "${s.compliant}회")
        row(body, "준수율", Format.pct(s.rate))
        s.avgLeadSec?.let { row(body, "평균 선행 시간", "${Format.one(it)}초 전") }
        row(
            body, "감점",
            when {
                !s.applied -> "미적용"
                s.penalty == 0.0 -> "없음"
                else -> "−${Format.score(s.penalty)}점"
            },
            if (!s.applied) "확인된 조작이 ${ScoreConfig.SIGNAL_MIN_SAMPLES}회 미만이면 감점하지 않아요" else null,
        )
        row(body, "판별 제외 구간", "${s.excludedCount}회", "굽은 도로처럼 차선 변경·회전으로 확신할 수 없는 조향은 평가하지 않아요")
    }

    // ── 감점 내역 ────────────────────────────────────────────────

    private fun addDeductions(r: TripReport) {
        val body = section("감점 내역")

        val events = r.elements.flatMap { e -> e.deducted.map { e to it } }
            .sortedByDescending { (e, ev) -> e.pointsLostBy(ev) }
        val misses = r.signal.maneuvers.filter { !it.signalled }

        if (events.isEmpty() && (misses.isEmpty() || !r.signal.applied)) {
            row(body, "감점된 이벤트", "없음")
        }

        for ((e, ev) in events.take(MAX_EVENT_ROWS)) {
            row(
                body,
                "${Format.clockAt(r, ev.startSec)} · ${ev.kind.label}",
                "−${Format.two(e.pointsLostBy(ev))}점",
                eventDetail(ev),
            )
        }
        if (events.size > MAX_EVENT_ROWS) {
            row(body, "그 외", "${events.size - MAX_EVENT_ROWS}건")
        }

        if (misses.isNotEmpty()) {
            for (m in misses.take(MAX_EVENT_ROWS)) {
                row(
                    body,
                    "${Format.clockAt(r, m.startSec)} · ${m.direction.label} ${m.type.label}",
                    if (m.wrongSignal) "반대쪽 깜빡이" else "깜빡이 없음",
                    "최대 핸들 ${m.peakAngleDeg.roundToInt()}° · ${Format.one(m.durationSec)}초",
                )
            }
            row(
                body, "깜빡이 감점 합계",
                if (r.signal.applied) "−${Format.score(r.signal.penalty)}점" else "미적용 (표본 부족)",
            )
        }
    }

    private fun eventDetail(ev: ScoredEvent): String {
        val k = ev.kind
        if (ev.dangerous) {
            return "세기 ${Format.two(ev.peakAccel)}m/s² ≥ 위험선 ${k.dangerLimit}m/s² → 이벤트 0점 · " +
                "${Format.one(ev.durationSec)}초 지속"
        }
        return "세기 ${Format.two(ev.peakAccel)}m/s² → ${ev.intensityScore.roundToInt()}점 · " +
            "저크 ${Format.two(ev.peakJerk)}m/s³ → ${ev.suddennessScore.roundToInt()}점 · " +
            "이벤트 ${ev.score.roundToInt()}점 · ${Format.one(ev.durationSec)}초 지속"
    }

    // ── 측정 기준 ────────────────────────────────────────────────

    private fun addCriteria() {
        val body = section("측정 기준")
        row(body, "이벤트", "0.3m/s² · 1초 이상", "운전자 조작으로 생긴 흔들림만 집계하며 노면 요철은 제외돼요")
        row(body, "이벤트 점수", "세기 40% + 갑작스러움 60%", "세기가 위험선 이상이면 0점")
        for (k in ElementKind.entries) {
            row(body, k.label, "${k.smoothLimit} ~ ${k.dangerLimit}m/s²", "부드러움 경계 100점 ~ 위험선 0점")
        }
        row(body, "저크", "1 ~ 3m/s³", "1 이하 100점 ~ 3 이상 0점")
        row(
            body, "깜빡이 감점", "최대 −15점",
            "준수율 90% 이상 0점, 이후 10%p마다 −3점 · 확인된 조작 5회 미만은 미적용",
        )
        row(body, "위험선 출처", "교통안전공단 eTAS", "급가속 초당 11km/h · 급감속 초당 7.5km/h")
    }

    // ── 뷰 헬퍼 ──────────────────────────────────────────────────

    private fun section(title: String): LinearLayout {
        val card = inflater.inflate(R.layout.item_cert_section, sections, false)
        card.findViewById<TextView>(R.id.sectionTitle).text = title
        sections.addView(card)
        return card.findViewById(R.id.sectionBody)
    }

    private fun row(parent: LinearLayout, label: String, value: String, detail: String? = null) {
        val v = inflater.inflate(R.layout.item_cert_row, parent, false)
        v.findViewById<TextView>(R.id.rowLabel).text = label
        v.findViewById<TextView>(R.id.rowValue).text = value
        v.findViewById<TextView>(R.id.rowDetail).apply {
            text = detail
            isVisible = detail != null
        }
        parent.addView(v)
    }

    companion object {
        /** true면 PDF 발급 직후 앱 안 뷰어로 연다 */
        const val EXTRA_OPEN_PDF = "openPdf"
        private const val MAX_EVENT_ROWS = 40
    }
}
