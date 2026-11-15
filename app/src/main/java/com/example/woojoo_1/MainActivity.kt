package com.example.woojoo_1

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.Log
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Chronometer
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.woojoo_1.data.DriveMode
import com.example.woojoo_1.data.TripMonitor
import androidx.core.text.inSpans
import com.example.woojoo_1.domain.Grade
import com.example.woojoo_1.domain.TripReport
import com.example.woojoo_1.ui.Format
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private lateinit var cardResult: View
    private lateinit var textResultPeriod: TextView
    private lateinit var textHeadline: TextView
    private lateinit var textIndexBreakdown: TextView
    private lateinit var textAdvice: TextView
    private lateinit var textSignal: TextView
    private lateinit var buttonCertificate: MaterialButton
    private lateinit var textRestricted: TextView
    private lateinit var textStatusTitle: TextView
    private lateinit var textStatusBody: TextView
    private lateinit var chronoTrip: Chronometer
    private lateinit var textNotice: TextView
    private lateinit var textSensors: TextView
    private lateinit var cardDebug: View
    private lateinit var textDebugSpeed: TextView
    private lateinit var textDebug: TextView
    private var debugBuild = false
    private lateinit var textSafety: TextView
    private lateinit var textModeChip: TextView
    private lateinit var buttonReplay: MaterialButton
    private lateinit var replayProgress: LinearProgressIndicator
    private lateinit var textReplayInfo: TextView

    private val speedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.onSpeedPermissionGranted() else vm.onSpeedPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        // CCP 정책: 최상단 화면에서 Back → 앱을 종료하지 않고 뒤로 보냄 (명세서 10.1)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        // 증명서 화면이 열리면서 PDF 증명서가 발급된다
        buttonCertificate.setOnClickListener {
            startActivity(Intent(this, CertificateActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.buttonSettings).setOnClickListener { showSettings() }
        buttonReplay.setOnClickListener { vm.startReplay() }

        // 속도는 런타임 권한 (명세서 2.2, 13.1)
        if (ContextCompat.checkSelfPermission(this, PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
            vm.onSpeedPermissionGranted()
        } else if (savedInstanceState == null) {
            speedPermission.launch(PERMISSION_SPEED)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * debug 빌드 전용 adb 제어 (시연·검증용)
     *   adb shell am start --user 10 -n com.example.woojoo_1/.MainActivity --es mode ROUGH --ez autoplay true
     *   adb shell am start --user 10 --activity-single-top -n com.example.woojoo_1/.MainActivity --ez certificate true
     * (앱이 이미 떠 있으면 --activity-single-top 이 있어야 onNewIntent 로 전달된다)
     * mode: REAL | SOFT | ROUGH
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!debugBuild || intent == null) return
        Log.d("디버그제어", "extras=${intent.extras?.keySet()} report=${vm.state.value.report != null}")
        intent.getStringExtra("mode")?.let { name ->
            DriveMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { vm.setMode(it) }
        }
        if (intent.getBooleanExtra("autoplay", false)) vm.startReplay()
        val pdf = intent.getBooleanExtra("pdf", false)
        if ((intent.getBooleanExtra("certificate", false) || pdf) && vm.state.value.report != null) {
            startActivity(Intent(this, CertificateActivity::class.java).putExtra(CertificateActivity.EXTRA_OPEN_PDF, pdf))
        }
        intent.removeExtra("mode"); intent.removeExtra("autoplay"); intent.removeExtra("certificate"); intent.removeExtra("pdf")
    }

    private fun bindViews() {
        cardResult = findViewById(R.id.cardResult)
        textResultPeriod = findViewById(R.id.textResultPeriod)
        textHeadline = findViewById(R.id.textHeadline)
        textIndexBreakdown = findViewById(R.id.textIndexBreakdown)
        textAdvice = findViewById(R.id.textAdvice)
        textSignal = findViewById(R.id.textSignal)
        buttonCertificate = findViewById(R.id.buttonCertificate)
        textRestricted = findViewById(R.id.textRestricted)
        textStatusTitle = findViewById(R.id.textStatusTitle)
        textStatusBody = findViewById(R.id.textStatusBody)
        chronoTrip = findViewById(R.id.chronoTrip)
        textNotice = findViewById(R.id.textNotice)
        textSensors = findViewById(R.id.textSensors)
        cardDebug = findViewById(R.id.cardDebug)
        textDebugSpeed = findViewById(R.id.textDebugSpeed)
        textDebug = findViewById(R.id.textDebug)
        textSafety = findViewById(R.id.textSafety)
        textModeChip = findViewById(R.id.textModeChip)
        buttonReplay = findViewById(R.id.buttonReplay)
        replayProgress = findViewById(R.id.replayProgress)
        textReplayInfo = findViewById(R.id.textReplayInfo)
        debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        cardDebug.isVisible = debugBuild
    }

    private fun render(s: TripMonitor.State) {
        // 결과는 정차(대기) 상태에서만 보여준다 (명세서 9.2)
        val report = s.report
        val showResult = report != null && s.phase == TripMonitor.Phase.WAITING
        cardResult.isVisible = showResult
        if (showResult) bindReport(report, s.drivingRestricted)

        when (s.phase) {
            TripMonitor.Phase.WAITING -> {
                textStatusTitle.setText(if (report == null) R.string.status_waiting_first else R.string.status_waiting_next)
                textStatusBody.setText(if (s.seatSupported) R.string.status_waiting_body else R.string.status_no_seat_body)
                chronoTrip.stop()
                chronoTrip.isVisible = false
            }
            TripMonitor.Phase.DRIVING -> {
                textStatusTitle.setText(R.string.status_driving)
                textStatusBody.setText(R.string.status_driving_body)
                chronoTrip.base = s.tripStartElapsed
                chronoTrip.isVisible = true
                chronoTrip.start()
            }
            TripMonitor.Phase.ANALYZING -> {
                textStatusTitle.setText(R.string.status_analyzing)
                textStatusBody.setText(R.string.status_analyzing_body)
                chronoTrip.stop()
                chronoTrip.isVisible = false
            }
        }

        renderReplay(s)
        textNotice.text = s.notice
        textNotice.isVisible = s.notice != null
        textSensors.text = "승객석 센서: ${s.seatStatus}\n속도: ${s.speedStatus}"
        if (debugBuild) renderDebug(s)
    }

    /** 재생 모드: 모드 표시, 재생 시작 버튼, 진행 막대 */
    private fun renderReplay(s: TripMonitor.State) {
        val replay = s.mode.isReplay
        textModeChip.isVisible = replay
        if (replay) textModeChip.text = "재생 모드 · ${s.mode.label.removeSuffix(" 재생")}"
        buttonReplay.isVisible = replay && s.phase == TripMonitor.Phase.WAITING && !s.replayPlaying
        val showProgress = replay && s.replayPlaying
        replayProgress.isVisible = showProgress
        textReplayInfo.isVisible = showProgress
        if (showProgress && s.replayDurationSec > 0) {
            replayProgress.progress = (s.replayTimeSec / s.replayDurationSec * 1000).roundToInt()
            textReplayInfo.text = "재생 ${s.replayTimeSec.toInt()} / ${s.replayDurationSec.toInt()}초" +
                (s.replayCue?.let { " · $it" } ?: "")
        }
    }

    /** 설정: 실차 모드 / soft 재생 / rough 재생 */
    private fun showSettings() {
        val modes = DriveMode.entries
        val labels = modes.map { "${it.label}\n${it.description}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setSingleChoiceItems(labels, modes.indexOf(vm.mode)) { dialog, which ->
                vm.setMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun renderDebug(s: TripMonitor.State) {
        val d = s.debug
        fun f(x: Float?) = if (x == null) "-" else String.format(Locale.KOREA, "%.1f", x)
        textDebugSpeed.text = "${f(d.speedKmh)} km/h"
        val driving = s.phase == TripMonitor.Phase.DRIVING
        val brake = when (d.brake) { true -> "ON"; false -> "OFF"; null -> "-" }
        textDebug.text = buildString {
            appendLine("속도 콜백 누적  ${d.speedCallbacks}회")
            appendLine("핸들   ${f(d.steerDeg)}°")
            appendLine("브레이크 $brake · 깜빡이 ${d.signal ?: "-"}")
            if (driving) {
                appendLine("이벤트 가속 ${d.accelEvents} / 감속 ${d.decelEvents} / 핸들 ${d.steerEvents}")
                appendLine("속도 샘플 ${d.samples}개 (2초마다 재집계)")
            } else {
                appendLine("이벤트  운행 중 아님")
            }
            appendLine("점유 좌석: ${d.occupiedSeats.joinToString().ifEmpty { "없음" }} (${d.knownSeats}석 감시)")
            s.replayCue?.let { appendLine("재생 큐: $it") }
            append("상태: ${s.phase}${d.pendingAction?.let { " · $it" } ?: ""}")
        }
    }

    private fun bindReport(r: TripReport, restricted: Boolean) {
        textResultPeriod.text = "${Format.period(r)} · ${Format.duration(r.durationSec)}"
        textHeadline.text = headline(r)

        if (r.penalty > 0) {
            textIndexBreakdown.text =
                "깜빡이 감점 −${Format.score(r.penalty)}점 반영 (감점 전 ${r.smoothIndex.roundToInt()}점)"
            textIndexBreakdown.isVisible = true
        } else {
            textIndexBreakdown.isVisible = false
        }

        // 안전 운전(인증 80점 이상) / 주의 운전(70~79) / 위험 운전(70 미만)
        val (safety, safetyColor) = when (r.grade) {
            Grade.EXCELLENT, Grade.SMOOTH -> "안전 운전" to R.color.grade_excellent
            Grade.NORMAL -> "주의 운전" to R.color.grade_normal
            Grade.NEEDS_WORK -> "위험 운전" to R.color.grade_poor
        }
        textSafety.text = safety
        textSafety.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, safetyColor))
        textAdvice.text = r.advice
        textSignal.text = Format.signalLine(r)

        buttonCertificate.isEnabled = !restricted
        textRestricted.isVisible = restricted
    }

    /** "부드러움 지수 65점 · 개선 필요! (가속 82 / 감속 58 / 핸들 60)" */
    private fun headline(r: TripReport): CharSequence {
        val gradeColor = Format.gradeColor(this, r.grade)
        val gradeText = if (r.grade == Grade.NEEDS_WORK) "${r.grade.label}!" else r.grade.label
        return SpannableStringBuilder().apply {
            append("부드러움 지수 ")
            inSpans(ForegroundColorSpan(gradeColor), StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.6f)) {
                append("${r.finalScore.roundToInt()}점")
            }
            append(" · ")
            inSpans(ForegroundColorSpan(gradeColor), StyleSpan(Typeface.BOLD)) { append(gradeText) }
            append(" (")
            r.elements.forEachIndexed { i, e ->
                if (i > 0) append(" / ")
                append("${e.kind.label} ")
                inSpans(ForegroundColorSpan(Format.scoreColor(this@MainActivity, e.score)), StyleSpan(Typeface.BOLD)) {
                    append("${e.score.roundToInt()}")
                }
            }
            append(")")
        }
    }

    companion object {
        private const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"
    }
}
