package moe.kabii.trackers.videos.youtube.watcher

import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.GuildMessageChannel
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.spec.EmbedCreateFields
import discord4j.rest.http.client.ClientException
import discord4j.rest.util.Color
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import moe.kabii.LOG
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.data.mongodb.guilds.YoutubeSettings
import moe.kabii.data.relational.discord.MessageHistory
import moe.kabii.data.relational.streams.TrackedStreams
import moe.kabii.data.relational.streams.youtube.*
import moe.kabii.discord.util.Embeds
import moe.kabii.net.NettyFileServer
import moe.kabii.trackers.TrackerUtil
import moe.kabii.trackers.videos.StreamWatcher
import moe.kabii.trackers.videos.youtube.YoutubeParser
import moe.kabii.trackers.videos.youtube.YoutubeVideoInfo
import moe.kabii.trackers.videos.youtube.subscriber.YoutubeSubscriptionManager
import moe.kabii.util.DurationFormatter
import moe.kabii.util.constants.EmojiCharacters
import moe.kabii.util.constants.MagicNumbers
import moe.kabii.util.extensions.*
import org.apache.commons.lang3.StringUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.time.Duration
import java.time.Instant

abstract class YoutubeNotifier(private val subscriptions: YoutubeSubscriptionManager) : StreamWatcher(subscriptions.discord) {

    companion object {
        private val liveColor = YoutubeParser.color
        private val inactiveColor = Color.of(8847360)
        private val scheduledColor = Color.of(4270381)
        private val uploadColor = Color.of(16748800)
        private val creationColor = Color.of(16749824)
    }

    @WithinExposedContext
    suspend fun streamStart(video: YoutubeVideoInfo, dbVideo: YoutubeVideo) {
        // video will have live info if this function is called
        val liveInfo = checkNotNull(video.liveInfo)
        val viewers = liveInfo.concurrent ?: 0

        // create live stats object for video
        // should not already exist
        if(transaction { YoutubeLiveEvent.liveEventFor(dbVideo) != null }) return
        val liveEvent = propagateTransaction {
            YoutubeLiveEvent.new {
                this.ytVideo = dbVideo
                this.lastThumbnail = video.thumbnail
                this.lastChannelName = video.channel.name
                this.peakViewers = viewers
                this.uptimeTicks = 1
                this.averageViewers = viewers
                this.premiere = video.premiere
            }
        }
        dbVideo.liveEvent = liveEvent

        // post notifications to all enabled targets
        filteredTargets(dbVideo.ytChannel, liveEvent::shouldPostLiveNotice).forEach { target ->
            try {
                createLiveNotification(dbVideo, video, target, new = true)
            } catch(e: Exception) {
                // catch and consume all exceptions here - if one target fails, we don't want this to affect the other targets in potentially different discord servers
                LOG.warn("Error while creating live notification for channel: ${dbVideo.ytChannel} :: ${e.message}")
                LOG.debug(e.stackTraceString)
            }
        }

        //  targets that specifically asked for this video and may not have the channel tracked at all are a little different
        YoutubeVideoTrack.getForVideo(dbVideo).forEach { track ->
            try {
                sendLiveReminder(video, track)
            } catch(e: Exception) {
                LOG.warn("Error while sending live reminder for channel, dropping notification without sending: ${dbVideo.ytChannel} :: ${e.message}")
                LOG.debug(e.stackTraceString)
            }
            track.delete()
        }
    }

    @WithinExposedContext
    suspend fun streamEnd(video: YoutubeVideoInfo?, dbStream: YoutubeLiveEvent) {
        // edit/delete all notifications and remove stream from db when stream ends

        dbStream.ytVideo.notifications.forEach { notification ->
            try {

                val dbMessage = notification.messageID
                val channel = if(dbMessage != null) {
                    val existingNotif = discord.getMessageById(dbMessage.channel.channelID.snowflake, dbMessage.messageID.snowflake).awaitSingle()

                    val features = getStreamConfig(notification.targetID)

                    if(features.summaries) {

                        val embed = Embeds.other(if(dbStream.premiere) uploadColor else inactiveColor)
                            .run {
                                val viewers = "${dbStream.averageViewers} avg. / ${dbStream.peakViewers} peak"
                                if(features.viewers && dbStream.peakViewers > 0) withFields(EmbedCreateFields.Field.of("Viewers", viewers, true)) else this
                            }
                            .run {
                                if(video != null) {

                                    // stream has ended and vod is available - edit notifications to reflect
                                    val vodMessage = if(dbStream.premiere) " premiered a new video on YouTube!" else " was live."
                                    val durationStr = DurationFormatter(video.duration).colonTime

                                    withAuthor(EmbedCreateFields.Author.of("${video.channel.name}$vodMessage", video.channel.url, video.channel.avatar))
                                        .withUrl(video.url)
                                        .withFooter(EmbedCreateFields.Footer.of("Stream ended", NettyFileServer.youtubeLogo))
                                        .run {
                                            val timestamp = video.liveInfo?.endTime
                                            if(timestamp != null) withTimestamp(timestamp) else this
                                        }
                                        .withDescription("Video available: [$durationStr]")
                                        .withTitle(video.title)
                                        .withThumbnail(video.thumbnail)
                                } else {
                                    // this stream has ended and no vod is available (private or deleted) - edit notifications to reflect
                                    // here, we can only provide information from our database
                                    val lastTitle = dbStream.ytVideo.lastTitle
                                    val channelName = dbStream.lastChannelName
                                    val videoLink = "https://youtube.com/watch?v=${dbStream.ytVideo.videoId}"
                                    val channelLink = "https://youtube.com/channel/${dbStream.ytVideo.ytChannel.siteChannelID}"

                                    withAuthor(EmbedCreateFields.Author.of("$channelName was live.", channelLink, null))
                                        .withUrl(videoLink)
                                        .withFooter(EmbedCreateFields.Footer.of("Stream ended (approximate)", NettyFileServer.youtubeLogo))
                                        .withTimestamp(Instant.now())
                                        .withTitle("No VOD is available.")
                                        .withThumbnail(dbStream.lastThumbnail)
                                        .withDescription("Last video title: $lastTitle")
                                }
                            }


                        existingNotif.edit()
                            .withEmbeds(embed)
                            .then(mono {
                                TrackerUtil.checkUnpin(existingNotif)
                            })
                    } else {

                        existingNotif.delete()

                    }.thenReturn(Unit).tryAwait()
                    existingNotif.channel.awaitSingle()
                } else discord.getChannelById(notification.targetID.discordChannel.channelID.snowflake).ofType(GuildMessageChannel::class.java).awaitSingle()
                checkAndRenameChannel(channel, endingStream = dbStream.ytVideo.ytChannel)

            } catch(ce: ClientException) {
                LOG.info("Unable to find YouTube stream notification $notification :: ${ce.status.code()}")
            } catch(e: Exception) {
                // catch and consume all exceptions here - if one target fails, we don't want this to affect the other targets in potentially different discord servers
                LOG.info("Error in YouTube #streamEnd for stream $dbStream :: ${e.message}")
                LOG.debug(e.stackTraceString)
            } finally {
                // delete the notification from db either way, we are done with it
                notification.delete()
            }
        }

        // delete live stream event for this channel
        dbStream.delete()
    }

    @WithinExposedContext
    suspend fun streamUpcoming(dbEvent: YoutubeScheduledEvent, ytVideo: YoutubeVideoInfo, scheduled: Instant) {
        val untilStart = Duration.between(Instant.now(), scheduled)

        // check if any targets would like notification for this upcoming stream
        filteredTargets(dbEvent.ytVideo.ytChannel) { yt ->
            if (yt.upcomingNotificationDuration != null) {
                // check upcoming stream is within this target's notice 'range'
                val range = Duration.parse(yt.upcomingNotificationDuration)
                range >= untilStart

            } else false
        }.filter { target ->
            !YoutubeScheduledNotification[dbEvent, target].empty().not()
        }.forEach { target ->
            try {
                createUpcomingNotification(dbEvent, ytVideo, target, scheduled)
            } catch(e: Exception) {
                // catch and consume all exceptions here - if one target fails, we don't want this to affect the other targets in potentially different discord servers
                LOG.warn("Error while creating upcoming notification for stream: $ytVideo :: ${e.message}")
            }
        }
    }

    @WithinExposedContext
    suspend fun streamCreated(dbVideo: YoutubeVideo, ytVideo: YoutubeVideoInfo) {
        // check if any targets would like notification for this stream frame creation
        filteredTargets(dbVideo.ytChannel, YoutubeSettings::streamCreation)
            .forEach { target ->
                try {
                    createInitialNotification(ytVideo, target)
                } catch(e: Exception) {
                    // catch and consume all exceptions here - if one target fails, we don't want this to affect the other targets in potentially different discord servers
                    LOG.warn("Error while creating 'creation' notification for stream: $ytVideo :: ${e.message}")
                }
            }
    }

    @WithinExposedContext
    suspend fun videoUploaded(dbVideo: YoutubeVideo, ytVideo: YoutubeVideoInfo) {
        if(ytVideo.liveInfo != null) return // do not post 'uploaded a video' if this was a VOD
        if(Duration.between(ytVideo.published, Instant.now()) > Duration.ofHours(3L)) return // do not post 'uploaded a video' if this is an old video (before we tracked the channel) that was just updated or intaken by the track command

        // check if any targets would like notification for this video upload
        filteredTargets(dbVideo.ytChannel, YoutubeSettings::uploads)
            .forEach { target ->
                if(!YoutubeNotification.getExisting(target, dbVideo).empty()) return@forEach
                try {
                    val new = createVideoNotification(ytVideo, target) ?: return@forEach
                    YoutubeNotification.new {
                        this.messageID = MessageHistory.Message.getOrInsert(new)
                        this.targetID = target
                        this.videoID = dbVideo
                    }
                } catch(e: Exception) {
                    // catch and consume all exceptions here - if one target fails, we don't want this to affect the other targets in potentially different discord servers
                    LOG.warn("Error while creating 'upload' notification for stream: $ytVideo :: ${e.message}")
                }
            }
    }

    //       ----------------

    @WithinExposedContext
    suspend fun filteredTargets(channel: TrackedStreams.StreamChannel, filter: (YoutubeSettings) -> Boolean): List<TrackedStreams.Target> {
        val channelId = channel.siteChannelID
        val activeTargets = getActiveTargets(channel)
        return if(activeTargets == null) {
            // channel has been untracked entirely
            subscriptions.subscriber.unsubscribe(channelId)
            emptyList()
        } else activeTargets
            .filter { target ->
                val (_, features) =
                    GuildConfigurations.findFeatures(target.discordChannel.guild?.guildID, target.discordChannel.channelID)
                val yt = features?.youtubeSettings ?: YoutubeSettings()
                filter(yt)
            }
    }

    @WithinExposedContext
    suspend fun createUpcomingNotification(event: YoutubeScheduledEvent, video: YoutubeVideoInfo, target: TrackedStreams.Target, time: Instant): Message? {
        // get target channel in discord
        val chan = getUpcomingChannel(target)

        val message = try {
            val shortTitle = StringUtils.abbreviate(video.title, MagicNumbers.Embed.TITLE)
            val embed = Embeds.other(scheduledColor)
                .withAuthor(EmbedCreateFields.Author.of("${video.channel.name} has an upcoming stream!", video.channel.url, video.channel.avatar))
                .withUrl(video.url)
                .withTitle(shortTitle)
                .withThumbnail(video.thumbnail)
                .withFooter(EmbedCreateFields.Footer.of("Scheduled start time ", NettyFileServer.youtubeLogo))
                .withTimestamp(time)
            chan.createMessage(embed).awaitSingle()
        } catch(ce: ClientException) {
            val err = ce.status.code()
            if(err == 403) {
                LOG.warn("Unable to send upcoming notification to channel '${chan.id.asString()}'. Disabling feature in channel. YoutubeNotifier.java")
                TrackerUtil.permissionDenied(chan, FeatureChannel::streamTargetChannel, target::delete)
                return null
            } else throw ce
        }
        YoutubeScheduledNotification.create(event, target)
        TrackerUtil.checkAndPublish(message)
        return message
    }

    @WithinExposedContext
    suspend fun createVideoNotification(video: YoutubeVideoInfo, target: TrackedStreams.Target): Message? {
        // get target channel in discord
        val chan = getChannel(target.discordChannel.guild?.guildID, target.discordChannel.channelID, target)

        // get channel stream embed settings
        val guildId = target.discordChannel.guild?.guildID
        val guildConfig = guildId?.run(GuildConfigurations::getOrCreateGuild)
        val features = getStreamConfig(target)

        // get mention role from db if one is registered
        val mentionRole = if(guildId != null) {
            getMentionRoleFor(target.streamChannel, guildId, chan, features)
        } else null

        val new = try {
            val shortDescription = StringUtils.abbreviate(video.description, 200)
            val shortTitle = StringUtils.abbreviate(video.title, MagicNumbers.Embed.TITLE)

            val embed = Embeds.other("Video description: $shortDescription", uploadColor)
                .withAuthor(EmbedCreateFields.Author.of("${video.channel.name} posted a new video on YouTube!", video.channel.url, video.channel.avatar))
                .withUrl(video.url)
                .withTitle(shortTitle)
                .run { if(features.thumbnails) withImage(video.thumbnail) else withThumbnail(video.thumbnail) }
                .run {
                    val videoLength = DurationFormatter(video.duration).colonTime
                    withFooter(EmbedCreateFields.Footer.of("YouTube Upload: $videoLength", NettyFileServer.youtubeLogo))
                }
            val mentionMessage = if(mentionRole != null) chan.createMessage(mentionRole.discord.mention) else chan.createMessage()
            mentionMessage.withEmbeds(embed).awaitSingle()

        } catch(ce: ClientException) {
            val err = ce.status.code()
            if(err == 403) {
                LOG.warn("Unable to send video upload notification to channel '${chan.id.asString()}'. Disabling feature in channel. YoutubeNotifier.java")
                TrackerUtil.permissionDenied(chan, FeatureChannel::streamTargetChannel, target::delete)
                return null
            } else throw ce
        }
        TrackerUtil.checkAndPublish(new, guildConfig?.guildSettings)
        return new
    }

    @WithinExposedContext
    suspend fun createInitialNotification(video: YoutubeVideoInfo, target: TrackedStreams.Target): Message? {
        val chan = getChannel(target.discordChannel.guild?.guildID, target.discordChannel.channelID, target)

        // get channel stream embed settings
        val guildId = target.discordChannel.guild?.guildID
        val guildConfig = guildId?.run(GuildConfigurations::getOrCreateGuild)

        val startTime = video.liveInfo?.scheduledStart!!
        val timeUntil = Duration.between(Instant.now(), startTime)
        val eta = DurationFormatter(timeUntil).fullTime

        val shortDescription = StringUtils.abbreviate(video.description, 200)
        val shortTitle = StringUtils.abbreviate(video.title, MagicNumbers.Embed.TITLE)

        val embed = Embeds.other("Stream scheduled to start in ~$eta\n\nVideo description: $shortDescription", creationColor)
            .withAuthor(EmbedCreateFields.Author.of("${video.channel.name} scheduled a new stream!", video.channel.url, video.channel.avatar))
            .withUrl(video.url)
            .withTitle(shortTitle)
            .withThumbnail(video.thumbnail)
            .withFooter(EmbedCreateFields.Footer.of("Scheduled start time ", NettyFileServer.youtubeLogo))
            .withTimestamp(startTime)
        val new = try {
            chan.createMessage(embed).awaitSingle()
        } catch(ce: ClientException) {
            val err = ce.status.code()
            if(err == 403) {
                LOG.warn("Unable to send video creation notification to channel '${chan.id.asString()}'. Disabling feature in channel. YoutubeNotifier.java")
                TrackerUtil.permissionDenied(chan, FeatureChannel::streamTargetChannel, target::delete)
                return null
            } else throw ce
        }
        TrackerUtil.checkAndPublish(new, guildConfig?.guildSettings)
        return new
    }

    @WithinExposedContext
    suspend fun sendLiveReminder(liveStream: YoutubeVideoInfo, videoTrack: YoutubeVideoTrack) {
        // get target channel in Discord
        val chan = getChannel(videoTrack.discordChannel.guild?.guildID, videoTrack.discordChannel.channelID, null)

        val mention = if(videoTrack.mentionRole != null) "<@&${videoTrack.mentionRole}> " else "<@${videoTrack.tracker.userID}> Livestream reminder: "
        val new = chan
            .createMessage("$mention**${liveStream.channel.name}** is now live: ${liveStream.url}")
            .awaitSingle()
        TrackerUtil.checkAndPublish(new)
    }

    @WithinExposedContext
    @Throws(ClientException::class)
    suspend fun createLiveNotification(dbVideo: YoutubeVideo, liveStream: YoutubeVideoInfo, target: TrackedStreams.Target, new: Boolean = true): Message? {

        // get target channel in discord, make sure it still exists
        val guildId = target.discordChannel.guild?.guildID
        val chan = getChannel(guildId, target.discordChannel.channelID, target)

        // get channel stream embed settings
        val guildConfig = guildId?.run(GuildConfigurations::getOrCreateGuild)
        val features = getStreamConfig(target)

        // get mention role from db if one is registered
        val mention = if(guildId != null) {
            val old = liveStream.liveInfo?.startTime?.run { Duration.between(this, Instant.now()) > Duration.ofMinutes(15) }
            if(old == true) null
            else getMentionRoleFor(target.streamChannel, guildId, chan, features)
        } else null

        try {
            val shortDescription = StringUtils.abbreviate(liveStream.description, 150)
            val shortTitle = StringUtils.abbreviate(liveStream.title, MagicNumbers.Embed.TITLE)
            val startTime = liveStream.liveInfo?.startTime
            val sinceStr = if(startTime != null) " since " else " "

            val liveMessage = when {
                liveStream.premiere -> " is premiering a new video!"
                new -> " went live!"
                else -> " is live."
            }
            val embed = Embeds.other(shortDescription, if(liveStream.premiere) uploadColor else liveColor)
                .withAuthor(EmbedCreateFields.Author.of("${liveStream.channel.name}$liveMessage ${EmojiCharacters.liveCircle}", liveStream.url, liveStream.channel.avatar))
                .withUrl(liveStream.url)
                .withTitle(shortTitle)
                .withFooter(EmbedCreateFields.Footer.of("Live on YouTube$sinceStr", NettyFileServer.youtubeLogo))
                .run { if(features.thumbnails) withImage(liveStream.thumbnail) else withThumbnail(liveStream.thumbnail) }
                .run { if(startTime != null) withTimestamp(startTime) else this }

            val mentionMessage = if(mention != null) {
                mention.db.lastMention = DateTime.now()
                chan.createMessage(mention.discord.mention)
            } else chan.createMessage()

            val newNotification = mentionMessage.withEmbeds(embed).awaitSingle()

            TrackerUtil.checkAndPublish(newNotification, guildConfig?.guildSettings)
            TrackerUtil.pinActive(discord, features, newNotification)

            // log message in db
            YoutubeNotification.new {
                this.messageID = MessageHistory.Message.getOrInsert(newNotification)
                this.targetID = target
                this.videoID = dbVideo
            }

            // edit channel name if feature is enabled and stream starts
            checkAndRenameChannel(chan)

            return newNotification

        } catch (ce: ClientException) {
            val err = ce.status.code()
            if(err == 403) {
                LOG.warn("Unable to send stream notification to channel '${chan.id.asString()}'. Disabling feature in channel. YoutubeNotifier.java")
                TrackerUtil.permissionDenied(chan, FeatureChannel::streamTargetChannel, target::delete)
                return null
            } else throw ce
        }
    }

    @WithinExposedContext
    private suspend fun getUpcomingChannel(target: TrackedStreams.Target): MessageChannel {
        val (guildConfig, features) = GuildConfigurations.findFeatures(target.discordChannel.guild?.guildID, target.discordChannel.channelID)
        val yt = features?.youtubeSettings ?: YoutubeSettings()

        val altChannel = if(yt.upcomingChannel != null) {
            try {
                discord.getChannelById(yt.upcomingChannel!!.snowflake)
                    .ofType(MessageChannel::class.java)
                    .awaitSingle()
            } catch(e: Exception) {
                if(e is ClientException && (e.status.code() == 404 || e.status.code() == 403)) {
                    // if 'upcoming' channel is not accessible - just reset it to the actual yt channel
                    yt.upcomingChannel = target.discordChannel.channelID
                    guildConfig!!.save()
                    null
                } else throw e
            }
        } else null

        // if altChannel failed by clientexception or did not exist, get regular channel
        return altChannel ?: try {
            discord.getChannelById(target.discordChannel.channelID.snowflake)
                .ofType(MessageChannel::class.java)
                .awaitSingle()
        } catch(e: Exception) {
            LOG.warn("${Thread.currentThread().name} - YoutubeNotifier-getUpcomingChannel :: Unable to get Discord channel: ${e.message}")
            throw e
        }
    }
}