package com.example.woojoo_1.data

import com.example.woojoo_1.domain.RecordedTrip
import com.example.woojoo_1.domain.ScoreConfig
import com.example.woojoo_1.domain.Series
import com.example.woojoo_1.domain.StateChange
import com.example.woojoo_1.domain.TurnSignal

/**
 * 센서 값을 운행 단위로 쌓는다. 어떤 스레드에서 불러도 안전하다.
 *
 * - 같은 값 반복 수신(핸들 3.1초 주기 등)은 버린다 (6.1 중복 샘플 제거)
 * - 20ms 안에 연속으로 오는 값은 마지막 값만 남긴다 (1ms 속도 콜백 대응)
 * - 운행 중이 아닐 때도 마지막 값은 기억해서, 운행 시작 시점의 초기값으로 쓴다
 */
class TripRecorder {

    private val lock = Any()
    @Volatile private var recording = false
    val isRecording: Boolean get() = recording

    private var lastSpeed: Float? = null
    private var lastSteer: Float? = null
    private var lastBrake: Boolean? = null
    private var lastSignal: TurnSignal? = null

    private val speed = SeriesBuffer()
    private val steer = SeriesBuffer()
    private val brake = ArrayList<StateChange<Boolean>>()
    private val signal = ArrayList<StateChange<TurnSignal>>()

    private var startMs = 0L
    private var startWallMs = 0L

    /** 디버그용: 속도 콜백 수신 횟수 (중복 값 포함) */
    @Volatile var speedCallbackCount = 0L
        private set

    data class Live(
        val speedKmh: Float?,
        val steerDeg: Float?,
        val brake: Boolean?,
        val signal: TurnSignal?,
        val speedCallbacks: Long,
        val speedSamples: Int,
        val steerSamples: Int,
    )

    fun live(): Live = synchronized(lock) {
        Live(lastSpeed, lastSteer, lastBrake, lastSignal, speedCallbackCount, speed.size, steer.size)
    }

    /** 운행을 멈추지 않고 지금까지의 기록을 복사 (디버그 패널의 실시간 이벤트 집계용) */
    fun peek(nowMs: Long, nowWallMs: Long): RecordedTrip? = synchronized(lock) {
        if (!recording) return null
        RecordedTrip(
            startMs = startMs,
            endMs = nowMs,
            startWallMs = startWallMs,
            endWallMs = nowWallMs,
            speedKmh = speed.snapshot(nowMs),
            steeringDeg = steer.snapshot(nowMs),
            brake = ArrayList(brake),
            signal = ArrayList(signal),
        )
    }

    fun onSpeed(t: Long, kmh: Float) { synchronized(lock) {
        speedCallbackCount++
        if (kmh != lastSpeed) {
            lastSpeed = kmh
            if (recording) speed.add(t, kmh, ScoreConfig.RECORD_MIN_INTERVAL_MS)
        }
    } }

    fun onSteering(t: Long, deg: Float) { synchronized(lock) {
        if (deg != lastSteer) {
            lastSteer = deg
            if (recording) steer.add(t, deg, ScoreConfig.RECORD_MIN_INTERVAL_MS)
        }
    } }

    fun onBrake(t: Long, pressed: Boolean) { synchronized(lock) {
        if (pressed != lastBrake) {
            lastBrake = pressed
            if (recording) brake.add(StateChange(t, pressed))
        }
    } }

    fun onTurnSignal(t: Long, s: TurnSignal) { synchronized(lock) {
        if (s != lastSignal) {
            lastSignal = s
            if (recording) signal.add(StateChange(t, s))
        }
    } }

    fun begin(t: Long, wallMs: Long) { synchronized(lock) {
        clear()
        recording = true
        startMs = t
        startWallMs = wallMs
        lastSpeed?.let { speed.add(t, it, 0) }
        lastSteer?.let { steer.add(t, it, 0) }
        lastBrake?.let { brake.add(StateChange(t, it)) }
        lastSignal?.let { signal.add(StateChange(t, it)) }
    } }

    fun finish(endMs: Long, endWallMs: Long): RecordedTrip = synchronized(lock) {
        recording = false
        val trip = RecordedTrip(
            startMs = startMs,
            endMs = endMs,
            startWallMs = startWallMs,
            endWallMs = endWallMs,
            speedKmh = speed.snapshot(endMs),
            steeringDeg = steer.snapshot(endMs),
            brake = brake.filter { it.timeMs <= endMs },
            signal = signal.filter { it.timeMs <= endMs },
        )
        clear()
        trip
    }

    fun cancel() { synchronized(lock) {
        recording = false
        clear()
    } }

    private fun clear() {
        speed.clear(); steer.clear(); brake.clear(); signal.clear()
    }
}

/** 박싱 없는 시계열 버퍼 */
class SeriesBuffer(initialCapacity: Int = 4096) {
    private var times = LongArray(initialCapacity)
    private var values = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(t: Long, v: Float, minIntervalMs: Long) {
        if (size > 0 && t - times[size - 1] < minIntervalMs) {
            values[size - 1] = v
            return
        }
        if (size == times.size) {
            times = times.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        times[size] = t
        values[size] = v
        size++
    }

    fun clear() {
        size = 0
        if (times.size > 65_536) {   // 긴 운행 후 메모리 반환
            times = LongArray(4096)
            values = FloatArray(4096)
        }
    }

    fun snapshot(untilMs: Long): Series {
        var n = size
        while (n > 0 && times[n - 1] > untilMs) n--
        return Series(times.copyOf(n), values.copyOf(n))
    }
}
