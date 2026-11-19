package com.kj7nye.lorafieldops.platform

/** A re-entrant mutual-exclusion lock for short, non-suspending sections. */
expect class SyncLock() {
    fun <T> withLock(block: () -> T): T
}
