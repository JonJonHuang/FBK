package moe.kabii.discord.event.user

import discord4j.common.util.Snowflake
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.User
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.event.domain.guild.MemberLeaveEvent
import discord4j.rest.util.Color
import kotlinx.coroutines.reactor.mono
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.data.mongodb.guilds.LogSettings
import moe.kabii.data.relational.UserLog
import moe.kabii.discord.event.EventListener
import moe.kabii.structure.long
import moe.kabii.structure.orNull
import moe.kabii.structure.snowflake
import org.jetbrains.exposed.sql.transactions.transaction
import reactor.kotlin.core.publisher.toFlux

object PartHandler {
    object PartListener : EventListener<MemberLeaveEvent>(MemberLeaveEvent::class) {
        override suspend fun handle(event: MemberLeaveEvent) = handlePart(event.guildId, event.user, event.member.orNull())
    }

    suspend fun handlePart(guild: Snowflake, user: User, member: Member?) {
        val config = GuildConfigurations.getOrCreateGuild(guild.asLong())

        // save current roles if this setting is enabled
        if(config.guildSettings.reassignRoles && member != null) {
            config.autoRoles.rejoinRoles[user.id.asLong()] = member.roleIds.map(Snowflake::long).toLongArray()
        }
        config.save()

        config.options.featureChannels.values.toList().toFlux()
            .filter(FeatureChannel::logChannel)
            .map(FeatureChannel::logSettings)
            .filter(LogSettings::partLog)
            .filter { partLog -> partLog.shouldInclude(user) }
            .flatMap { partLog ->
            user.client.getChannelById(partLog.channelID.snowflake)
                .ofType(TextChannel::class.java)
                .flatMap { channel ->
                    mono {
                        UserEventFormatter(user)
                            .formatPart(partLog.partFormat, member)
                    }.flatMap { formatted ->
                        channel.createEmbed { embed ->
                            embed.setDescription(formatted)
                            embed.setColor(Color.of(16739688))
                            if (partLog.partFormat.contains("&avatar")) {
                                embed.setImage(user.avatarUrl)
                            }
                        }
                    }
                }
        }.subscribe()

        transaction {
            val logUser = UserLog.GuildRelationship.getOrInsert(user.id.asLong(), guild.long)
            logUser.currentMember = false
        }
    }
}