package dev.ambon.engine.behavior

class MobBehaviorMemory {
    var patrolIndex: Int = 0
    val cooldownTimestamps: MutableMap<String, Long> = mutableMapOf()
}
