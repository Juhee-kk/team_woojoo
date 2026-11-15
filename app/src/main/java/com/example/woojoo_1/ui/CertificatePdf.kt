package com.example.woojoo_1.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.woojoo_1.domain.Grade
import com.example.woojoo_1.domain.ScoreConfig
import com.example.woojoo_1.domain.TripReport
import com.example.woojoo_1.domain.label
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 운행 승차감 증명서 PDF (A4, 595×842pt)
 * 화면 테마와 무관하게 인쇄용 밝은 색으로 그린다.
 */
object CertificatePdf {

    class Result(
        /** 앱 캐시의 PDF (앱 안 뷰어로 열기) */
        val file: File,
        /** 사용자가 찾을 수 있는 저장 위치 설명 (예: "다운로드/운행승차감증명서_….pdf") */
        val savedLocation: String?,
    )

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val FOOTER_H = 28f

    // 인쇄용 고정 팔레트
    private val INK = Color.parseColor("#1F2933")
    private val INK_SOFT = Color.parseColor("#5B6573")
    private val LINE = Color.parseColor("#E3E8EE")
    private val TAXI = Color.parseColor("#FFC629")       // 택시 노랑 (채움·장식)
    private val TAXI_INK = Color.parseColor("#7A5200")   // 노랑 계열 진한 글자
    private val TAXI_BG = Color.parseColor("#FFF3C4")    // 머리말·표 머리 배경
    private val CREAM = Color.parseColor("#FFFBEB")
    private val SAFE = Color.parseColor("#1F8A6B")       // 인증 도장
    private val TRACK = Color.parseColor("#EDF1F5")

    fun gradeColor(g: Grade): Int = Color.parseColor(
        when (g) {
            Grade.EXCELLENT -> "#1F8A6B"
            Grade.SMOOTH -> "#2563C9"
            Grade.NORMAL -> "#B26A00"
            Grade.NEEDS_WORK -> "#C7362B"
        }
    )

    fun fileName(r: TripReport) = "운행승차감증명서_${Format.certificateNo(r)}.pdf"

    /** PDF를 만들어 앱 캐시에 쓰고, 다운로드 폴더에도 복사한다 */
    fun create(context: Context, r: TripReport): Result {
        val dir = File(context.cacheDir, "certificates").apply { mkdirs() }
        val file = File(dir, fileName(r))
        file.outputStream().use { write(r, it) }
        val saved = try {
            saveCopy(context, file)
        } catch (e: Exception) {
            null
        }
        return Result(file, saved)
    }

    private fun saveCopy(context: Context, src: File): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri: Uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            "다운로드/${src.name}"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null
            val dst = File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        }
    }

    // ═══════════════════════ 그리기 ═══════════════════════

    fun write(r: TripReport, out: OutputStream) {
        val doc = PdfDocument()
        val pen = Pen(doc)
        pen.newPage()

        header(pen, r)
        scoreBlock(pen, r)
        elementBars(pen, r)
        signalSection(pen, r)
        deductionTable(pen, r)
        criteria(pen)

        pen.finishPage()
        doc.writeTo(out)
        doc.close()
    }

    private fun header(pen: Pen, r: TripReport) {
        val c = pen.canvas
        val box = RectF(MARGIN, pen.y, PAGE_W - MARGIN, pen.y + 92f)
        c.drawRoundRect(box, 18f, 18f, fill(TAXI_BG))
        // 작은 장식 원
        c.drawCircle(box.right - 34f, box.top + 30f, 9f, fill(TAXI))
        c.drawCircle(box.right - 56f, box.top + 50f, 5f, fill(Color.parseColor("#FFE38A")))

        c.drawText("운행 승차감 증명서", box.left + 22f, box.top + 40f, text(24f, TAXI_INK, bold = true))
        c.drawText(
            "부드러움 지수 · Smoothness Ride Certificate", box.left + 22f, box.top + 60f, text(10f, INK_SOFT),
        )
        val issued = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date())
        c.drawText(
            "발급번호 ${Format.certificateNo(r)}   ·   발급 $issued", box.left + 22f, box.top + 78f, text(9f, INK_SOFT),
        )
        pen.y = box.bottom + 14f

        val info = "운행 ${Format.period(r)}  ·  ${Format.duration(r.durationSec)}  ·  " +
            "${Format.one(r.distanceKm)}km  ·  최고 ${r.maxSpeedKmh.roundToInt()}km/h"
        c.drawText(info, MARGIN + 4f, pen.y + 10f, text(11f, INK))
        pen.y += 24f
    }

    private fun scoreBlock(pen: Pen, r: TripReport) {
        val c = pen.canvas
        val gc = gradeColor(r.grade)
        val box = RectF(MARGIN, pen.y, PAGE_W - MARGIN, pen.y + 118f)
        c.drawRoundRect(box, 18f, 18f, fill(CREAM))

        val scoreText = Format.score(r.finalScore)
        val big = text(52f, gc, bold = true)
        c.drawText(scoreText, box.left + 24f, box.top + 66f, big)
        val sx = box.left + 24f + big.measureText(scoreText) + 6f
        c.drawText("점", sx, box.top + 66f, text(18f, INK, bold = true))
        c.drawText(r.grade.label, box.left + 26f, box.top + 92f, text(14f, gc, bold = true))
        val sub = "부드러움 지수 ${Format.score(r.smoothIndex)}" +
            (if (r.penalty > 0) "  −  깜빡이 감점 ${Format.score(r.penalty)}" else "  ·  깜빡이 감점 없음")
        c.drawText(sub, box.left + 26f, box.top + 108f, text(9.5f, INK_SOFT))

        // 요약·조언
        val textLeft = box.left + 190f
        val textRight = box.right - (if (r.grade.certified) 108f else 20f)
        var ty = box.top + 18f
        ty += paragraph(c, "한 줄 요약", textLeft, ty, textRight - textLeft, text(9f, INK_SOFT, bold = true))
        ty += paragraph(c, r.summary, textLeft, ty, textRight - textLeft, text(10.5f, INK)) + 6f
        ty += paragraph(c, "운전 요령", textLeft, ty, textRight - textLeft, text(9f, INK_SOFT, bold = true))
        paragraph(c, r.advice, textLeft, ty, textRight - textLeft, text(10.5f, INK))

        if (r.grade.certified) stamp(c, box.right - 56f, box.centerY())
        pen.y = box.bottom + 22f
    }

    /** "부드러운 운전 인증" 도장 */
    private fun stamp(c: Canvas, cx: Float, cy: Float) {
        c.save()
        c.rotate(-12f, cx, cy)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = SAFE; strokeWidth = 2.5f }
        c.drawCircle(cx, cy, 40f, ring)
        ring.strokeWidth = 1f
        c.drawCircle(cx, cy, 34f, ring)
        val t = text(11f, SAFE, bold = true).apply { textAlign = Paint.Align.CENTER }
        c.drawText("부드러운", cx, cy - 2f, t)
        c.drawText("운전 인증", cx, cy + 13f, t)
        c.restore()
    }

    private fun elementBars(pen: Pen, r: TripReport) {
        sectionTitle(pen, "요소별 점수", "가속 25% · 감속 25% · 핸들 50%")
        for (e in r.elements) {
            pen.ensure(52f)
            val c = pen.canvas
            val gc = gradeColor(Grade.of(e.score))
            val y = pen.y
            c.drawText(e.kind.label, MARGIN + 4f, y + 14f, text(12f, INK, bold = true))
            val trackL = MARGIN + 50f
            val trackR = PAGE_W - MARGIN - 110f
            c.drawRoundRect(RectF(trackL, y + 4f, trackR, y + 16f), 6f, 6f, fill(TRACK))
            val w = (trackR - trackL) * (e.score / 100.0).toFloat().coerceIn(0f, 1f)
            if (w > 0) c.drawRoundRect(RectF(trackL, y + 4f, trackL + w, y + 16f), 6f, 6f, fill(gc))
            c.drawText(
                "${Format.score(e.score)}점 → ${Format.one(e.contribution)}",
                PAGE_W - MARGIN - 102f, y + 14f, text(11f, gc, bold = true),
            )
            val h = paragraph(c, Format.elementDetail(e), trackL, y + 22f, trackR - trackL + 100f, text(8.5f, INK_SOFT))
            pen.y += 26f + h
        }
        pen.y += 6f
    }

    private fun signalSection(pen: Pen, r: TripReport) {
        val s = r.signal
        sectionTitle(pen, "예고 운전 (깜빡이)", Format.signalLine(r))
        val lanes = s.maneuvers.count { it.type.name == "LANE_CHANGE" }
        val lines = listOf(
            "확인된 조작" to "${s.evaluated}회 (차선 변경 $lanes · 회전 ${s.evaluated - lanes})",
            "깜빡이 선행" to "${s.compliant}회" + (s.avgLeadSec?.let { " · 평균 ${Format.one(it)}초 전" } ?: ""),
            "준수율 / 감점" to "${Format.pct(s.rate)} / " + when {
                !s.applied -> "미적용 (확인된 조작 ${ScoreConfig.SIGNAL_MIN_SAMPLES}회 미만)"
                s.penalty == 0.0 -> "없음"
                else -> "−${Format.score(s.penalty)}점"
            },
            "판별 제외 구간" to "${s.excludedCount}회 (굽은 도로 등 확신할 수 없는 조향)",
        )
        keyValues(pen, lines)
    }

    private fun deductionTable(pen: Pen, r: TripReport) {
        val events = r.elements.flatMap { e -> e.deducted.map { e to it } }
            .sortedByDescending { (e, ev) -> e.pointsLostBy(ev) }
        val misses = r.signal.maneuvers.filter { !it.signalled }
        sectionTitle(pen, "감점 내역", "최종 점수에서 깎인 점수가 큰 순서")

        val cols = floatArrayOf(0f, 62f, 112f, 220f, 330f, 400f, 470f)
        val heads = listOf("시각", "항목", "세기 (m/s²)", "저크 (m/s³)", "이벤트", "구간", "감점")
        fun row(values: List<String>, bold: Boolean, color: Int, bg: Int?) {
            pen.ensure(18f)
            val c = pen.canvas
            if (bg != null) c.drawRoundRect(RectF(MARGIN, pen.y, PAGE_W - MARGIN, pen.y + 17f), 6f, 6f, fill(bg))
            val p = text(9f, color, bold)
            values.forEachIndexed { i, v -> c.drawText(v, MARGIN + 6f + cols[i], pen.y + 12f, p) }
            pen.y += 18f
        }

        if (events.isEmpty() && misses.isEmpty()) {
            pen.ensure(20f)
            pen.canvas.drawText("감점된 이벤트가 없어요.", MARGIN + 6f, pen.y + 12f, text(10f, INK_SOFT))
            pen.y += 22f
            return
        }
        row(heads, true, INK_SOFT, TAXI_BG)
        for ((e, ev) in events) {
            val danger = ev.dangerous
            row(
                listOf(
                    Format.clockAt(r, ev.startSec),
                    ev.kind.label,
                    "${Format.two(ev.peakAccel)} → ${ev.intensityScore.roundToInt()}" + if (danger) " 위험" else "",
                    "${Format.two(ev.peakJerk)} → ${ev.suddennessScore.roundToInt()}",
                    "${ev.score.roundToInt()}점",
                    "${Format.one(ev.durationSec)}초",
                    "−${Format.two(e.pointsLostBy(ev))}",
                ),
                bold = false,
                color = if (danger) gradeColor(Grade.NEEDS_WORK) else INK,
                bg = null,
            )
        }
        for (m in misses) {
            row(
                listOf(
                    Format.clockAt(r, m.startSec),
                    "깜빡이",
                    "${m.direction.label} ${m.type.label}",
                    if (m.wrongSignal) "반대쪽 깜빡이" else "깜빡이 없음",
                    "핸들 ${m.peakAngleDeg.roundToInt()}°",
                    "${Format.one(m.durationSec)}초",
                    if (r.signal.applied) "준수율 반영" else "미적용",
                ),
                bold = false, color = INK, bg = null,
            )
        }
        pen.y += 8f
    }

    private fun criteria(pen: Pen) {
        pen.ensure(150f)   // 섹션이 쪽 경계에서 쪼개지지 않게 통째로 넘긴다
        sectionTitle(pen, "측정 기준", null)
        val lines = listOf(
            "이벤트: 가속도 0.3m/s² 이상이 1초 이상 이어진 구간 (운전자 조작만 집계, 노면 요철 제외)",
            "이벤트 점수 = 세기 40% + 갑작스러움(저크) 60% · 세기가 위험선 이상이면 0점",
            "가속 1.5~3.1m/s² · 감속 1.0~2.1m/s² · 좌우 쏠림 1.0~2.1m/s² (100점~0점) · 저크 1~3m/s³",
            "위험선: 교통안전공단 eTAS 급가속 초당 11km/h, 급감속 초당 7.5km/h",
            "깜빡이: 준수율 90% 이상 감점 없음, 이후 10%p마다 −3점 (최대 −15점) · 확인된 조작 5회 미만 미적용",
            "급제동 등 돌발 상황의 정당성은 앱이 판단하지 않습니다. 필요 시 운행 기록으로 확인해 주세요.",
        )
        for (l in lines) {
            pen.ensure(16f)
            val h = paragraph(pen.canvas, "· $l", MARGIN + 4f, pen.y, PAGE_W - 2 * MARGIN - 8f, text(8.5f, INK_SOFT))
            pen.y += h + 3f
        }
    }

    // ═══════════════════════ 도구 ═══════════════════════

    private fun sectionTitle(pen: Pen, title: String, sub: String?) {
        pen.ensure(40f)
        val c = pen.canvas
        c.drawCircle(MARGIN + 6f, pen.y + 9f, 5f, fill(TAXI))
        c.drawText(title, MARGIN + 16f, pen.y + 14f, text(13f, INK, bold = true))
        if (sub != null) {
            val tp = text(9f, INK_SOFT)
            c.drawText(sub, PAGE_W - MARGIN - tp.measureText(sub), pen.y + 14f, tp)
        }
        c.drawLine(MARGIN, pen.y + 22f, PAGE_W - MARGIN, pen.y + 22f, fill(LINE).apply { strokeWidth = 1f })
        pen.y += 30f
    }

    private fun keyValues(pen: Pen, items: List<Pair<String, String>>) {
        for ((k, v) in items) {
            pen.ensure(18f)
            pen.canvas.drawText(k, MARGIN + 6f, pen.y + 12f, text(10f, INK_SOFT))
            pen.canvas.drawText(v, MARGIN + 120f, pen.y + 12f, text(10f, INK, bold = true))
            pen.y += 18f
        }
        pen.y += 10f
    }

    /** 줄바꿈 문단을 그리고 높이를 돌려준다 */
    private fun paragraph(c: Canvas, s: String, x: Float, y: Float, width: Float, paint: TextPaint): Float {
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, width.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(1.5f, 1f)
            .build()
        c.save()
        c.translate(x, y)
        layout.draw(c)
        c.restore()
        return layout.height.toFloat()
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    /** 쪽 넘김을 관리하는 붓 */
    private class Pen(val doc: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var number = 0
        lateinit var canvas: Canvas
        var y = MARGIN

        fun newPage() {
            number++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create())
            page = p
            canvas = p.canvas
            canvas.drawColor(Color.WHITE)
            y = MARGIN
        }

        fun ensure(h: Float) {
            if (y + h > PAGE_H - MARGIN - FOOTER_H) {
                finishPage()
                newPage()
            }
        }

        fun finishPage() {
            val p = page ?: return
            val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.parseColor("#8A94A3") }
            canvas.drawText(
                "부드러움 지수 · 차량 센서 데이터로 자동 산출된 증명서입니다", MARGIN, PAGE_H - MARGIN + 8f, foot,
            )
            val pn = "$number"
            canvas.drawText(pn, PAGE_W - MARGIN - foot.measureText(pn), PAGE_H - MARGIN + 8f, foot)
            doc.finishPage(p)
            page = null
        }
    }
}
