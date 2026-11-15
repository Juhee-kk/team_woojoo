package com.example.woojoo_1.domain

/**
 * 부드러움 지수 계산에 쓰는 모든 기준값 (개발명세서 5~7장).
 * (A#) = 출처 있는 기준, (B) = 설계값
 */
object ScoreConfig {

    // ── 신호 처리 (6.1) ──────────────────────────────────────────
    /** 분석용 리샘플링 간격 10Hz. CPU 5% 제한 대응 (10.4) */
    const val SAMPLE_DT_SEC = 0.1
    const val SAMPLE_DT_MS = 100L

    /** 이동평균 창 (0.9초). 저크는 두 번 미분한 값이라 노이즈 제거 필수 */
    const val SMOOTH_WINDOW = 9

    /** 미분 시 앞뒤 0.5초씩 → 1초 간격 차분 (명세서 예시와 같은 1초 단위) */
    const val DIFF_HALF_SAMPLES = 5

    /** 수집 단계 최소 저장 간격. 1ms로 들어오는 속도를 그대로 쌓지 않기 위함 */
    const val RECORD_MIN_INTERVAL_MS = 20L

    /** 조향비 15 (A14 중간값, B) */
    const val STEERING_RATIO = 15.0

    /** 축거. Pleos CarInfo API에 축거 항목이 없어 승용차 평균값 사용 (B) */
    const val WHEELBASE_M = 2.9

    // ── 이벤트 판정 (6.2) ────────────────────────────────────────
    const val EVENT_THRESHOLD = 0.3     // m/s² (B)
    const val EVENT_MIN_SEC = 1.0       // 초 (B)

    // ── 이벤트 채점 (6.3~6.5) ───────────────────────────────────
    const val INTENSITY_WEIGHT = 0.4    // 세기 (B)
    const val SUDDENNESS_WEIGHT = 0.6   // 갑작스러움 (B)
    const val JERK_SMOOTH = 1.0         // m/s³ 이하 100점 (A11)
    const val JERK_LIMIT = 3.0          // m/s³ 이상 0점 (A10 반올림)

    // ── 깜빡이 판별 (7.2) ────────────────────────────────────────
    /** 이 각도 이상이면 '핸들을 꺾는 중'으로 본다 */
    const val STEER_ACTIVE_DEG = 3.0
    /** 조작 시작·끝 지점 = 핸들이 이 각도 미만으로 중앙에 있는 순간 */
    const val STEER_REST_DEG = 1.0
    /** 판별 애매 구간으로 집계할 최소 각도 (이보다 작으면 단순 보정 조향) */
    const val STEER_MEANINGFUL_DEG = 10.0
    /** 조향 구간 사이 간격이 이 이하면 하나의 조작으로 묶는다 */
    const val STEER_GROUP_GAP_SEC = 1.0

    const val LANE_CHANGE_MIN_SEC = 3.0
    const val LANE_CHANGE_MAX_SEC = 6.0
    const val LANE_CHANGE_MIN_SPEED_KMH = 20.0
    const val LANE_CHANGE_MAX_DEG = 45.0

    /** 내비 미연동 상태의 교차로 회전 추정: 크게 꺾었다가 원위치 */
    const val TURN_MIN_DEG = 90.0
    const val TURN_MAX_SEC = 20.0
    const val MOVING_SPEED_KMH = 3.0

    /** 깜빡이가 조작 시작 전 이 시간 안에 켜졌어야 '선행'으로 인정 */
    const val SIGNAL_LOOKBACK_SEC = 15.0
    /** 깜빡이가 조작 시작 직전 이 시간 안까지는 켜져 있어야 함 */
    const val SIGNAL_GRACE_SEC = 2.0
    /** 깜빡임(ON/OFF 반복)을 하나의 점등으로 합치는 간격 */
    const val SIGNAL_MERGE_GAP_SEC = 1.5

    /** 확인된 조작이 5회 미만이면 감점 미적용 (7.3) */
    const val SIGNAL_MIN_SAMPLES = 5

    // ── 운행 판정 (8장) ──────────────────────────────────────────
    /** 좌석 점유 변화가 이 시간 이상 유지돼야 승차/하차로 확정 */
    const val SEAT_DEBOUNCE_MS = 3_000L
    /** 이보다 짧은 운행은 판정이 애매하므로 증명서 대상 제외 */
    const val MIN_TRIP_SEC = 30.0

    /** 준수율 → 감점 (7.3) */
    fun signalPenalty(rate: Double): Double = when {
        rate >= 0.9 -> 0.0
        rate >= 0.8 -> 3.0
        rate >= 0.7 -> 6.0
        rate >= 0.6 -> 9.0
        rate >= 0.5 -> 12.0
        else -> 15.0
    }
}
