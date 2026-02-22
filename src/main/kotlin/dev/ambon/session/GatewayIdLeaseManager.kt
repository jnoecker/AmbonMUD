package dev.ambon.session

/**
 * Manages an exclusive Redis lease for a gateway ID to detect duplicate gateway deployments.
 *
 * Uses a SET NX EX key to claim ownership.  All Redis interactions are injected as functional
 * parameters so the class is testable without Mockito or a live Redis instance.
 *
 * @param gatewayId     The numeric gateway ID being leased (used as part of the Redis key).
 * @param instanceId    Unique identifier for this gateway instance (stored as the lease value).
 * @param leaseTtlSeconds TTL for the Redis key so stale leases expire automatically.
 * @param setNxEx       `SET key value NX EX ttl` — returns `true` if the key was set (acquired).
 * @param getValue      `GET key` — returns the current value, or `null` if the key does not exist.
 * @param deleteKey     `DEL key` — unconditionally deletes the key.
 */
class GatewayIdLeaseManager(
    gatewayId: Int,
    private val instanceId: String,
    private val leaseTtlSeconds: Long,
    private val setNxEx: (key: String, value: String, ttl: Long) -> Boolean,
    private val getValue: (key: String) -> String?,
    private val deleteKey: (key: String) -> Unit,
) {
    private val leaseKey = "gateway:lock:$gatewayId"

    /** Attempts to acquire the lease.  Returns `true` if this instance now holds it. */
    fun tryAcquire(): Boolean = setNxEx(leaseKey, instanceId, leaseTtlSeconds)

    /** Releases the lease only if this instance still owns it. */
    fun release() {
        if (getValue(leaseKey) == instanceId) {
            deleteKey(leaseKey)
        }
    }

    /** Returns the current leaseholder's instance ID, or `null` if the key is not set. */
    fun currentOwner(): String? = getValue(leaseKey)
}
