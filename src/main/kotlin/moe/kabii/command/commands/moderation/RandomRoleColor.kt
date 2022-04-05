package moe.kabii.command.commands.moderation

import discord4j.core.`object`.component.ActionRow
import discord4j.core.`object`.component.Button
import discord4j.core.`object`.reaction.ReactionEmoji
import discord4j.core.event.domain.interaction.ButtonInteractionEvent
import discord4j.core.spec.EmbedCreateFields
import discord4j.core.spec.EmbedCreateSpec
import discord4j.rest.http.client.ClientException
import discord4j.rest.util.Color
import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import moe.kabii.command.Command
import moe.kabii.command.PermissionUtil
import moe.kabii.command.verify
import moe.kabii.discord.util.ColorUtil
import moe.kabii.discord.util.Embeds
import moe.kabii.discord.util.RGB
import moe.kabii.discord.util.Search
import moe.kabii.net.NettyFileServer
import moe.kabii.util.constants.EmojiCharacters
import moe.kabii.util.extensions.awaitAction
import moe.kabii.util.extensions.tryAwait
import reactor.core.publisher.Mono
import reactor.function.TupleUtils
import reactor.util.function.Tuple2
import java.time.Duration
import java.util.concurrent.TimeoutException

object RandomRoleColor : Command("randomizecolorz") {
    override val wikiPath = "Moderation-Commands#randomizing-a-roles-color"

    private fun randomColor() = Color.of((0..0xFFFFFF).random())

    init {
        botReqs(Permission.MANAGE_ROLES)
        discord {
            member.verify(Permission.MANAGE_ROLES)

            val role = args.role("role").awaitSingle()
            val safe = PermissionUtil.isSafeRole(role, member, target, managed = true, everyone = false)
            if(!safe) {
                ereply(Embeds.error("You can not manage the role **${role.name}**.")).awaitSingle()
                return@discord
            }
            fun colorPicker(color: Color): EmbedCreateSpec {
                val rgb = RGB(color)
                val (r, g, b) = rgb
                return Embeds.other("Color: ($r, $g, $b)", color)
                    .withAuthor(EmbedCreateFields.Author.of("Role Color Randomizer for \"${role.name}\"", null, null))
                    .withThumbnail(NettyFileServer.rgb(rgb))
            }

            var currColor = randomColor()
            // build color picker
            // X <CHECK> <NEXT>
            event
                .reply()
                .withEmbeds(colorPicker(currColor))
                .withComponents(
                    ActionRow.of(
                        Button.danger("exit", ReactionEmoji.unicode(EmojiCharacters.redX)),
                        Button.success("confirm", ReactionEmoji.unicode(EmojiCharacters.checkBox)),
                        Button.primary("next", ReactionEmoji.unicode(EmojiCharacters.play))
                    )
                )
                .awaitAction()

            while(true) {

                // listen for button press response
                val press = listener(ButtonInteractionEvent::class, true, Duration.ofMinutes(15), "exit", "confirm", "next")
                    .switchIfEmpty { event.editReply().withComponentsOrNull(null) }
                    .take(1).awaitFirstOrNull() ?: return@discord

                when(press.customId) {
                    "exit" -> {
                        event.editReply()
                            .withEmbeds(Embeds.fbk("Role edit aborted."))
                            .awaitSingle()
                        return@discord
                    }
                    "confirm" -> {
                        val oldColor = ColorUtil.hexString(role.color)
                        val newColor = ColorUtil.hexString(currColor)
                        try {
                            role.edit().withColor(currColor).awaitSingle()
                        } catch(ce: ClientException) {
                            event.editReply()
                                .withEmbeds(Embeds.error("I am unable to edit the role **${role.name}**. I must have a role above **${role.name}** to edit it. The hex value for the color you wanted to set was $newColor."))
                                .awaitSingle()
                        }
                        event.editReply()
                            .withEmbeds(Embeds.other("**${role.name}**'s color has been changed to $newColor. (Previously $oldColor)", currColor))
                            .awaitSingle()
                        return@discord
                    }
                    "next" -> {
                        // new color
                        currColor = randomColor()
                        event.editReply()
                            .withEmbeds(colorPicker(currColor))
                            .awaitSingle()
                    }
                }
            }
        }
    }
}