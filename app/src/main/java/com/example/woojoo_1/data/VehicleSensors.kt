package com.example.woojoo_1.data

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.example.woojoo_1.domain.TurnSignal

/**
 * Pleos Vehicle SDK + AAOS CarPropertyManager 연동 (명세서 2장).
 * 모든 콜백은 SDK 스레드에서 올 수 있으므로 Listener 구현 쪽에서 스레드를 처리한다.
 */
class VehicleSensors(private val context: Context, private val listener: Listener) : VehicleSource {

    interface Listener {
        /** kmh = 변환된 속도, rawMps = PERF_VEHICLE_SPEED 원시값(m/s) */
        fun onSpeed(kmh: Float, rawMps: Float)
        fun onSteering(deg: Float)
        fun onBrake(pressed: Boolean)
        fun onTurnSignal(signal: TurnSignal)
        fun onSeatOccupancy(seatKey: String, occupied: Boolean)
        /** 승객석(운전석 제외) 목록. 비어 있으면 미지원 */
        fun onPassengerSeats(seatNames: List<String>, error: String?)
        fun onSpeedStatus(ok: Boolean, message: String)
        fun onDrivingRestricted(restricted: Boolean)
        /** 재생기 전용: 다음 샘플들의 타임스탬프(elapsedRealtime ms)를 지정. null이면 수신 시각 사용 */
        fun setSampleTime(elapsedMs: Long?) {}
    }

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var speedRegistered = false
    private val seatListeners = ArrayList<Pair<SeatArea, SeatOccupancyListener>>()

    // ── 리스너 ───────────────────────────────────────────────────

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            angle?.let { listener.onSteering(it) }
        }
        override fun onFailed(e: Exception) = logFail("핸들", e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) {
            status?.let { listener.onBrake(it) }
        }
        override fun onFailed(e: Exception) = logFail("브레이크", e)
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            val s = when (state) {
                TurnSignalValue.LEFT -> TurnSignal.LEFT
                TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            }
            listener.onTurnSignal(s)
        }
        override fun onFailed(e: Exception) = logFail("깜빡이", e)
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // ⚠️ PERF_VEHICLE_SPEED 단위는 m/s (AAOS VehicleUnit.METER_PER_SEC). km/h로 변환해서 넘긴다.
            //    실측: 콘솔 약 159km/h 일 때 원시값 44.2
            val mps = value.value as? Float ?: return
            listener.onSpeed(mps * MPS_TO_KMH, mps)
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            listener.onSpeedStatus(false, "속도 수신 오류")
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        listener.onDrivingRestricted(r.isRequiresDistractionOptimization)
    }

    // ── 시작 / 해제 ─────────────────────────────────────────────

    override fun start() {
        val v = try {
            Vehicle(context).apply { initialize() }
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle 초기화 실패", e)
            listener.onPassengerSeats(emptyList(), "차량 서비스 연결 실패")
            null
        }
        vehicle = v

        if (v != null) {
            safe("핸들") { v.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
            safe("브레이크") { v.getBrake().registerBrakePedalStatus(brakeListener) }
            safe("깜빡이") { v.getTurnSignal().registerTurnSignalState(turnSignalListener) }
            registerSeats(v)
        }

        try {
            car = Car.createCar(context)
            uxManager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager?.registerListener(uxListener)
            uxManager?.currentCarUxRestrictions?.let {
                listener.onDrivingRestricted(it.isRequiresDistractionOptimization)
            }
        } catch (e: Exception) {
            Log.w(TAG, "주행 규제 정보 사용 불가", e)
        }
    }

    /** CAR_SPEED 런타임 권한을 받은 뒤 호출 */
    @Suppress("DEPRECATION")
    override fun startSpeed() {
        if (speedRegistered) return
        try {
            val c = car ?: Car.createCar(context).also { car = it }
            val mgr = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = mgr
            speedRegistered = mgr.registerCallback(
                speedCallback,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_FASTEST,
            )
            listener.onSpeedStatus(speedRegistered, if (speedRegistered) "연결됨" else "구독 실패")
        } catch (e: Exception) {
            Log.e(TAG, "속도 구독 실패", e)
            listener.onSpeedStatus(false, "연결 실패 (${e.javaClass.simpleName})")
        }
    }

    @Suppress("DEPRECATION")
    override fun release() {
        val v = vehicle
        if (v != null) {
            safe("해제") { v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
            safe("해제") { v.getBrake().unregisterBrakePedalStatus(brakeListener) }
            safe("해제") { v.getTurnSignal().unregisterTurnSignalState(turnSignalListener) }
            for ((area, l) in seatListeners) safe("해제") { v.getSeat().unregisterSeatOccupancy(area, l) }
            safe("해제") { v.release() }
        }
        seatListeners.clear()
        vehicle = null

        safe("해제") { if (speedRegistered) propertyManager?.unregisterCallback(speedCallback) }
        safe("해제") { uxManager?.unregisterListener() }
        safe("해제") { car?.disconnect() }
        speedRegistered = false
        propertyManager = null
        uxManager = null
        car = null
    }

    // ── 좌석 점유 (운행 시작/종료 판정) ─────────────────────────

    private fun registerSeats(v: Vehicle) {
        try {
            v.getSeat().checkSeatOccupancyCapabilities(
                onComplete = { _, areas ->
                    // 운전석(1열 왼쪽)을 뺀 나머지가 승객석
                    val passengers = areas.filter { area -> area.values.none { it == SeatValue.ROW_1_LEFT } }
                    val names = ArrayList<String>()
                    for (area in passengers) {
                        val key = seatKey(area)
                        val l = object : SeatOccupancyListener {
                            override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                                // ⚠️ SDK 2.3.2는 원시값 1(AAOS VACANT)을 true로 변환한다.
                                //    실측: Virtual Console 'Empty' → 원시값 1 → SDK true
                                //    따라서 SDK 값을 뒤집어야 실제 점유 여부가 된다.
                                val sdkValue = occupancy ?: return
                                val occupied = !sdkValue
                                Log.d(TAG, "좌석 $key sdk=$sdkValue → 점유=$occupied")
                                listener.onSeatOccupancy(key, occupied)
                            }
                            override fun onFailed(e: Exception) = logFail("좌석 $key", e)
                        }
                        try {
                            v.getSeat().registerSeatOccupancy(area, l)
                            seatListeners.add(area to l)
                            names.add(seatName(area))
                        } catch (e: Exception) {
                            logFail("좌석 $key", e)
                        }
                    }
                    listener.onPassengerSeats(names, if (names.isEmpty()) "승객석 점유 센서 없음" else null)
                },
                onFailed = { e ->
                    listener.onPassengerSeats(emptyList(), "좌석 점유 미지원 (${e.message})")
                },
            )
        } catch (e: Exception) {
            listener.onPassengerSeats(emptyList(), "좌석 점유 미지원 (${e.javaClass.simpleName})")
        }
    }

    private fun seatKey(area: SeatArea) = area.values.joinToString("+") { it.name }

    private fun seatName(area: SeatArea) = seatLabel(seatKey(area))

    private fun logFail(what: String, e: Exception) {
        Log.w(TAG, "$what 실패: ${e.message}")
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "$what 오류", e) }
    }

    companion object {
        private const val TAG = "부드러움"
        const val MPS_TO_KMH = 3.6f

        /** "ROW_2_RIGHT" 같은 좌석 키 → 한글 이름 */
        fun seatLabel(key: String): String = key.split("+").joinToString("+") {
            when (it) {
                "ROW_1_LEFT" -> "운전석"
                "ROW_1_RIGHT" -> "조수석"
                "ROW_1_CENTER" -> "1열 가운데"
                "ROW_2_LEFT" -> "뒷좌석 왼쪽"
                "ROW_2_CENTER" -> "뒷좌석 가운데"
                "ROW_2_RIGHT" -> "뒷좌석 오른쪽"
                "ROW_3_LEFT" -> "3열 왼쪽"
                "ROW_3_CENTER" -> "3열 가운데"
                "ROW_3_RIGHT" -> "3열 오른쪽"
                else -> it
            }
        }
    }
}
