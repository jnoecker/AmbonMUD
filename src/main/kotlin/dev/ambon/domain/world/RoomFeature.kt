package dev.ambon.domain.world

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId

/**
 * Immutable definition of a stateful room feature.
 *
 * Feature IDs follow the pattern `<zone>:<room>/<localId>`, e.g.:
 * - Door at direction NORTH in room `zone:room` → `"zone:room/north"`
 * - Container with local id `old_chest` in room `zone:room` → `"zone:room/old_chest"`
 */
sealed class RoomFeature {
    abstract val id: String
    abstract val roomId: RoomId
    abstract val displayName: String
    abstract val keyword: String

    data class Door(
        override val id: String,
        override val roomId: RoomId,
        override val displayName: String,
        override val keyword: String,
        val direction: Direction,
        val initialState: LockableState,
        val keyItemId: ItemId?,
        val keyConsumed: Boolean,
        val resetWithZone: Boolean,
        /** Seconds out of [initialState] before the door reverts on its own. Null = zone reset only. */
        val respawnSeconds: Long? = null,
    ) : RoomFeature()

    data class Container(
        override val id: String,
        override val roomId: RoomId,
        override val displayName: String,
        override val keyword: String,
        val initialState: LockableState,
        val keyItemId: ItemId?,
        val keyConsumed: Boolean,
        val resetWithZone: Boolean,
        val initialItems: List<ItemId>,
        /**
         * Seconds out of its initial condition (state or contents) before the
         * container reverts and refills [initialItems]. Null = zone reset only.
         */
        val respawnSeconds: Long? = null,
        /**
         * Optional open-chest backdrop art (resolved URL) for the web client's
         * World Features modal. Absent → the `container_bg` global default, then
         * a built-in CSS treatment.
         */
        val backgroundImage: String? = null,
    ) : RoomFeature()

    data class Lever(
        override val id: String,
        override val roomId: RoomId,
        override val displayName: String,
        override val keyword: String,
        val initialState: LeverState,
        val resetWithZone: Boolean,
        /** Seconds out of [initialState] before the lever snaps back. Null = zone reset only. */
        val respawnSeconds: Long? = null,
        /**
         * Optional custom artwork: a static base plate plus a handle that rotates
         * around [leverPivot] between [upAngle] and [downAngle]. Image refs are
         * content-addressed filenames resolved against the world image base, like
         * room/mob/item art. When [handleImage] is absent the client renders its
         * built-in vector lever (full backward compatibility).
         */
        val plateImage: String? = null,
        val handleImage: String? = null,
        val leverPivot: LeverPivot? = null,
        val upAngle: Double? = null,
        val downAngle: Double? = null,
        /**
         * Optional backdrop "box" art (resolved URL) the lever sits inside;
         * the plate + handle render on top. Absent → the `lever_bg` global
         * default, then a built-in CSS treatment.
         */
        val backgroundImage: String? = null,
    ) : RoomFeature()

    /** Handle pivot as fractions (0..1) of the handle sprite box. */
    data class LeverPivot(
        val x: Double,
        val y: Double,
    )

    data class Sign(
        override val id: String,
        override val roomId: RoomId,
        override val displayName: String,
        override val keyword: String,
        val text: String,
        /**
         * Optional sign-board backdrop art (resolved URL) for the web client's
         * World Features modal. Absent → the `sign_bg` global default, then a
         * built-in CSS treatment.
         */
        val backgroundImage: String? = null,
    ) : RoomFeature()
}
