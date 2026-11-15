package com.example.woojoo_1.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.woojoo_1.domain.ReplayScenario

/**
 * 녹화된 주행 시나리오를 실시간(10Hz)으로 재생한다 (명세서 11.1).
 *
 * 실차 센서와 똑같은 Listener 콜백을 부르므로 운행 판정·점수·화면은 실차 모드와 동일하게 동작한다.
 * - play(): 뒷좌석 오른쪽 '점유' → CSV 시간축대로 속도·핸들·깜빡이·브레이크 방출 → 끝나면 '빈 좌석'
 * - 브레이크: CSV에 없으므로 속도가 줄어드는 샘플을 ON으로 간주 (ReplayScenario 참고)
 */
class ReplayVehicleSource(
    private val context: Context,
    private val mode: DriveMode,
    private val listener: VehicleSensors.Listener,
    private val onProgress: (playing: Boolean, timeSec: Double, durationSec: Double, cue: String?) -> Unit,
) : VehicleSource {

    private val handler = Handler(Looper.getMainLooper())
    private var scenario: ReplayScenario? = null
    private var index = 0
    private var startUptime = 0L
    private var startElapsed = 0L
    private var lastCue: String? = null
    var isPlaying = false
        private set

    override fun start() {
        scenario = try {
            val text = context.assets.open(mode.asset!!).bufferedReader(Charsets.UTF_8).use { it.readText() }
            ReplayScenario.parse(mode.name.lowercase(), text)
        } catch (e: Exception) {
            Log.e(TAG, "시나리오 로드 실패: ${mode.asset}", e)
            null
        }
        val s = scenario
        if (s == null) {
            listener.onPassengerSeats(emptyList(), "재생 파일을 읽을 수 없어요 (${mode.asset})")
            return
        }
        Log.i(TAG, "시나리오 로드: ${mode.label} ${s.rows.size}행, ${s.durationSec}초")
        listener.onPassengerSeats(listOf("뒷좌석 오른쪽 (재생)"), null)
        listener.onSpeedStatus(true, "재생 데이터 · ${mode.label}")
        listener.onSeatOccupancy(SEAT, false)
        s.rows.firstOrNull()?.let { emit(it) }
        onProgress(false, 0.0, s.durationSec, null)
    }

    /** 승객 탑승부터 하차까지 한 번 재생 */
    fun play() {
        val s = scenario ?: return
        if (isPlaying) return
        isPlaying = true
        index = 0
        lastCue = null
        Log.i(TAG, "▶ 재생 시작: ${mode.label}")
        listener.onSeatOccupancy(SEAT, true)
        startUptime = SystemClock.uptimeMillis()
        startElapsed = SystemClock.elapsedRealtime()
        handler.post(step)
        onProgress(true, 0.0, s.durationSec, null)
    }

    fun stop() {
        handler.removeCallbacks(step)
        if (isPlaying) {
            isPlaying = false
            listener.onSeatOccupancy(SEAT, false)
            scenario?.let { onProgress(false, 0.0, it.durationSec, null) }
        }
    }

    /** 경과 시간까지의 행을 모두 방출하고 다음 행 시각에 다시 깨어난다 (지연이 생겨도 따라잡음) */
    private val step = object : Runnable {
        override fun run() {
            val s = scenario ?: return
            val elapsedSec = (SystemClock.uptimeMillis() - startUptime) / 1000.0
            while (index < s.rows.size && s.rows[index].timeSec <= elapsedSec) {
                val row = s.rows[index]
                // 늦게 방출되더라도 원래 시각으로 기록되도록 시나리오 시간을 타임스탬프로 넘긴다
                listener.setSampleTime(startElapsed + (row.timeSec * 1000).toLong())
                emit(row)
                listener.setSampleTime(null)
                row.cue?.let { lastCue = it; Log.i(TAG, "  [%.1fs] %s".format(row.timeSec, it)) }
                index++
            }
            onProgress(true, elapsedSec.coerceAtMost(s.durationSec), s.durationSec, lastCue)
            if (index >= s.rows.size) {
                isPlaying = false
                Log.i(TAG, "■ 재생 끝 → 승객 하차")
                listener.onSeatOccupancy(SEAT, false)
                onProgress(false, s.durationSec, s.durationSec, lastCue)
                return
            }
            handler.postAtTime(this, startUptime + (s.rows[index].timeSec * 1000).toLong())
        }
    }

    private fun emit(r: ReplayScenario.Row) {
        listener.onSpeed(r.speedKmh, r.speedKmh / VehicleSensors.MPS_TO_KMH)
        listener.onSteering(r.steerDeg)
        listener.onBrake(r.brake)
        listener.onTurnSignal(r.signal)
    }

    override fun startSpeed() = Unit   // 재생은 권한 불필요

    override fun release() {
        handler.removeCallbacks(step)
        isPlaying = false
    }

    companion object {
        private const val TAG = "재생"
        const val SEAT = "ROW_2_RIGHT"
    }
}
