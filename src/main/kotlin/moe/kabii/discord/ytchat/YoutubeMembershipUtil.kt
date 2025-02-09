package moe.kabii.discord.ytchat

import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.Role
import discord4j.rest.http.client.ClientException
import kotlinx.coroutines.reactor.awaitSingle
import moe.kabii.LOG
import moe.kabii.data.relational.streams.TrackedStreams
import moe.kabii.data.relational.streams.youtube.ytchat.*
import moe.kabii.discord.trackers.TrackerUtil
import moe.kabii.util.extensions.WithinExposedContext
import moe.kabii.util.extensions.snowflake
import moe.kabii.util.extensions.stackTraceString
import moe.kabii.util.extensions.success
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.select

class YoutubeMembershipUtil private constructor(val discord: GatewayDiscordClient, val link: MembershipConfiguration, var guild: Guild? = null) {

    var membershipRole: Role? = null

    constructor(discord: GatewayDiscordClient, link: MembershipConfiguration): this(discord, link, guild = null) {
        guild = try {
            // try to get guild, if error then ignore, this will be handled by membership cleanup task
            discord.getGuildById(link.discordServer.guildID.snowflake).block()
        } catch(ce: ClientException) { null }
    }

    companion object {
        @WithinExposedContext fun forConfig(client: GatewayDiscordClient, config: MembershipConfiguration) = YoutubeMembershipUtil(client, config)
        @WithinExposedContext fun forConfig(guild: Guild, config: MembershipConfiguration) = YoutubeMembershipUtil(guild.client, config, guild = guild)
        @WithinExposedContext fun forGuild(guild: Guild) = MembershipConfigurations.getForGuild(guild.id)?.run { YoutubeMembershipUtil(guild.client, this, guild = guild) }

        @WithinExposedContext
        suspend fun linkMembership(discord: GatewayDiscordClient, linkedYt: LinkedYoutubeAccount) {
            // check if newly linked account has associated memberships
            // linkedyt -> chat memberships -> configs -> assign role
            MembershipConfigurations
                .innerJoin(TrackedStreams.StreamChannels)
                .innerJoin(YoutubeMembers, { TrackedStreams.StreamChannels.siteChannelID }, { channelOwnerId })
                .innerJoin(LinkedYoutubeAccounts, { YoutubeMembers.chatterId }, { ytChatId })
                .select {
                    YoutubeMembers.chatterId eq linkedYt.ytChatId
                }
                .run(MembershipConfiguration::wrapRows)
                .map { config -> config.utils(discord) }
                .forEach { utils -> utils.assignMembershipRole(linkedYt) }
        }

        @WithinExposedContext
        suspend fun linkMembership(discord: GatewayDiscordClient, member: YoutubeMember) {
            // check if newly recorded membership has an associated discord account
            // ytmember -> linkedyt -> configs -> assign role
            val linkedAccount = LinkedYoutubeAccount.find {
                LinkedYoutubeAccounts.ytChatId eq member.chatterId
            }.singleOrNull() ?: return

            MembershipConfigurations
                .innerJoin(TrackedStreams.StreamChannels)
                .select {
                    TrackedStreams.StreamChannels.siteChannelID eq member.channelOwnerId
                }
                .run(MembershipConfiguration::wrapRows)
                .map { config -> config.utils(discord) }
                .forEach { utils -> utils.assignMembershipRole(linkedAccount) }
        }
    }

    @WithinExposedContext
    suspend fun unsync() {
        try {
            val role = checkMembershipRole()
            role?.delete()?.thenReturn(Unit)?.awaitSingle()
        } catch(e: Exception) {
            LOG.info("Error deleting membership integration role: ${link.membershipRole} :: ${e.message}")
            LOG.trace(e.stackTraceString)
        }
        link.delete()
    }

    @WithinExposedContext
    suspend fun syncMemberships() {
        // ensure all members have the role in discord
        getActiveMembers().forEach { ytMember ->
            // get discord member data
            val member = getMember(ytMember) ?: return@forEach
            if(!member.roleIds.contains(link.membershipRole.snowflake)) {
                assignMembershipRole(member)
            }
        }
    }

    @WithinExposedContext
    fun getActiveMembers(): List<LinkedYoutubeAccount> = LinkedYoutubeAccounts
        .innerJoin(YoutubeMembers, { ytChatId }, { chatterId })
        .select {
            // pull all active youtube members for this config
            YoutubeMembers.channelOwnerId eq link.streamChannel.siteChannelID
        }
        .run(LinkedYoutubeAccount::wrapRows)
        .toList()

    @WithinExposedContext
    private suspend fun getMember(linkedYt: LinkedYoutubeAccount): Member? {
        val guild = guild ?: return null
        return try {
            guild.getMemberById(linkedYt.discordUser.userID.snowflake).awaitSingle()
        } catch(e: Exception) {
            LOG.warn("Unable to get Discord member: ${linkedYt.discordUser.userID} :: ${e.message}")
            null
        }
    }

    @WithinExposedContext
    private suspend fun assignMembershipRole(member: Member) {
        val role = checkMembershipRole() ?: return
        // todo notify if missing permissions? once sync command is written
        member.addRole(role.id).success().awaitSingle()
    }

    @WithinExposedContext
    private suspend fun assignMembershipRole(linkedYt: LinkedYoutubeAccount) {
        val member = getMember(linkedYt) ?: return
        assignMembershipRole(member)
    }

    @WithinExposedContext
    suspend fun unassignMembershipRole(linkedYt: LinkedYoutubeAccount) {
        val role = checkMembershipRole() ?: return
        val member = getMember(linkedYt) ?: return
        member.removeRole(role.id).success().awaitSingle()
    }

    @WithinExposedContext
    private suspend fun checkMembershipRole(): Role? {
        if(membershipRole != null) return membershipRole
        val guild = guild ?: return null

        // get role, handle errors
        return try {
            val role = discord.getRoleById(guild.id, link.membershipRole.snowflake).awaitSingle()
            this.membershipRole = role
            return role
        } catch(ce: ClientException) {
            when {
                ce.status.code() == 404 -> {
                    // role deleted, unlink memberships
                    val unlinked = "The role for linked YouTube memberships has been deleted in **${guild.name}**. The integration has been deleted accordingly. If this was in error, you may re-create the integration at any time with the **linkyoutubemembers** command."
                    TrackerUtil.notifyOwner(discord, guild.id.asLong(), unlinked)
                    link.delete()
                }
                ce.status.code() == 403 -> {
                    LOG.warn("YouTube membership link: permission denied for guild ${guild.id.asString()} :: ${ce.message}")
                    val perms = "There is a role configured for linked YouTube memberships in **${guild.name}**, but I do not have permission to access this role. Please ensure I have both the Manage Roles permission and the **membership role** is NOT ABOVE my (bot) role."
                    TrackerUtil.notifyOwner(discord, guild.id.asLong(), perms)
                }
                else -> throw ce
            }
            null
        }
    }

}