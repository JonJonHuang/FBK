package moe.kabii.command.params

import discord4j.core.event.domain.interaction.UserInteractionEvent
import moe.kabii.FBK

data class UserInteractionParameters(
    val client: FBK,
    val event: UserInteractionEvent
)