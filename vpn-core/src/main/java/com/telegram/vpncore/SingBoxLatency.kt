package com.telegram.vpncore

/**
 * How to read the delay values the core reports for a server.
 *
 * The number is unsigned and carries three different meanings, which is easy to get wrong:
 * 0 is not "instant", it means the core has no result for that server — the measurement
 * failed or never ran. Treating it as a latency makes an unreachable server look like the
 * fastest one and win any automatic selection.
 */
object SingBoxLatency {

    /** Above this the core is reporting a failure rather than a duration. */
    const val FAILED_ABOVE = 65000

    /** True when [delay] is an actual measurement and can be compared with others. */
    fun isMeasured(delay: Int?): Boolean = delay != null && delay > 0 && delay <= FAILED_ABOVE

    /** True when the core measured this server and it did not answer. */
    fun isFailed(delay: Int?): Boolean = delay != null && delay > FAILED_ABOVE
}
