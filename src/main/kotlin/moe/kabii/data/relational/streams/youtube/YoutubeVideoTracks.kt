package moe.kabii.data.relational.streams.youtube

import moe.kabii.data.relational.discord.DiscordObjects
import moe.kabii.data.relational.streams.TrackedStreams
import moe.kabii.util.extensions.WithinExposedContext
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select

object YoutubeVideoTracks : IntIdTable() {
    val ytVideo = reference("yt_video", YoutubeVideos, ReferenceOption.CASCADE)
    val discordChannel = reference("discord_channel", DiscordObjects.Channels, ReferenceOption.CASCADE)
    val tracker = reference("discord_user_tracked", DiscordObjects.Users, ReferenceOption.CASCADE)
    val mentionRole = long("role_mention").nullable()

    override val primaryKey = PrimaryKey(ytVideo, discordChannel)
}

class YoutubeVideoTrack(id: EntityID<Int>) : IntEntity(id) {
    var ytVideo by YoutubeVideo referencedOn YoutubeVideoTracks.ytVideo
    var discordChannel by DiscordObjects.Channel referencedOn YoutubeVideoTracks.discordChannel
    var tracker by DiscordObjects.User referencedOn YoutubeVideoTracks.tracker
    var mentionRole by YoutubeVideoTracks.mentionRole

    companion object : IntEntityClass<YoutubeVideoTrack>(YoutubeVideoTracks) {
        @WithinExposedContext
        fun insertOrUpdate(ytVideo: YoutubeVideo, discordChannel: DiscordObjects.Channel, tracker: DiscordObjects.User, mentionRoleId: Long?): YoutubeVideoTrack {
            val existing = find {
                YoutubeVideoTracks.ytVideo eq ytVideo.id and
                        (YoutubeVideoTracks.discordChannel eq discordChannel.id)
            }.firstOrNull()
            return existing?.apply {
                this.mentionRole = mentionRoleId
            } ?: new {
                this.ytVideo = ytVideo
                this.discordChannel = discordChannel
                this.tracker = tracker
                this.mentionRole = mentionRoleId
            }
        }

        @WithinExposedContext
        fun getForVideo(ytVideo: YoutubeVideo) = find {
            YoutubeVideoTracks.ytVideo eq ytVideo.id
        }

        @WithinExposedContext
        fun getForChannel(ytChan: TrackedStreams.StreamChannel) = YoutubeVideoTrack.wrapRows(
            YoutubeVideoTracks
                .innerJoin(YoutubeVideos)
                .select {
                    YoutubeVideos.ytChannel eq ytChan.id
                }
        )
    }
}