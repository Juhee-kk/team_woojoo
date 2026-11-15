package com.example.woojoo_1.domain

/**
 * 결과 화면용 한 줄 조언.
 * 예) "감속과 핸들 점수가 낮아요. 브레이크는 미리 천천히, 핸들은 부드럽게 돌려보세요."
 *
 * - 인증 기준(80점) 미만인 요소를 '낮은 점수'로 본다
 * - 요소별 요령을 이어 붙이고 마지막 요령에만 동사를 붙인다
 * - 깜빡이 감점이 있으면 한 문장 덧붙인다
 */
object AdviceWriter {

    const val LOW_SCORE = 80.0

    private class Tip(val phrase: String, val verb: String)

    private fun tipFor(kind: ElementKind) = when (kind) {
        ElementKind.ACCEL -> Tip("출발할 때 가속 페달은 천천히", "밟아보세요")
        ElementKind.DECEL -> Tip("브레이크는 미리 천천히", "밟아보세요")
        ElementKind.STEERING -> Tip("핸들은 부드럽게", "돌려보세요")
    }

    fun write(r: TripReport): String {
        val low = r.elements.filter { it.score < LOW_SCORE }
        val signalBad = r.signal.applied && r.signal.penalty > 0

        if (low.isEmpty() && !signalBad) {
            return if (r.grade == Grade.EXCELLENT) "가속·감속·핸들 모두 부드러워요. 지금처럼 운전해 주세요."
            else "큰 흔들림 없이 운행했어요. 조금만 더 부드럽게 하면 '매우 부드러움'이에요."
        }

        val sentences = ArrayList<String>()
        if (low.isNotEmpty()) {
            sentences += "${joinNames(low.map { it.kind.label })} 점수가 낮아요."
            val tips = low.map { tipFor(it.kind) }
            val body = tips.dropLast(1).map { it.phrase } + "${tips.last().phrase} ${tips.last().verb}"
            sentences += body.joinToString(", ") + "."
        }
        if (signalBad) {
            sentences += "방향을 바꾸기 전에 깜빡이를 먼저 켜 주세요."
        }
        return sentences.joinToString(" ")
    }

    /** ["감속","핸들"] → "감속과 핸들", ["가속","감속","핸들"] → "가속, 감속과 핸들" (모두 받침으로 끝나 '과') */
    private fun joinNames(names: List<String>): String = when (names.size) {
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + "과 " + names.last()
    }
}
