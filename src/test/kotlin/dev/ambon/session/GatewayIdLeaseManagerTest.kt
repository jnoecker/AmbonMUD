package dev.ambon.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayIdLeaseManagerTest {
    /** In-memory Redis stand-in: simple mutable map with SET NX EX semantics. */
    private fun buildStore(): MutableMap<String, String> = mutableMapOf()

    private fun leaseManager(
        store: MutableMap<String, String>,
        instanceId: String,
        gatewayId: Int = 1,
        ttl: Long = 300L,
    ) = GatewayIdLeaseManager(
        gatewayId = gatewayId,
        instanceId = instanceId,
        leaseTtlSeconds = ttl,
        setNxEx = { key, value, _ ->
            if (store.containsKey(key)) {
                false
            } else {
                store[key] = value
                true
            }
        },
        getValue = { key -> store[key] },
        deleteKey = { key -> store.remove(key) },
    )

    @Test
    fun `acquire succeeds when key is unset`() {
        val store = buildStore()
        val mgr = leaseManager(store, instanceId = "instance-A")

        assertTrue(mgr.tryAcquire(), "tryAcquire() must return true when key is free")
        assertEquals("instance-A", mgr.currentOwner(), "currentOwner() must return this instance's ID")
    }

    @Test
    fun `acquire fails when key is already held by another instance`() {
        val store = buildStore()
        val mgrA = leaseManager(store, instanceId = "instance-A")
        val mgrB = leaseManager(store, instanceId = "instance-B")

        mgrA.tryAcquire()

        assertFalse(mgrB.tryAcquire(), "tryAcquire() must return false when key is already held")
        assertEquals("instance-A", mgrB.currentOwner(), "currentOwner() must return the other instance's ID")
    }

    @Test
    fun `release deletes key when this instance owns it`() {
        val store = buildStore()
        val mgr = leaseManager(store, instanceId = "instance-A")

        mgr.tryAcquire()
        mgr.release()

        assertNull(mgr.currentOwner(), "After release the key must not exist")
    }

    @Test
    fun `release does not delete key when owned by a different instance`() {
        val store = buildStore()
        val mgrA = leaseManager(store, instanceId = "instance-A")
        val mgrB = leaseManager(store, instanceId = "instance-B")

        mgrA.tryAcquire() // A holds the lease
        mgrB.release() // B tries to release — must be a no-op

        assertEquals("instance-A", mgrA.currentOwner(), "Key must still exist and be owned by A")
    }
}
