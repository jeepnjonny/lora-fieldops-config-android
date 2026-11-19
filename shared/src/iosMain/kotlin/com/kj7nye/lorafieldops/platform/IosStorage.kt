@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.kj7nye.lorafieldops.platform

import com.kj7nye.lorafieldops.logger.LogQueueStore
import com.kj7nye.lorafieldops.logger.QueuedEntry
import com.kj7nye.lorafieldops.logger.SecretStore
import com.kj7nye.lorafieldops.logger.SettingsBackend
import com.kj7nye.lorafieldops.logger.SettingsEditor
import com.kj7nye.lorafieldops.model.CoverageEntry
import com.kj7nye.lorafieldops.model.CoverageEntryType
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val KEYCHAIN_SERVICE = "com.kj7nye.lorafieldops.coverage-logger"
private const val DEFAULTS_PREFIX = "coverage_logger."

// -----------------------------------------------------------------------------
// Settings: NSUserDefaults, secrets in the Keychain
// -----------------------------------------------------------------------------

internal class IosSettingsBackend : SettingsBackend {
    private val defaults = NSUserDefaults.standardUserDefaults

    private fun k(key: String) = DEFAULTS_PREFIX + key

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(k(key)) == null) default else defaults.boolForKey(k(key))

    override fun getLong(key: String, default: Long): Long =
        if (defaults.objectForKey(k(key)) == null) default else defaults.integerForKey(k(key))

    override fun getString(key: String): String? = defaults.stringForKey(k(key))

    override fun edit(block: SettingsEditor.() -> Unit) {
        object : SettingsEditor {
            override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, k(key))
            override fun putLong(key: String, value: Long) = defaults.setInteger(value, k(key))
            override fun putString(key: String, value: String?) {
                if (value == null) defaults.removeObjectForKey(k(key)) else defaults.setObject(value, k(key))
            }
        }.block()
    }

    override fun openSecrets(): SecretStore = KeychainSecretStore()
}

/**
 * Generic-password Keychain items, readable after the first unlock (so a background
 * upload with the phone locked still finds the key) and never synced or backed up to
 * another device.
 */
private class KeychainSecretStore : SecretStore {
    override fun getString(key: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(
            key,
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { SecItemCopyMatching(it, result.ptr) }
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        data.toByteArray().decodeToString()
    }

    override fun putString(key: String, value: String) {
        withQuery(key) { SecItemDelete(it) }
        if (value.isEmpty()) return
        val data = CFBridgingRetain(value.encodeToByteArray().toNSData())
        try {
            withQuery(
                key,
                kSecValueData to data,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ) { SecItemAdd(it, null) }
        } finally {
            CFRelease(data)
        }
    }

    /** Runs [block] with a query dictionary for [account] plus [extra] entries. */
    private fun <T> withQuery(account: String, vararg extra: Pair<CFStringRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> T): T {
        val service = CFBridgingRetain(KEYCHAIN_SERVICE)
        val acct = CFBridgingRetain(account)
        val dict = CFDictionaryCreateMutable(null, (3 + extra.size).toLong(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dict, kSecAttrService, service)
            CFDictionaryAddValue(dict, kSecAttrAccount, acct)
            extra.forEach { (k, v) -> CFDictionaryAddValue(dict, k, v) }
            return block(dict)
        } finally {
            CFRelease(dict)
            CFRelease(service)
            CFRelease(acct)
        }
    }
}

// -----------------------------------------------------------------------------
// Upload queue: a JSON file in Application Support
// -----------------------------------------------------------------------------

@Serializable
private data class StoredEntry(
    val id: Long,
    val device: String,
    val type: String,
    val ts: Long,
    val packet: String? = null,
    val text: String? = null,
    val rssi: Int? = null,
    val snr: Double? = null,
)

@Serializable
private data class StoredQueue(val nextId: Long = 1, val entries: List<StoredEntry> = emptyList())

private val queueJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * The coverage logger's offline queue as one JSON file, rewritten atomically on every
 * change. The queue is small (it drains every minute while online), and an atomic
 * rewrite means a crash or kill never leaves a half-written file.
 */
internal class FileLogQueue : LogQueueStore {
    private val mutex = Mutex()
    private val file: NSURL? by lazy { queueFileUrl() }
    private var state: StoredQueue? = null
    private val _count = MutableStateFlow(0)
    override val count: Flow<Int> = _count.asStateFlow()

    init {
        // Show the stored count on the logger screen without waiting for the first write.
        _count.value = load().entries.size
    }

    override suspend fun insert(device: String, entries: List<CoverageEntry>) = update { q ->
        var id = q.nextId
        val added = entries.map { e ->
            StoredEntry(id++, device, e.type.wire, e.ts, e.packet, e.text, e.rssi, e.snr)
        }
        StoredQueue(id, q.entries + added)
    }

    override suspend fun nextBatch(limit: Int, exclude: Set<String>): List<QueuedEntry> = mutex.withLock {
        val q = current()
        val device = q.entries.firstOrNull { it.device !in exclude }?.device ?: return emptyList()
        q.entries.asSequence()
            .filter { it.device == device }
            .mapNotNull { it.toQueued() }   // a type this build doesn't know is skipped
            .take(limit)
            .toList()
    }

    override suspend fun delete(ids: List<Long>) {
        val gone = ids.toSet()
        update { q -> q.copy(entries = q.entries.filterNot { it.id in gone }) }
    }

    private suspend fun update(change: (StoredQueue) -> StoredQueue) = mutex.withLock {
        val next = change(current())
        withContext(Dispatchers.IO) { save(next) }
        state = next
        _count.value = next.entries.size
    }

    private fun current(): StoredQueue = state ?: load().also { state = it }

    private fun load(): StoredQueue {
        val url = file ?: return StoredQueue()
        val data = NSData.dataWithContentsOfURL(url) ?: return StoredQueue()
        return runCatching { queueJson.decodeFromString(StoredQueue.serializer(), data.toByteArray().decodeToString()) }
            .getOrElse { StoredQueue() }
            // Rows with a type this build can't send would sit at the head forever.
            .let { q -> q.copy(entries = q.entries.filter { CoverageEntryType.fromWire(it.type) != null }) }
    }

    private fun save(q: StoredQueue) {
        val url = file ?: throw IllegalStateException("No storage directory for the upload queue")
        val bytes = queueJson.encodeToString(StoredQueue.serializer(), q).encodeToByteArray()
        if (!bytes.toNSData().writeToURL(url, atomically = true)) {
            throw IllegalStateException("Couldn't write the upload queue")
        }
    }

    private fun queueFileUrl(): NSURL? {
        val fm = NSFileManager.defaultManager
        val dir = fm.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null) ?: return null
        return dir.URLByAppendingPathComponent("coverage_queue.json")
    }
}

private fun StoredEntry.toQueued(): QueuedEntry? {
    val t = CoverageEntryType.fromWire(type) ?: return null
    return QueuedEntry(id, device, CoverageEntry(t, ts, packet, text, rssi, snr))
}
