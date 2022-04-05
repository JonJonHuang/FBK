package moe.kabii.command.commands.utility

import discord4j.core.retriever.EntityRetrievalStrategy
import discord4j.core.spec.EmbedCreateSpec
import discord4j.core.spec.MessageCreateSpec
import discord4j.rest.util.Color
import discord4j.rest.util.Image
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.command.CommandContainer
import moe.kabii.discord.util.Embeds
import moe.kabii.discord.util.Search
import moe.kabii.util.extensions.orNull
import moe.kabii.util.extensions.tryAwait
import moe.kabii.util.extensions.userAddress

object AvatarUtil : CommandContainer {
    object Avatar : Command("avatar") {
        override val wikiPath = "Discord-Info-Commands#get-user-avatar"

        init {
            discord {
                val targetUser = args.optUser("user")?.awaitSingle() ?: author
                // uses new embed spec to send 2 in one message though we are typically not converting to this style until 1.1
                val member = guild?.run { targetUser.asMember(id, EntityRetrievalStrategy.REST).tryAwait().orNull() }
                val avatars = sequence {
                    val globalAvatar = EmbedCreateSpec.create()
                        .withTitle("Avatar for **${targetUser.userAddress()}**")
                        .withImage("${targetUser.avatarUrl}?size=256")
                        .withColor(Color.of(12187102))
                    yield(globalAvatar)
                    if(guild != null && member != null) {
                        val format = if(member.hasAnimatedGuildAvatar()) Image.Format.GIF else Image.Format.PNG
                        val guildAvatarUrl = member.getGuildAvatarUrl(format).orNull()
                        if(guildAvatarUrl != null) {
                            val guildAvatar = EmbedCreateSpec.create()
                                .withTitle("Server avatar for **${targetUser.userAddress()}** in **${guild.name}**")
                                .withImage("$guildAvatarUrl?size=256")
                                .withColor(Color.of(12187102))
                            yield(guildAvatar)
                        }
                    }
                }.toList()
                chan.createMessage(
                    MessageCreateSpec.create()
                        .withEmbeds(avatars)
                ).awaitSingle()
            }
        }
    }

    object GuildIcon : Command("icon") {
        override val wikiPath = "Discord-Info-Commands#get-server-icon"

        init {
            discord {
                val iconUrl = target.getIconUrl(Image.Format.PNG).orNull()
                if(iconUrl != null) {
                    ireply(
                        Embeds.fbk()
                            .withTitle("Guild icon for **${target.name}**")
                            .withImage(iconUrl)
                    )
                } else {
                    ireply(Embeds.error("Icon not available for **${target.name}**."))
                }.tryAwait()
            }
        }
    }
}