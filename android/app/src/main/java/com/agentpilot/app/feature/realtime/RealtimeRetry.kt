package com.agentpilot.app.feature.realtime

import kotlin.math.min
import kotlin.random.Random

private const val BASE_DELAY_MILLIS = 1_000L
private const val MAX_DELAY_MILLIS = 60_000L
private const val MAX_SHIFT = 6

/**
 * Exponential backoff for realtime reconnects: 1s, 2s, 4s, ... capped at 60s,
 * with a bounded random jitter so concurrent clients do not reconnect in lockstep.
 *
 * Pure so unit tests can drive it with a fixed [randomUnit].
 */
internal fun reconnectDelayMillis(
    attempt: Int,
    randomUnit: Double = Random.nextDouble(),
): Long {
    val exponent = attempt.coerceIn(0, MAX_SHIFT)
    val base = min(BASE_DELAY_MILLIS shl exponent, MAX_DELAY_MILLIS)
    val jitter = (base * 0.2 * randomUnit.coerceIn(0.0, 1.0)).toLong()
    return min(base + jitter, MAX_DELAY_MILLIS)
}
