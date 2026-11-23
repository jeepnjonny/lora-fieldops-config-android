package io.github.jeepnjonny.lorafieldops.platform

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as jvmWithLock

actual class SyncLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T = lock.jvmWithLock(block)
}
