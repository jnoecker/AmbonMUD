package dev.ambon.domain.world.data

data class DialogueNodeFile(
    val text: String,
    val choices: List<DialogueChoiceFile> = emptyList(),
)

data class DialogueChoiceFile(
    val text: String,
    val next: String? = null,
    val minLevel: Int? = null,
    val requiredClass: String? = null,
)
