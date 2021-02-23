package moe.kabii.discord.trackers.twitter.watcher

import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.rest.http.client.ClientException
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.time.delay
import moe.kabii.LOG
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.data.mongodb.guilds.TwitterSettings
import moe.kabii.data.relational.twitter.TwitterFeed
import moe.kabii.discord.trackers.ServiceRequestCooldownSpec
import moe.kabii.discord.trackers.TrackerUtil
import moe.kabii.discord.trackers.twitter.TwitterDateTimeUpdateException
import moe.kabii.discord.trackers.twitter.TwitterParser
import moe.kabii.discord.trackers.twitter.TwitterRateLimitReachedException
import moe.kabii.discord.translation.Translator
import moe.kabii.discord.util.MagicNumbers
import moe.kabii.discord.util.fbkColor
import moe.kabii.structure.extensions.applicationLoop
import moe.kabii.structure.extensions.snowflake
import moe.kabii.structure.extensions.stackTraceString
import org.apache.commons.lang3.StringUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.math.max

class TwitterChecker(val discord: GatewayDiscordClient, val cooldowns: ServiceRequestCooldownSpec) : Runnable {

    override fun run() {
        applicationLoop {
            val start = Instant.now()

            newSuspendedTransaction {
                try {
                    // get all tracked twitter feeds
                    val feeds = TwitterFeed.all()

                    // feeds who are completely inactive and since_id has fallen out of the valid range
                    val requireUpdate = mutableListOf<TwitterFeed>()
                    var maxId = 0L

                    var first = true
                    feeds.forEach { feed ->
                        if(!first) {
                            delay(Duration.ofMillis(cooldowns.callDelay))
                        } else first = false

                        val targets = feed.targets.toList()

                        if(targets.isEmpty()) {
                            LOG.info("Untracking Twitter Feed '${feed.userId} as it is not tracked in any channels.")
                            feed.delete()
                            return@forEach
                        }

                        // determine if any targets want RT or quote tweets
                        var pullRetweets = false
                        var pullQuotes = false

                        targets.forEach { target ->
                            val features = GuildConfigurations.findFeatures(target)
                            val twitter = features?.twitterSettings ?: TwitterSettings()

                            if(twitter.displayRetweet) pullRetweets = true
                            if(twitter.displayQuote) pullQuotes = true
                        }

                        val limits = TwitterParser.TwitterQueryLimits(
                            sinceId = feed.lastPulledTweet,
                            includeRT = pullRetweets,
                            includeQuote = pullQuotes
                        )
                        val recent = try {
                            TwitterParser.getRecentTweets(feed.userId, limits)
                        } catch(sinceId: TwitterDateTimeUpdateException) {
                            LOG.info("Twitter feed '${feed.userId}' is far out of date and the Tweets since_id query was rejected")
                            requireUpdate.add(feed)
                            null
                        } catch(rate: TwitterRateLimitReachedException) {
                            val reset = rate.reset
                            LOG.warn("Twitter rate limit reached: sleeping ${reset.seconds} seconds")
                            delay(reset)
                            null
                        } catch(e: Exception) {
                            LOG.warn("TwitterChecker: Error in Twitter call: ${e.message}")
                            LOG.debug(e.stackTraceString)
                            delay(Duration.ofMillis(100L))
                            null
                        }
                        recent ?: return@forEach
                        val (user, tweets) = recent
                        val latest = tweets.maxOf { tweet ->

                            // if tweet is after last posted tweet and within 2 hours (arbitrary - to prevent spam when initially tracking) - send discord notifs
                            val age = Duration.between(tweet.createdAt, Instant.now())
                            if (feed.lastPulledTweet ?: 0 >= tweet.id || age > Duration.ofHours(2)) return@maxOf tweet.id // if already handled or too old, skip, but do not pull tweet ID again

                            // send discord notifs - check if any channels request
                            targets.forEach target@{ target ->
                                try {
                                    // post a notif to this target
                                    val channel = discord.getChannelById(target.discordChannel.channelID.snowflake)
                                        .ofType(MessageChannel::class.java)
                                        .awaitSingle()

                                    val features = GuildConfigurations.findFeatures(target)
                                    val twitter = features?.twitterSettings ?: TwitterSettings()

                                    if(!tweet.notifyOption.get(twitter)) return@target

                                    val action = when {
                                        tweet.retweet -> "retweeted \uD83D\uDD01"
                                        tweet.reply -> "replied to a Tweet \uD83D\uDCAC"
                                        tweet.quote -> "quoted a Tweet \uD83D\uDDE8"
                                        else -> "posted a new Tweet"
                                    }

                                    if(tweet.sensitive == true && target.discordChannel.guild != null) {
                                        // filter potentially nsfw tweets in guilds
                                        val guildChan = channel as? TextChannel // will fail for news channels as they can not be marked nsfw
                                        if(guildChan?.isNsfw != true) {
                                            channel.createEmbed { embed ->
                                                fbkColor(embed)
                                                embed.setDescription("[**@${user.username}**](${user.url}) $action which may contain sensitive content.")
                                            }.awaitSingle()
                                            return@target
                                        }
                                    }

                                    val notif = channel.createMessage { spec ->
                                        // todo channel setting for custom message ?
                                        spec.setContent("**@${user.username}** $action: https://twitter.com/${user.username}/status/${tweet.id}")

                                    }.awaitSingle()

                                    TrackerUtil.checkAndPublish(notif)

                                    if(twitter.autoTranslate && !tweet.retweet && tweet.text?.isBlank() == false) {

                                        val service = Translator.getService()
                                        val defaultLang = GuildConfigurations
                                            .getOrCreateGuild(target.discordChannel.guild!!.guildID)
                                            .translator.defaultTargetLanguage
                                            .run(service.supportedLanguages::get) ?: service.defaultLanguage()
                                        try {
                                            val translation = service.translateText(from = null, to = defaultLang, rawText = tweet.text)

                                            if(translation.originalLanguage != translation.targetLanguage && translation.translatedText.isNotBlank()) {
                                                channel.createEmbed { embed ->
                                                    fbkColor(embed)
                                                    embed.setAuthor("@${user.username} Tweet Translation", tweet.url, user.profileImage)
                                                    embed.setDescription(StringUtils.abbreviate(translation.translatedText, MagicNumbers.Embed.DESC))

                                                    embed.setFooter("Translator: ${service.fullName}\nTranslation: ${translation.originalLanguage.tag} -> ${translation.targetLanguage.tag}", null)
                                                }.awaitSingle()
                                            }
                                        } catch(e: IOException) {
                                            LOG.warn("Tweet translation failed: ${e.message} :: ${e.stackTraceString}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is ClientException && e.status.code() == 403) {
                                        TrackerUtil.permissionDenied(target.discordChannel.guild?.guildID, target.discordChannel.channelID, FeatureChannel::twitterChannel, target::delete)
                                        LOG.warn("Unable to send stream notification to channel '${target.discordChannel.channelID}'. Disabling feature in channel. TwitterChecker.java")
                                    } else {
                                        LOG.warn("Error sending stream notification to channel: ${e.message}")
                                        LOG.debug(e.stackTraceString)
                                    }
                                }
                            }
                            tweet.id // return tweet id for 'max' calculation to find the newest tweet that was returned
                        }
                        if(latest > feed.lastPulledTweet ?: 0L) {
                            newSuspendedTransaction {
                                feed.lastPulledTweet = latest
                            }
                        }
                        if(latest > maxId) maxId = latest
                    }

                    requireUpdate.forEach { feed ->
                        newSuspendedTransaction {
                            feed.lastPulledTweet = maxId
                        }
                    }
                } catch(e: Exception) {
                    LOG.info("Uncaught exception in ${Thread.currentThread().name} :: ${e.message}")
                    LOG.debug(e.stackTraceString)
                }
            }
            val runDuration = Duration.between(start, Instant.now())
            val delay = cooldowns.minimumRepeatTime - runDuration.toMillis()
            delay(Duration.ofMillis(max(delay, 0L)))
        }
    }
}