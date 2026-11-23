package app.nocturne.player.data.online

import app.nocturne.player.data.model.OnlineProviderType
import app.nocturne.player.data.model.ProviderHealthSnapshot
import java.util.concurrent.ConcurrentHashMap

class ProviderHealthTracker(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val failureThreshold: Int = 2,
    private val baseCooldownMs: Long = 30_000L,
    private val maxCooldownMs: Long = 5 * 60_000L
) {
    private data class MutableHealth(
        var consecutiveFailures: Int = 0,
        var lastFailureAtMs: Long = 0L,
        var cooldownUntilMs: Long = 0L,
        var ewmaLatencyMs: Long = 450L,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var activeInstanceUrl: String? = null
    )

    private val states = ConcurrentHashMap<OnlineProviderType, MutableHealth>()

    private fun stateFor(type: OnlineProviderType): MutableHealth =
        states.getOrPut(type) { MutableHealth() }

    @Synchronized
    fun recordSuccess(type: OnlineProviderType, latencyMs: Long, instanceUrl: String? = null) {
        val st = stateFor(type)
        st.consecutiveFailures = 0
        st.cooldownUntilMs = 0L
        st.successCount += 1
        st.ewmaLatencyMs = if (st.successCount == 1) {
            latencyMs.coerceAtLeast(1L)
        } else {
            ((st.ewmaLatencyMs * 7 + latencyMs.coerceAtLeast(1L) * 3) / 10)
        }
        if (instanceUrl != null) st.activeInstanceUrl = instanceUrl
    }

    @Synchronized
    fun recordFailure(type: OnlineProviderType) {
        val now = clockMs()
        val st = stateFor(type)
        st.consecutiveFailures += 1
        st.failureCount += 1
        st.lastFailureAtMs = now
        if (st.consecutiveFailures >= failureThreshold) {
            val exponent = (st.consecutiveFailures - failureThreshold).coerceIn(0, 4)
            val cooldown = (baseCooldownMs * (1L shl exponent)).coerceAtMost(maxCooldownMs)
            st.cooldownUntilMs = now + cooldown
        }
    }

    @Synchronized
    fun isAvailable(type: OnlineProviderType): Boolean {
        val st = stateFor(type)
        return clockMs() >= st.cooldownUntilMs
    }

    @Synchronized
    fun selectOrderedCandidates(candidates: List<OnlineProviderType>): List<OnlineProviderType> {
        val now = clockMs()
        val healthy = candidates.filter { now >= stateFor(it).cooldownUntilMs }
        return if (healthy.isNotEmpty()) {
            // Preserve priority order among available providers so recovered primary sources are retried
            healthy
        } else {
            // All candidates are in cooldown: pick the ones whose cooldown expires soonest
            candidates.sortedBy { stateFor(it).cooldownUntilMs }
        }
    }

    @Synchronized
    fun snapshotAll(types: List<OnlineProviderType> = OnlineProviderType.entries): List<ProviderHealthSnapshot> {
        val now = clockMs()
        return types.map { type ->
            val st = stateFor(type)
            val remaining = (st.cooldownUntilMs - now).coerceAtLeast(0L)
            ProviderHealthSnapshot(
                providerType = type,
                isAvailable = remaining == 0L,
                ewmaLatencyMs = st.ewmaLatencyMs,
                consecutiveFailures = st.consecutiveFailures,
                cooldownRemainingMs = remaining,
                activeInstanceUrl = st.activeInstanceUrl
            )
        }
    }
}
