package io.github.jeepnjonny.lorafieldops.platform

import platform.Foundation.NSRecursiveLock

actual class SyncLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
