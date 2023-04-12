package moe.kabii.ytchat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import moe.kabii.LOG
import moe.kabii.data.relational.streams.TrackedStreams
import moe.kabii.data.relational.streams.youtube.YoutubeVideo
import moe.kabii.data.relational.streams.youtube.YoutubeVideos
import moe.kabii.data.relational.streams.youtube.ytchat.MembershipConfigurations
import moe.kabii.discord.util.MetaData
import moe.kabii.instances.DiscordInstances
import moe.kabii.internal.ytchat.HoloChats
import moe.kabii.util.extensions.applicationLoop
import moe.kabii.util.extensions.propagateTransaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import java.io.File
import java.time.Duration
import kotlin.concurrent.thread

class YoutubeChatWatcher(instances: DiscordInstances) : Runnable {

    val activeChats = mutableMapOf<String, Process>()

    private val parseQueue = Channel<YTChatData>(Channel.UNLIMITED)
    val holoChatQueue = Channel<YTMessageData>(Channel.UNLIMITED)

    private val parser = YoutubeChatParser(instances, this)
    private val holochats = HoloChats(instances)

    private val scriptDir = File("files/scripts")
    private val scriptName = "ytchat.py"
    init {
        scriptDir.mkdirs()
    }

    private val parserTask = Runnable {
        runBlocking {
            for(chatData in parseQueue) {
                parser.handleChatData(chatData)
            }
        }
    }

    private val holoChatTask = Runnable {
        runBlocking {
            for(ytChat in holoChatQueue) {
                holochats.handleHoloChat(ytChat)
            }
        }
    }

    override fun run() {
        if(!MetaData.host) return

        // launch thread to parse/handle db ops with
        val parserThread = Thread(parserTask, "YTChatParser")
        parserThread.start()
        val holoChatThread = Thread(holoChatTask, "HoloChats")
        holoChatThread.start()

        val chatScript = File(scriptDir, scriptName)
        require(chatScript.exists()) { "YouTube chat script not found! ${chatScript.absolutePath}" }
        applicationLoop {
            val watchChatRooms = propagateTransaction {

                val chatRooms = mutableMapOf<String, YTChatRoom>()
                // get all youtube videos that have a yt channel with a connected membership
                YoutubeVideos
                    .innerJoin(MembershipConfigurations, { ytChannel }, { streamChannel })
                    .select {
                        YoutubeVideos.liveEvent neq null or
                                (YoutubeVideos.scheduledEvent neq null)
                    }
                    .withDistinct(true)
                    .map { row -> YTChatRoom(YoutubeVideo.wrapRow(row)) }
                    .associateByTo(chatRooms, YTChatRoom::videoId)

                // get all youtube videos that have a yt channel associated with the holochat service
                val holochatYtIds = holochats.chatChannels.keys
                YoutubeVideos
                    .innerJoin(TrackedStreams.StreamChannels)
                    .select {
                        TrackedStreams.StreamChannels.siteChannelID inList holochatYtIds and
                                (YoutubeVideos.liveEvent neq null or (YoutubeVideos.scheduledEvent neq null))
                    }
                    .withDistinct(true)
                    .map { row -> YTChatRoom(YoutubeVideo.wrapRow(row)) }
                    .associateByTo(chatRooms, YTChatRoom::videoId)

                chatRooms
            }

            // end old chat listeners
            activeChats
                .filterKeys { activeId -> !watchChatRooms.contains(activeId) }
                .forEach { (chatId, process) ->
                    LOG.info("Unsubscribing from YT chat: $chatId")
                    process.destroy()
                    activeChats.remove(chatId)
                }

            // register new chat listeners
            watchChatRooms
                .filterKeys { watchId -> !activeChats.contains(watchId) }
                .forEach { (newChatId, chatRoom) ->
                    LOG.info("Subscribing to YT chat: $newChatId")
                    // launch thread for each chat connection/process
                    thread(start = true) {
                        val subprocess = ProcessBuilder("python3", scriptName, newChatId)
                            .directory(scriptDir)
                            .start()

                        println("1: $newChatId")
                        activeChats[newChatId] = subprocess

                        subprocess.inputStream
                            .bufferedReader()
                            .lines()
                            .forEach { chatData ->
                                parseQueue.trySend(YTChatData(chatRoom, chatData))
                            }

                        println("2: $newChatId")

                        // if outputstream ends, this process should be ending (or unresponsive?)
                        subprocess.destroy()
                        if(subprocess.waitFor() != 0) {
                            activeChats.remove(newChatId)
                            println("lost chat $newChatId")
                        }
                        println("3: $newChatId")
                    }
                }
            delay(Duration.ofSeconds(5))
        }
    }

    data class YTChatRoom(val channelId: String, val videoId: String) {
        constructor(dbVideo: YoutubeVideo) : this(dbVideo.ytChannel.siteChannelID, dbVideo.videoId)
    }

    data class YTChatData(val room: YTChatRoom, val json: String)
    data class YTMessageData(val room: YTChatRoom, val chat: YTChatMessage)
}