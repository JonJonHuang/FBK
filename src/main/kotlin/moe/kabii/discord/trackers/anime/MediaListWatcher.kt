package moe.kabii.discord.trackers.anime

import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.`object`.entity.channel.PrivateChannel
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.rest.http.client.ClientException
import kotlinx.coroutines.*
import moe.kabii.LOG
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.MediaSite
import moe.kabii.data.mongodb.TrackedMediaList
import moe.kabii.data.mongodb.TrackedMediaLists
import moe.kabii.rusty.Err
import moe.kabii.rusty.Ok
import moe.kabii.structure.*
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

class MediaListWatcher(val discord: GatewayDiscordClient) : Thread("TrackedMediaLists") {
    var active = true
    private val listWatcherThreads = Executors.newFixedThreadPool(MediaSite.values().size).asCoroutineScope()

    override fun run() {
        while (active) {
            // save current state of tracked lists
            val lists = TrackedMediaLists.mediaLists.toList()
            // specifically only one thread per service - bei/ng careful of rate limits!
            runBlocking {
                val start = Instant.now()
                lists.groupBy { it.list.site }
                        .map { (k, v) -> spawn(k, v) }
                        .joinAll()
                val runDuration = Duration.between(start, Instant.now())
                // only run every few minutes at max
                if(runDuration.toMinutes() < 5) {
                    val wait = 300000L - runDuration.toMillis()
                    delay(wait)
                }
            }
        }
    }

    private fun spawn(site: MediaSite, trackedlists: List<TrackedMediaList>): Job = listWatcherThreads.launch {
        val lists = trackedlists
                .associateBy {
                    try {
                        site.parser.parse(it.list.id)
                    } catch(e: Exception) {
                        LOG.error("Uncaught exception parsing media item: ${it.list.id} :: ${e.message}")
                        LOG.info(e.stackTraceString)
                        Err(MediaListIOErr(e))
                    }
                }
        for((newList, savedList) in lists) {
            if(savedList.targets.isEmpty()) {
                savedList.removeSelf()
                continue
            }
            val new = if(newList is Ok) newList.value.media else return@launch
            var newEntry = false // these could perhaps be heirarchical for a typical use case. but for customization they are seperate configurations and any one must be met to post the message
            var statusChange = false
            var statusUpdate = false

            for(newMedia in new) {
                val oldMedia = savedList.savedMediaList.media.find { saved -> saved.mediaID == newMedia.mediaID }
                // don't create embed builder yet because the vast majority of media checked will not need one
                var builder: MediaEmbedBuilder? = null
                if (oldMedia == null) {
                    // anime is new to list
                    newEntry = true
                    builder = MediaEmbedBuilder(newMedia)
                    builder.descriptionFmt = when (newMedia.status) {
                        ConsumptionStatus.COMPLETED -> "Added %s to their list as Completed."
                        ConsumptionStatus.DROPPED -> "Added %s to their list as Dropped."
                        ConsumptionStatus.HOLD -> "Added %s to their list as On-hold."
                        ConsumptionStatus.PTW -> "Added %s to their Plan to Watch."
                        ConsumptionStatus.WATCHING -> "Started watching %s!"
                    }
                } else {
                    // media was already on list, check for changes
                    if (newMedia.status != oldMedia.status) {
                        statusChange = true
                        builder = MediaEmbedBuilder(newMedia)
                        builder.descriptionFmt = when (newMedia.status) {
                            ConsumptionStatus.WATCHING -> when(newMedia.type) {
                                MediaType.ANIME -> "Started watching %s!"
                                MediaType.MANGA -> "Started reading %s!"
                            }
                            ConsumptionStatus.PTW -> "Put %s back on their Plan to Watch."
                            ConsumptionStatus.HOLD -> "Placed %s on hold."
                            ConsumptionStatus.DROPPED -> "Dropped %s."
                            ConsumptionStatus.COMPLETED -> "Finished %s!"
                        }
                    }
                    // if episode changed, or score changed
                    val progress = newMedia.watched - oldMedia.watched
                    if (progress != 0) {
                        statusUpdate = true
                        builder = builder ?: MediaEmbedBuilder(newMedia)
                        builder.descriptionFmt = when (newMedia.status) {
                            ConsumptionStatus.COMPLETED -> "Updated their completed status for %s."
                            ConsumptionStatus.DROPPED -> "Updated their dropped status for %s."
                            ConsumptionStatus.HOLD -> "Updated their on-hold status for %s."
                            ConsumptionStatus.PTW -> "Updated their Plan to Watch for %s."
                            ConsumptionStatus.WATCHING -> {
                                builder.oldProgress = oldMedia.progressStr(withTotal = false)
                                when(newMedia.type) {
                                    MediaType.ANIME -> "Watched $progress ${"episode".plural(progress)} of %s."
                                    MediaType.MANGA -> "Read $progress ${"chapter".plural(progress)} of %s."
                                }
                            }
                        }
                        if (newMedia.score != oldMedia.score) {
                            statusUpdate = true
                            builder.oldScore = oldMedia.scoreStr(withMax = false)
                        }
                    }
                }
                if (builder != null) {
                    for (target in savedList.targets) {
                        // send embed to all channels this user's mal is tracked in
                        val user = discord.getUserById(target.discordUserID.snowflake).tryBlock().orNull() ?: continue //
                        builder.withUser(user)

                        discord.getChannelById(target.channelID.snowflake)
                                .ofType(MessageChannel::class.java)
                                .filter { chan ->
                                    // check if channel is currently enabled (or pm channel)
                                    when(chan) {
                                        is TextChannel -> {
                                            val config = GuildConfigurations.getOrCreateGuild(chan.guildId.asLong())
                                            val featureSettings = config.options.featureChannels[chan.id.asLong()]?.featureSettings
                                            // qualifications for posting in tis particular guild. same embed might be posted in any number of other guilds, so this is checked at the very end when sending.
                                            when {
                                                featureSettings == null -> false
                                                featureSettings.mediaNewItem && newEntry -> true
                                                featureSettings.mediaStatusChange && statusChange -> true
                                                featureSettings.mediaUpdatedStatus && statusUpdate -> true
                                                else -> false
                                            }
                                        }
                                        is PrivateChannel -> true
                                        else -> false
                                    }
                                }
                                .flatMap { chan ->
                                    chan.createEmbed { spec ->
                                        builder.createEmbedConsumer(savedList.list)(spec)
                                    }
                                }
                                .onErrorResume { e ->
                                    if(e is ClientException && e.status.code() == 404) {
                                        // remove target if no longer valid
                                        savedList.targets -= target
                                    }

                                    LOG.warn("Uncaught exception in MediaListWatcher: ${e.message} while sending anime list update to ${target.channelID}")
                                    LOG.info(e.stackTraceString)
                                    Mono.empty()
                                }
                                .subscribe()
                    }
                }
            }
            // save list state
            if (newEntry || statusChange || statusUpdate) {
                savedList.savedMediaList = newList.value
                savedList.save()
            }
        }
    }
}