package dev.ambon.domain

data class Progress(
    val current: Int,
    val required: Int,
) {
    val isComplete: Boolean get() = current >= required
}
