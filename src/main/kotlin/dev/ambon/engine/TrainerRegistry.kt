package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.TrainerDefinition

class TrainerRegistry {
    private val trainersByRoom = mutableMapOf<RoomId, TrainerDefinition>()

    fun register(trainers: List<TrainerDefinition>) {
        for (trainer in trainers) {
            trainersByRoom[trainer.roomId] = trainer
        }
    }

    fun trainerInRoom(roomId: RoomId): TrainerDefinition? = trainersByRoom[roomId]

    fun clear() {
        trainersByRoom.clear()
    }
}
