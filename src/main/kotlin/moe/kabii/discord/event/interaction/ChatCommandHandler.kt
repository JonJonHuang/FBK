package moe.kabii.discord.event.interaction

import discord4j.core.`object`.entity.channel.GuildChannel
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.rest.http.client.ClientException
import discord4j.rest.util.Permission
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import moe.kabii.LOG
import moe.kabii.command.*
import moe.kabii.command.params.DiscordParameters
import moe.kabii.command.registration.GlobalCommandRegistrar
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.discord.event.EventListener
import moe.kabii.discord.util.DiscordBot
import moe.kabii.discord.util.Embeds
import moe.kabii.trackers.ServiceWatcherManager
import moe.kabii.util.extensions.*

class ChatCommandHandler(val manager: CommandManager, val services: ServiceWatcherManager) : EventListener<ChatInputInteractionEvent>(ChatInputInteractionEvent::class) {

    fun searchCommandByName(name: String, bypassExempt: Boolean = false): Command? = manager.commands.find { command ->
        val allowed = if(bypassExempt) true else !command.commandExempt
        allowed && command.name == name.lowercase()
    }

    override suspend fun handle(event: ChatInputInteractionEvent) {

        val interaction = event.interaction
        val config = interaction.guildId.map { id -> GuildConfigurations.getOrCreateGuild(id.asLong()) }.orNull()

        // discord command handler
        val command = manager.commandsDiscord[event.commandName]
        if(command != null) {

            manager.context.launch {
                // retrieve/build command parameters
                val author = interaction.user
                val isPM = !interaction.guildId.isPresent
                val enabled = if(config == null) true else config.commandFilter.isCommandEnabled(command)
                val guild = interaction.guild.awaitSingleOrNull()
                val targetId = (guild?.id ?: author.id).asLong()
                val guildName = guild?.name ?: author.username
                val context = if (isPM) "Private" else "Guild"
                val optStr = GlobalCommandRegistrar.optionsToString(event.options)
                LOG.info("${context}Command:\t$guildName\t${author.userAddress()}\t:${event.commandName}\t$optStr :: on ${Thread.currentThread().name}")
                if(!enabled) {
                    event.reply()
                        .withEmbeds(Embeds.error("The /${command.name} command has been disabled by the staff of **${guild!!.name}**."))
                        .withEphemeral(true)
                        .awaitSingle()
                    LOG.info("Command is disabled in guild $guildName")
                    return@launch
                }
                val chan = interaction.channel.awaitSingle()
                val param = DiscordParameters(this@ChatCommandHandler, event, interaction, chan, guild, author, command)

                try {

                    // main command execution
                    if(command.executeChat != null) {
                        command.executeChat!!(param)
                    }

                } catch (parse: GuildTargetInvalidException) {
                    param.ereply(Embeds.error("${parse.string} Execute this command while in a server channel.")).subscribe()

                } catch (perms: MemberPermissionsException) {
                    val s = if(perms.perms.size > 1) "s" else ""
                    val reqs = perms.perms.joinToString(", ")
                    param.ereply(Embeds.error("The **${event.commandName}** command is restricted. (Requires the **$reqs** permission$s).")).subscribe()

                } catch (feat: ChannelFeatureDisabledException) {
                    //val channelMod = feat.origin.member.hasPermissions(feat.origin.guildChan, Permission.MANAGE_CHANNELS)
                    //val enableNotice = if(channelMod) "\nChannel moderators+ can enable this feature using **${prefix}feature ${feat.feature} enable**." else ""
                    val enableNotice = "\nChannel moderators+ can enable this feature using **/feature ${feat.feature} enable**."

                    val channels = if(feat.listChannels != null) {
                        feat.origin.config.options
                            .getChannels(feat.listChannels).keys
                            .ifEmpty { null }
                            ?.joinToString(", ") { chanId -> "<#$chanId>"}
                    } else null
                    val enabledIn = if(channels != null) "\n**${feat.feature}** is currently enabled in the following channels: $channels"
                    else ""

                    param.ereply(Embeds.error("The **${feat.feature}** feature is not enabled in this channel.$enableNotice$enabledIn"))
                        .awaitSingle()

                } catch (guildFeature: GuildFeatureDisabledException) {

                    val serverAdmin = param.member.hasPermissions(guildFeature.enablePermission)
                    val enableNotice = if(serverAdmin) "\nServer staff (${guildFeature.enablePermission.friendlyName} permission) can enable this feature using **/${guildFeature.adminEnable}**." else ""
                    param.ereply(Embeds.error("The **${guildFeature.featureName}** feature is not enabled in **$guildName**.$enableNotice.")).awaitSingle()

                } catch (cmd: GuildCommandDisabledException) {

                    author.privateChannel
                        .flatMap { pm ->
                            pm.createMessage(
                                Embeds.error("I tried to respond to your command **${event.commandName}** in channel ${chan.mention} but that command has been disabled by the staff of **$guildName**.")
                            )
                        }.awaitSingle()

                } catch (ba: BotAdminException) {
                    LOG.info("Bot admin check failed: $param")

                } catch (perms: BotSendMessageException) {
                    LOG.warn("${perms.message} :: channel=${perms.channel}")

                } catch (ce: ClientException) {
                    // bot is missing permissions
                    when (ce.status.code()) {
                        403 -> {
                            LOG.debug("403: ${ce.message}")
                            if (config == null || chan !is GuildChannel) return@launch
                            if (ce.errorResponse.orNull()?.fields?.get("message")?.equals("Missing Permissions") != true) return@launch
                            val botPermissions = chan.getEffectivePermissions(DiscordBot.selfId).awaitSingle()
                            val listMissing = command.discordReqs
                                .filterNot(botPermissions::contains)
                                .map(Permission::friendlyName)
                                .joinToString("\n")
                            author.privateChannel
                                .flatMap { pm ->
                                    pm.createMessage(
                                        Embeds.error("I tried to respond to your command **${command.name}** in channel ${chan.getMention()} but I am missing required permissions:\n\n**$listMissing\n\n**If you think bot commands are intended to be used in this channel, please ask the server's admins to check my permissions.")
                                    )
                                }.awaitSingle()
                        }
                        else -> {
                            LOG.error("Uncaught client exception in command ${command.name} on guild $targetId: ${ce.message}")
                            LOG.debug(ce.stackTraceString) // these can be relatively normal - deleted channels and other weirdness
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("\nUncaught (non-discord) exception in command ${command.name} on guild $targetId: ${e.message}\nErroring command: ${event.commandName} :: ${event.options}")
                    LOG.warn(e.stackTraceString)
                }
            }
            return
        }

        // check for guild 'custom' commands if a command was not found (do not allow them to override global commands)
        if(config != null) {

            manager.context.launch {
                val customCommand = config.guildCustomCommands.commands
                    .find { c -> c.name == event.commandName }

                if(customCommand != null) {
                    event
                        .reply()
                        .withEmbeds(Embeds.fbk(customCommand.response))
                        .withEphemeral(customCommand.ephemeral)
                        .awaitAction()
                }
            }
            return
        }
        error("Chat Command missing: ${event.commandName}")
    }
}