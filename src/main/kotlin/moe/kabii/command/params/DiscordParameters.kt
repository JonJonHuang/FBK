package moe.kabii.command.params

import discord4j.core.`object`.command.ApplicationCommandInteractionOption
import discord4j.core.`object`.command.Interaction
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.User
import discord4j.core.`object`.entity.channel.GuildChannel
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.spec.EmbedCreateSpec
import discord4j.core.spec.InteractionApplicationCommandCallbackReplyMono
import discord4j.rest.util.Permission
import moe.kabii.command.*
import moe.kabii.data.mongodb.GuildConfiguration
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.data.mongodb.guilds.GuildSettings
import moe.kabii.discord.event.interaction.ChatCommandHandler
import moe.kabii.discord.util.Embeds
import moe.kabii.discord.util.MessageColors
import moe.kabii.util.extensions.tryBlock
import moe.kabii.util.extensions.withUser
import kotlin.reflect.KProperty1

data class DiscordParameters (
    val handler: ChatCommandHandler,
    val event: ChatInputInteractionEvent,
    val interaction: Interaction,
    val chan: MessageChannel,
    val guild: Guild?,
    val author: User,
    val command: Command) {

    val subCommand by lazy { event.options[0] }

    fun baseArgs() = ChatCommandArguments(event)
    fun subArgs(sub: ApplicationCommandInteractionOption) = ChatCommandArguments(sub)

    // Commands which require guild context and should simply error if executed in PMs can retrieve the 'target' guild
    val target: Guild by lazy {
        if(guild != null) return@lazy guild
        throw GuildTargetInvalidException("Guild context unknown.")
    }

    val isPM = guild == null

    val member: Member by lazy {
        author.asMember(target.id).tryBlock().orNull() ?: error("Unable to get author as member of origin guild")
    }

    val config: GuildConfiguration by lazy {
        GuildConfigurations.getOrCreateGuild(target.id.asLong())
    }

    suspend fun features() = config.getOrCreateFeatures(guildChan.id.asLong())

    // error if we need to verify channel permissions for targeting specific channel, but this was executed in DMs
    val guildChan: GuildChannel
        get() = (chan as? GuildChannel) ?: throw GuildTargetInvalidException("Current channel is not a Discord server channel.")

    suspend fun channelVerify(vararg permissions: Permission) = member.channelVerify(guildChan, *permissions)

    @Throws(GuildFeatureDisabledException::class)
    fun guildFeatureVerify(feature: KProperty1<GuildSettings, Boolean>, featureName: String? = null) {
        if(guild != null) {
            val name = featureName ?: feature.name
            if(!feature.get(config.guildSettings)) throw GuildFeatureDisabledException(name, "guildcfg $name enable")
        } // else this is pm, allow
    }

    @Throws(ChannelFeatureDisabledException::class)
    suspend fun channelFeatureVerify(feature: KProperty1<FeatureChannel, Boolean>, featureName: String? = null, allowOverride: Boolean = true) {
        if(guild != null) {
            val features = config.options.featureChannels[chan.id.asLong()] ?: FeatureChannel(chan.id.asLong())
            val name = featureName ?: feature.name.removeSuffix("Channel")
            val permOverride = member.hasPermissions(guildChan, Permission.MANAGE_CHANNELS)
            if(!feature.get(features) && (!allowOverride || !permOverride)) throw ChannelFeatureDisabledException(name, this, feature)
        } // else this is pm, allow
    }

    // basic command reply
    fun reply(vararg embeds: EmbedCreateSpec) = event
        .reply()
        .withEmbeds(*embeds)

    // Create a 'usage info' message TODO this may not be needed with slash commnands
    fun usage(commandError: String, linkText: String?, user: User? = null): InteractionApplicationCommandCallbackReplyMono {
        val link = if(linkText != null) {
            if(command.wikiPath != null) " Command usage: **[$linkText](${command.getHelpURL()})**." else " Command usage: **$linkText**."
        } else ""
        return reply(Embeds.other("$commandError$link", MessageColors.spec).withUser(user))
    }
}