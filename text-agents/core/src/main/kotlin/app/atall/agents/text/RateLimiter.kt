package app.atall.agents.text

/**
 * Sliding-window limits on outgoing texts, per agent and global. Guards against coordinator loops (two agents
 * answering each other forever) and against spamming a closed agent's number, which can get it blocked.
 * In-memory: a process restart resets the windows (acceptable; the hour cap is a backstop, not billing).
 */
class RateLimiter(private val limits: Limits = Limits()) {
  data class Limits(
    val minIntervalMs: Long = 3_000,
    val perAgentPerMinute: Int = 6,
    val perAgentPerHour: Int = 60,
    val globalPerMinute: Int = 20,
  )

  private val perAgent = HashMap<AgentId, ArrayDeque<Long>>()
  private val global = ArrayDeque<Long>()

  /** Null when a send to [agent] at [now] is allowed; else the scope that blocks it and how long to wait. */
  @Synchronized
  fun check(agent: AgentId, now: Long): Pair<RateScope, Long>? {
    val q = perAgent.getOrPut(agent) { ArrayDeque() }
    prune(q, now, HOUR)
    prune(global, now, MINUTE)
    q.lastOrNull()?.let { last -> if (now - last < limits.minIntervalMs) return RateScope.AGENT_INTERVAL to (last + limits.minIntervalMs - now) }
    val lastMinute = q.filter { now - it < MINUTE }
    if (lastMinute.size >= limits.perAgentPerMinute) return RateScope.AGENT_MINUTE to (lastMinute.first() + MINUTE - now)
    if (q.size >= limits.perAgentPerHour) return RateScope.AGENT_HOUR to (q.first() + HOUR - now)
    if (global.size >= limits.globalPerMinute) return RateScope.GLOBAL_MINUTE to (global.first() + MINUTE - now)
    return null
  }

  @Synchronized
  fun record(agent: AgentId, now: Long) {
    perAgent.getOrPut(agent) { ArrayDeque() }.addLast(now)
    global.addLast(now)
  }

  private fun prune(q: ArrayDeque<Long>, now: Long, window: Long) {
    while (q.isNotEmpty() && now - q.first() >= window) q.removeFirst()
  }

  private companion object {
    const val MINUTE = 60_000L
    const val HOUR = 3_600_000L
  }
}
