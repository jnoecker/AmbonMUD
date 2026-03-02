package dev.ambon.domain.world

/** Shared state for doors and containers — both can be open, closed, or locked. */
enum class LockableState { OPEN, CLOSED, LOCKED }

enum class LeverState { UP, DOWN }
