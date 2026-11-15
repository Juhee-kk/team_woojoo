package com.example.woojoo_1.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.woojoo_1.domain.RecordedTrip
import com.example.woojoo_1.domain.ScoreConfig
import com.example.woojoo_1.domain.ScoreEngine
import com.example.woojoo_1.domain.TripReport
import com.example.woojoo_1.domain.TurnSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 좌석 점유로 운행 1회를 나누고, 하차하면 점수를 계산한다 (명세서 8장).
 *
 * 승차: 승객석 중 하나라도 점유 → 3초 유지되면 운행 확정 (점유된 순간부터 기록)
 * 하차: 승객석이 모두 비고 3초 유지되면 운행 종료 (비워진 순간까지 기록)
 * 30초 미만 운행은 판정이 애매하므로 평가하지 않는다.
 *
 * 좌석 상태와 운행 상태는 메인 스레드에서만 다룬다.
 * Logcat 태그 "운행판정" 으로 승차·하차 판정 과정을, "부드러움" 으로 센서 수신을 확인할 수 있다.
 */
class TripMonitor(context: Context, val mode: DriveMode) : VehicleSensors.Listener {

    enum class Phase { WAITING, DRIVING, ANALYZING }

    /** 디버그 패널에 보여줄 실시간 값 */
    data class Debug(
        val speedKmh: Float? = null,
        val steerDeg: Float? = null,
        val brake: Boolean? = null,
        val signal: TurnSignal? = null,
        val speedCallbacks: Long = 0,
        val occupiedSeats: List<String> = emptyList(),
        val knownSeats: Int = 0,
        val accelEvents: Int = 0,
        val decelEvents: Int = 0,
        val steerEvents: Int = 0,
        val samples: Int = 0,
        val pendingAction: String? = null,
    )

    data class State(
        val phase: Phase = Phase.WAITING,
        val tripStartElapsed: Long = 0L,
        val report: TripReport? = null,
        val notice: String? = null,
        val seatStatus: String = "확인 중…",
        val seatSupported: Boolean = true,
        val speedStatus: String = "권한 확인 중…",
        val drivingRestricted: Boolean = false,
        val debug: Debug = Debug(),
        val mode: DriveMode = DriveMode.REAL,
        val replayPlaying: Boolean = false,
        val replayTimeSec: Double = 0.0,
        val replayDurationSec: Double = 0.0,
        val replayCue: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recorder = TripRecorder()
    private val replay: ReplayVehicleSource? =
        if (mode.isReplay) ReplayVehicleSource(context.applicationContext, mode, this, ::onReplayProgress) else null
    private val source: VehicleSource = replay ?: VehicleSensors(context.applicationContext, this)
    private val debugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val seats = LinkedHashMap<String, Boolean>()
    private var pending: Runnable? = null
    private var pendingAction: String? = null
    private var boardElapsed = 0L
    private var leaveElapsed = 0L
    private var leaveWall = 0L
    private var released = false

    @Volatile private var liveCounts = Triple(0, 0, 0)
    @Volatile private var liveSamples = 0
    @Volatile private var liveCountBusy = false
    private var tick = 0
    private var lastLoggedSpeed: Float? = null

    private val _state = MutableStateFlow(State(report = TripRepository.latest, mode = mode))
    val state: StateFlow<State> = _state.asStateFlow()

    /** 0.5초마다 디버그 값 갱신. 운행 중에는 2초마다 이벤트 수 재집계, 5초마다 상태 로그 */
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            tick++
            val phase = _state.value.phase
            if (phase == Phase.DRIVING && tick % 4 == 0) recountLiveEvents()
            if (tick % 10 == 0) logStatus()
            publishDebug()
            main.postDelayed(this, 500)
        }
    }

    fun start() {
        source.start()
        main.post(ticker)
    }

    fun startSpeed() = source.startSpeed()

    /** 재생 모드: 승객 탑승 → 시나리오 재생 → 하차 */
    fun startReplay() {
        if (_state.value.phase != Phase.WAITING) return
        replay?.play()
    }

    private fun onReplayProgress(playing: Boolean, timeSec: Double, durationSec: Double, cue: String?) {
        _state.update {
            it.copy(replayPlaying = playing, replayTimeSec = timeSec, replayDurationSec = durationSec, replayCue = cue)
        }
    }

    fun onSpeedPermissionDenied() {
        Log.w(TAG_TRIP, "속도 권한 거부됨")
        _state.update { it.copy(speedStatus = "권한 거부됨 · 가속·감속 평가 불가") }
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        source.release()
        worker.shutdown()
    }

    // ── 센서 콜백 (임의 스레드) ─────────────────────────────────

    /** 재생 모드에서는 시나리오 시간축을 그대로 쓴다 (메인 스레드 지연으로 샘플이 몰려도 가속도가 왜곡되지 않게) */
    @Volatile private var sampleTimeOverride: Long? = null
    override fun setSampleTime(elapsedMs: Long?) { sampleTimeOverride = elapsedMs }
    private fun sampleTime() = sampleTimeOverride ?: now()

    override fun onSpeed(kmh: Float, rawMps: Float) {
        val t = sampleTime()
        recorder.onSpeed(t, kmh)
        if (debugBuild && recorder.isRecording) logSpeedSample(rawMps, kmh)
    }

    // ── 속도 샘플 로그 (debug 빌드, 운행 기록 중에만) ───────────
    // 필터: adb logcat -s 속도샘플
    private val sampleClock = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
    private var sampleSeq = 0
    private var sampleMaxRaw = 0f
    private var sampleLastRaw: Float? = null

    private fun logSpeedSample(rawMps: Float, kmh: Float) = synchronized(sampleClock) {
        sampleSeq++
        if (rawMps > sampleMaxRaw) sampleMaxRaw = rawMps
        val same = rawMps == sampleLastRaw
        sampleLastRaw = rawMps
        Log.v(
            TAG_SAMPLE,
            String.format(
                Locale.KOREA, "#%d %s  원시 %.3f m/s → %.1f km/h%s",
                sampleSeq, sampleClock.format(Date()), rawMps, kmh, if (same) "  (같은 값)" else "",
            ),
        )
    }

    private fun resetSampleLog() = synchronized(sampleClock) {
        sampleSeq = 0
        sampleMaxRaw = 0f
        sampleLastRaw = null
    }
    override fun onSteering(deg: Float) = recorder.onSteering(sampleTime(), deg)
    override fun onBrake(pressed: Boolean) = recorder.onBrake(sampleTime(), pressed)
    override fun onTurnSignal(signal: TurnSignal) = recorder.onTurnSignal(sampleTime(), signal)

    override fun onSeatOccupancy(seatKey: String, occupied: Boolean) = onMain {
        val before = seats[seatKey]
        seats[seatKey] = occupied
        if (before != occupied) {
            Log.i(
                TAG_TRIP,
                "좌석 변경: ${VehicleSensors.seatLabel(seatKey)} ${mark(before)} → ${mark(occupied)} " +
                    "| 점유 중: ${occupiedLabel()} | 단계=${_state.value.phase}",
            )
        }
        evaluateSeats()
    }

    override fun onPassengerSeats(seatNames: List<String>, error: String?) = onMain {
        if (error != null) Log.w(TAG_TRIP, "좌석 센서 사용 불가: $error")
        else Log.i(TAG_TRIP, "승객석 센서 등록: ${seatNames.joinToString()} (운전석은 판정에서 제외)")
        _state.update {
            if (error != null) it.copy(seatStatus = error, seatSupported = false)
            else it.copy(seatStatus = seatNames.joinToString(", "), seatSupported = true)
        }
    }

    override fun onSpeedStatus(ok: Boolean, message: String) = onMain {
        Log.i(TAG_TRIP, "속도 구독: $message")
        _state.update { it.copy(speedStatus = message) }
    }

    override fun onDrivingRestricted(restricted: Boolean) = onMain {
        _state.update { it.copy(drivingRestricted = restricted) }
    }

    // ── 운행 판정 (메인 스레드) ─────────────────────────────────

    private fun evaluateSeats() {
        val occupied = seats.values.any { it }
        when (_state.value.phase) {
            Phase.WAITING -> {
                if (occupied && pending == null) {
                    boardElapsed = now()
                    resetSampleLog()
                    recorder.begin(boardElapsed, System.currentTimeMillis())
                    Log.i(TAG_TRIP, "승차 감지 (${occupiedLabel()}) → ${debounceSec()}초 유지되면 운행 시작")
                    schedule("운행 시작 확인 중") {
                        if (seats.values.any { it }) {
                            Log.i(TAG_TRIP, "▶ 운행 시작 확정 | 점유 중: ${occupiedLabel()}")
                            liveCounts = Triple(0, 0, 0)
                            liveSamples = 0
                            _state.update { it.copy(phase = Phase.DRIVING, tripStartElapsed = boardElapsed, notice = null) }
                        } else {
                            Log.i(TAG_TRIP, "승차 취소: ${debounceSec()}초 안에 좌석이 다시 비었음")
                            recorder.cancel()
                        }
                    }
                } else if (!occupied && pending != null) {
                    Log.i(TAG_TRIP, "승차 취소: 확정 전에 좌석이 비었음")
                    cancelPending()
                    recorder.cancel()
                }
            }
            Phase.DRIVING -> {
                if (!occupied && pending == null) {
                    leaveElapsed = now()
                    leaveWall = System.currentTimeMillis()
                    Log.i(TAG_TRIP, "하차 감지: 승객석이 모두 빔 → ${debounceSec()}초 유지되면 운행 종료")
                    schedule("운행 종료 확인 중") {
                        if (seats.values.none { it }) {
                            finishTrip()
                        } else {
                            Log.i(TAG_TRIP, "하차 취소: 다시 점유됨 (${occupiedLabel()})")
                        }
                    }
                } else if (occupied && pending != null) {
                    Log.i(TAG_TRIP, "하차 취소: 종료 확정 전에 다시 점유됨 (${occupiedLabel()}) → 운행 계속")
                    cancelPending()
                } else if (occupied) {
                    Log.d(TAG_TRIP, "운행 계속: 아직 점유 중 → ${occupiedLabel()}")
                }
            }
            Phase.ANALYZING -> Log.d(TAG_TRIP, "분석 중이라 좌석 변화는 분석이 끝난 뒤 다시 평가")
        }
    }

    private fun finishTrip() {
        val trip = recorder.finish(leaveElapsed, leaveWall)
        val durationSec = (trip.endMs - trip.startMs) / 1000.0
        Log.i(
            TAG_TRIP,
            "■ 운행 종료 확정: ${fmt(durationSec)}초 | 속도 샘플 ${trip.speedKmh.size}개, 핸들 샘플 ${trip.steeringDeg.size}개",
        )
        if (debugBuild) synchronized(sampleClock) {
            Log.i(
                TAG_SAMPLE,
                "운행 중 속도 콜백 ${sampleSeq}회 | 최고 원시 ${fmt(sampleMaxRaw)} m/s = ${fmt(sampleMaxRaw * VehicleSensors.MPS_TO_KMH)} km/h",
            )
        }
        if (durationSec < ScoreConfig.MIN_TRIP_SEC) {
            Log.i(TAG_TRIP, "평가 제외: ${ScoreConfig.MIN_TRIP_SEC.toInt()}초 미만 운행")
            _state.update {
                it.copy(
                    phase = Phase.WAITING,
                    notice = "운행 시간이 ${durationSec.toInt()}초로 짧아 평가하지 않았어요 (최소 ${ScoreConfig.MIN_TRIP_SEC.toInt()}초)",
                )
            }
            evaluateSeats()
            return
        }
        _state.update { it.copy(phase = Phase.ANALYZING) }
        analyze(trip)
    }

    private fun analyze(trip: RecordedTrip) {
        worker.execute {
            val result = runCatching { ScoreEngine.evaluate(trip) }
            onMain {
                result
                    .onSuccess { r ->
                        Log.i(
                            TAG_TRIP,
                            "점수 계산 완료: 최종 ${r.finalScore} (지수 ${r.smoothIndex} − 깜빡이 ${r.penalty}) " +
                                "| 이벤트 가속 ${r.accel.events.size} / 감속 ${r.decel.events.size} / 핸들 ${r.steering.events.size} " +
                                "| ${r.summary}",
                        )
                        TripRepository.latest = r
                        _state.update { it.copy(phase = Phase.WAITING, report = r, notice = null) }
                    }
                    .onFailure { e ->
                        Log.e(TAG_TRIP, "점수 계산 실패", e)
                        _state.update { it.copy(phase = Phase.WAITING, notice = "점수 계산 중 오류가 발생했어요") }
                    }
                evaluateSeats()
            }
        }
    }

    // ── 디버그 ───────────────────────────────────────────────────

    private fun recountLiveEvents() {
        if (liveCountBusy) return
        val trip = recorder.peek(now(), System.currentTimeMillis()) ?: return
        liveCountBusy = true
        worker.execute {
            try {
                liveCounts = ScoreEngine.liveEventCounts(trip)
                liveSamples = trip.speedKmh.size
            } catch (e: Exception) {
                Log.w(TAG_TRIP, "실시간 이벤트 집계 실패", e)
            } finally {
                liveCountBusy = false
            }
        }
    }

    private fun publishDebug() {
        val live = recorder.live()
        val driving = _state.value.phase == Phase.DRIVING
        val (a, d, s) = if (driving) liveCounts else Triple(0, 0, 0)
        val debug = Debug(
            speedKmh = live.speedKmh,
            steerDeg = live.steerDeg,
            brake = live.brake,
            signal = live.signal,
            speedCallbacks = live.speedCallbacks,
            occupiedSeats = seats.filterValues { it }.keys.map { VehicleSensors.seatLabel(it) },
            knownSeats = seats.size,
            accelEvents = a,
            decelEvents = d,
            steerEvents = s,
            samples = if (driving) liveSamples else 0,
            pendingAction = pendingAction,
        )
        _state.update { it.copy(debug = debug) }

        // 1초마다 현재 속도 로그 (debug 빌드는 항상, release는 값이 바뀔 때만)
        if (tick % 2 == 0 && (debugBuild || live.speedKmh != lastLoggedSpeed)) {
            lastLoggedSpeed = live.speedKmh
            Log.d(TAG_SENSOR, "속도 ${fmt(live.speedKmh)} km/h (콜백 누적 ${live.speedCallbacks}회)")
        }
    }

    private fun logStatus() {
        val live = recorder.live()
        Log.d(
            TAG_TRIP,
            "상태 ${_state.value.phase} | 점유 중: ${occupiedLabel()} | 대기 작업: ${pendingAction ?: "없음"} " +
                "| 속도 ${fmt(live.speedKmh)}km/h 핸들 ${fmt(live.steerDeg)}° " +
                "| 이벤트 가속 ${liveCounts.first}/감속 ${liveCounts.second}/핸들 ${liveCounts.third}",
        )
    }

    // ── 유틸 ─────────────────────────────────────────────────────

    private fun schedule(label: String, action: () -> Unit) {
        val r = Runnable {
            pending = null
            pendingAction = null
            action()
        }
        pending = r
        pendingAction = label
        main.postDelayed(r, ScoreConfig.SEAT_DEBOUNCE_MS)
    }

    private fun cancelPending() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
        pendingAction = null
    }

    private fun occupiedLabel(): String {
        val list = seats.filterValues { it }.keys.map { VehicleSensors.seatLabel(it) }
        return if (list.isEmpty()) "없음" else list.joinToString(", ")
    }

    private fun mark(v: Boolean?) = when (v) {
        true -> "점유"
        false -> "빔"
        null -> "모름"
    }

    private fun debounceSec() = ScoreConfig.SEAT_DEBOUNCE_MS / 1000

    private fun fmt(x: Number?): String = if (x == null) "-" else String.format(Locale.KOREA, "%.1f", x.toDouble())

    private fun onMain(block: () -> Unit) {
        main.post { if (!released) block() }
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val TAG_TRIP = "운행판정"
        const val TAG_SENSOR = "부드러움"
        const val TAG_SAMPLE = "속도샘플"
    }
}
