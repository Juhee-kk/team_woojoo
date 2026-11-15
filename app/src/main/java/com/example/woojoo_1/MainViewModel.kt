package com.example.woojoo_1

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.woojoo_1.data.DriveMode
import com.example.woojoo_1.data.TripMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 화면 회전·재생성과 무관하게 운행 기록이 이어지도록 데이터 출처를 ViewModel이 소유한다.
 * 설정에서 모드(실차 / soft 재생 / rough 재생)를 바꾸면 모니터를 새로 만든다.
 */
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private var monitor: TripMonitor = TripMonitor(app, DriveMode.load(app)).also { it.start() }
    private var collectJob: Job? = null
    private var speedGranted = false

    private val _state = MutableStateFlow(monitor.state.value)
    val state: StateFlow<TripMonitor.State> = _state.asStateFlow()

    val mode: DriveMode get() = monitor.mode

    init {
        collect()
    }

    fun setMode(mode: DriveMode) {
        if (mode == monitor.mode) return
        DriveMode.save(app, mode)
        collectJob?.cancel()
        monitor.release()
        monitor = TripMonitor(app, mode).also { it.start() }
        if (speedGranted) monitor.startSpeed()
        collect()
    }

    fun startReplay() = monitor.startReplay()

    fun onSpeedPermissionGranted() {
        speedGranted = true
        monitor.startSpeed()
    }

    fun onSpeedPermissionDenied() = monitor.onSpeedPermissionDenied()

    private fun collect() {
        val m = monitor
        collectJob = viewModelScope.launch { m.state.collect { _state.value = it } }
    }

    override fun onCleared() {
        monitor.release()
    }
}
