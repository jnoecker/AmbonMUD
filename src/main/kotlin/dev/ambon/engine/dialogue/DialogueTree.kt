package dev.ambon.engine.dialogue

data class DialogueTree(
    val rootNodeId: String,
    val nodes: Map<String, DialogueNode>,
)

data class DialogueNode(
    val text: String,
    val choices: List<DialogueChoice>,
)

data class DialogueChoice(
    val text: String,
    val nextNodeId: String?,
    val minLevel: Int?,
    val requiredClass: String?,
)
