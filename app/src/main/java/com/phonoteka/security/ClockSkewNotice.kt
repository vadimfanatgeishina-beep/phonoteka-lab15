package com.phonoteka.security

import kotlin.math.abs

/**
 * Человеческий текст про рассинхрон часов.
 *
 * Сервер принимает подпись только в окне ±[WINDOW_SECONDS] от своего времени. Если часы
 * устройства ушли дальше, клиент получает 401, из которого причина не видна: «подпись
 * неверна» и «время не то» выглядят одинаково. Этот объект переводит [VerificationResult]
 * в фразу, по которой понятно, что делать пользователю.
 */
object ClockSkewNotice {

    /** Окно, в котором сервер принимает timestamp подписи, в секундах. */
    const val WINDOW_SECONDS = 30L

    /** Часы устройства минус часы сервера: меньше нуля — отстают, больше — спешат. */
    fun driftSeconds(deviceSeconds: Long, serverSeconds: Long): Long =
        runCatching { Math.subtractExact(deviceSeconds, serverSeconds) }.getOrDefault(0L)

    fun describe(
        deviceSeconds: Long,
        serverSeconds: Long,
        windowSeconds: Long = WINDOW_SECONDS
    ): String {
        require(windowSeconds >= 0) { "Окно проверки не может быть отрицательным" }
        val drift = driftSeconds(deviceSeconds, serverSeconds)
        val magnitude = magnitudeOf(drift)
        if (magnitude <= windowSeconds) {
            return "Рассинхрона нет: часы устройства и сервера расходятся на $magnitude с, " +
                "это внутри окна ±$windowSeconds с — подпись проходит."
        }
        val direction = if (drift < 0) "отстают" else "спешат"
        return "Часы устройства $direction на $magnitude с: сервер принимает подпись только " +
            "в окне ±$windowSeconds с, поэтому запрос отклонён. Включите автоматическую " +
            "синхронизацию времени (Настройки → Дата и время) и повторите запрос заново — " +
            "старую подпись повторять нельзя, у неё уже истёк nonce."
    }

    /** То же объяснение, но по коду ответа: EXPIRED разворачивается в текст про часы. */
    fun describeResult(
        result: VerificationResult,
        deviceSeconds: Long,
        serverSeconds: Long,
        windowSeconds: Long = WINDOW_SECONDS
    ): String = when (result) {
        VerificationResult.EXPIRED -> describe(deviceSeconds, serverSeconds, windowSeconds)
        VerificationResult.REPLAY ->
            "Сервер уже видел этот nonce (HTTP 409): запрос отклонён как повтор. " +
                "Нужен новый nonce — переиспользовать старую подпись нельзя."
        VerificationResult.INVALID_SIGNATURE ->
            "Подпись не совпала (HTTP 401): тело или путь запроса изменились по дороге, " +
                "либо ключ на клиенте и сервере разные."
        VerificationResult.INVALID_NONCE ->
            "Nonce не подходит по формату (HTTP 401): нужны 16–64 символа из A-Z, a-z, 0-9, «_» и «-»."
        VerificationResult.ACCEPTED ->
            "Подпись верна, время в окне ±$windowSeconds с, nonce новый — сервер принял запрос (HTTP 200)."
    }

    /** abs(Long.MIN_VALUE) остаётся отрицательным, поэтому величину считаем отдельно. */
    private fun magnitudeOf(drift: Long): Long =
        if (drift == Long.MIN_VALUE) Long.MAX_VALUE else abs(drift)
}
