package moe.kabii.discord.tasks

import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.channel.VoiceChannel
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.relational.UserLog
import moe.kabii.discord.event.user.JoinHandler
import moe.kabii.discord.event.user.PartHandler
import moe.kabii.discord.util.RoleUtil
import moe.kabii.structure.extensions.long
import moe.kabii.structure.extensions.snowflake
import moe.kabii.structure.extensions.tryAwait
import moe.kabii.structure.extensions.withEach
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

// this is for checking after bot/api outages for any missed events
object OfflineUpdateHandler {
    suspend fun runChecks(guild: Guild) {
        val config = GuildConfigurations.getOrCreateGuild(guild.id.asLong())

         // check for empty twitch follower roles
        guild.roleIds.forEach { roleId ->
            RoleUtil.removeIfEmptyStreamRole(guild, roleId.long)
        }

        // sync all temporary voice channel states
        val tempChannels = config.tempVoiceChannels.tempChannels

        tempChannels // check any temp channels from last bot session that are deleted or are empty and remove them
            .filter { id ->
                guild.getChannelById(id.snowflake)
                    .ofType(VoiceChannel::class.java)
                    .tryAwait().orNull()
                    ?.let { vc ->
                        val empty = vc.voiceStates.hasElements().awaitSingle()
                        if(empty == true) vc.delete("Empty temporary channel.").subscribe()
                        empty
                    } ?: true // remove from db if empty or already deleted
            }.also { oldChannels ->
                if(oldChannels.isNotEmpty()) {
                    oldChannels.withEach(tempChannels::remove)
                    config.save()
                }
            }
    }
}