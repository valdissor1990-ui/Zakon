package ru.zakon.app

/** Returns a lockout countdown only when its elapsed-realtime marker belongs to this boot. */
internal fun remainingLockMs(
    nowElapsed: Long,
    lockStartedAtElapsed: Long,
    lockUntilElapsed: Long
): Long {
    if (lockStartedAtElapsed <= 0L || lockStartedAtElapsed > nowElapsed || lockUntilElapsed <= nowElapsed) {
        return 0L
    }
    return lockUntilElapsed - nowElapsed
}
