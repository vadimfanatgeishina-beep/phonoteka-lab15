package com.phonoteka.security

/** 1/2/4/8/16/30 seconds; only a fresh handshake is issued after each wait. */
object LocalReconnectPolicy {
    fun delayMillis(retry: Int, initialMillis: Long = 1000L, capMillis: Long = 30_000L): Long {
        require(retry >= 1 && initialMillis >= 0 && capMillis > 0)
        var value = initialMillis.coerceAtMost(capMillis)
        repeat((retry - 1).coerceAtMost(63)) {
            value = if (value > capMillis / 2) capMillis else value * 2
        }
        return value
    }
}
