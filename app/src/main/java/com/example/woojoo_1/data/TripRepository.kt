package com.example.woojoo_1.data

import com.example.woojoo_1.domain.TripReport

/** 가장 최근 운행 결과. 결과 화면 ↔ 증명서 화면이 공유한다. */
object TripRepository {
    @Volatile
    var latest: TripReport? = null
}
