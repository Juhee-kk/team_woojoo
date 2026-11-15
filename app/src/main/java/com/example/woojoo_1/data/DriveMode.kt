package com.example.woojoo_1.data

import android.content.Context

/** 데이터 출처: 실차 센서 또는 녹화된 주행 재생 (명세서 11.1) */
enum class DriveMode(val label: String, val description: String, val asset: String?) {
    REAL("실차 모드", "차량 센서 값으로 실제 운행을 기록해요", null),
    SOFT("부드러운 운전 재생", "부드러운 운전 시나리오를 10Hz로 재생해요", "replay/soft.csv"),
    ROUGH("거친 운전 재생", "거친 운전 시나리오를 10Hz로 재생해요", "replay/rough.csv");

    val isReplay: Boolean get() = asset != null

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "drive_mode"

        fun load(context: Context): DriveMode {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: REAL
        }

        fun save(context: Context, mode: DriveMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
        }
    }
}

/** 실차 센서와 재생기가 공통으로 구현하는 데이터 출처 */
interface VehicleSource {
    fun start()
    fun startSpeed()
    fun release()
}
