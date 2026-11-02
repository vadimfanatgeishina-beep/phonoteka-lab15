package com.phonoteka.security

/**
 * ЛР15, Шаг 8, сценарий «часы сдвинуты»: понятный текст вместо общей транспортной ошибки.
 *
 * Сервер принимает подпись только в окне ±[WINDOW_SECONDS] секунд от своего времени
 * (`HmacRequestSigner.verify`, `ReplayGuard`). Если часы устройства ушли за это окно,
 * запрос отклоняется как EXPIRED, и пользователю нужно объяснить причину и что делать,
 * а не показывать «401».
 *
 * Сдвиг считается как «часы устройства минус часы сервера»:
 * положительный — устройство спешит, отрицательный — отстаёт.
 */
object ClockSkewNotice {
    const val WINDOW_SECONDS = 30L

    /** Сдвиг часов устройства относительно сервера в секундах; при переполнении — 0. */
    fun driftSeconds(deviceSeconds: Long, serverSeconds: Long): Long =
        runCatching { Math.subtractExact(deviceSeconds, serverSeconds) }.getOrDefault(0L)

    /** Готовая фраза для пользователя по двум отметкам времени. */
    fun describe(
        deviceSeconds: Long,
        serverSeconds: Long,
        windowSeconds: Long = WINDOW_SECONDS
    ): String {
        val drift = driftSeconds(deviceSeconds, serverSeconds)
        val magnitude = if (drift == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(drift)
        return when {
            magnitude <= windowSeconds ->
                "Расхождение часов $magnitude с — в пределах окна ±$windowSeconds с, подпись проходит."
            drift > 0 ->
                "Часы устройства спешат на $magnitude с: сервер принимает подпись только в окне " +
                    "±$windowSeconds с, поэтому запрос отклонён. Включите автоматическую синхронизацию " +
                    "времени (Настройки → Дата и время) и повторите запрос заново — старую подпись " +
                    "повторять нельзя, у неё уже истёк nonce."
            else ->
                "Часы устройства отстают на $magnitude с: сервер принимает подпись только в окне " +
                    "±$windowSeconds с, поэтому запрос отклонён. Включите автоматическую синхронизацию " +
                    "времени (Настройки → Дата и время) и повторите запрос заново — старую подпись " +
                    "повторять нельзя, у неё уже истёк nonce."
        }
    }

    /** Текст по результату проверки: для EXPIRED объясняет причину, для остальных — короткий итог. */
    fun describeResult(
        result: VerificationResult,
        deviceSeconds: Long,
        serverSeconds: Long,
        windowSeconds: Long = WINDOW_SECONDS
    ): String = when (result) {
        VerificationResult.EXPIRED -> describe(deviceSeconds, serverSeconds, windowSeconds)
        VerificationResult.ACCEPTED -> "Подпись и время приняты."
        VerificationResult.REPLAY -> "Повтор nonce отклонён: этот запрос уже был принят."
        VerificationResult.INVALID_SIGNATURE -> "Подпись не совпала: изменились тело, путь или заголовки."
        VerificationResult.INVALID_NONCE -> "Nonce неверного формата: нужен 16–64 символа [A-Za-z0-9_-]."
    }
}
