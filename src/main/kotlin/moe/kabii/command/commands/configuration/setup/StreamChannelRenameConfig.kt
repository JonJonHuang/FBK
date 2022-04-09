package moe.kabii.command.commands.configuration.setup

import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.command.params.DiscordParameters
import moe.kabii.data.mongodb.guilds.ChannelMark
import moe.kabii.data.mongodb.guilds.MongoStreamChannel
import moe.kabii.discord.util.Embeds
import moe.kabii.rusty.Err
import moe.kabii.rusty.Ok
import moe.kabii.trackers.StreamingTarget
import moe.kabii.trackers.TargetArguments
import moe.kabii.trackers.TrackerTarget
import moe.kabii.trackers.videos.StreamWatcher
import moe.kabii.util.extensions.propagateTransaction

object StreamChannelRenameConfig : Command("streamrenamecfg") {
    override val wikiPath = "Livestream-Tracker#configuration"

    init {
        discord {
            if(isPM) return@discord
            when(subCommand.name) {
                "set" -> setOshiMark(this)
                "list" -> listOshiMarks(this)
            }
        }
    }

    private suspend fun setOshiMark(origin: DiscordParameters) = with(origin) {

        // /streamrenamecfg set <TrackedUsername> <character> (site)
        val settings = features().streamSettings
        if(!settings.renameEnabled) {
            ereply(Embeds.error("The channel renaming feature is not enabled in **#${origin.guildChan.name}**.If you wish to enable it, you can do so with **/streamcfg rename true**.")).awaitSingle()
            return
        }
        val args = subArgs(subCommand)
        val target = args.optInt("site")?.run(TrackerTarget::parseSiteArg)
        val streamArg = args.string("stream")
        val siteTarget = when (val findTarget = TargetArguments.parseFor(this, streamArg, target)) {
            is Ok -> findTarget.value
            is Err -> {
                ereply(Embeds.error("Unable to find livestream channel: ${findTarget.value}.")).awaitSingle()
                return@with
            }
        }
        val site = siteTarget.site as StreamingTarget // enforced by limited options for command

        val streamInfo = when (val streamCall = site.getChannel(siteTarget.identifier)) {
            is Ok -> streamCall.value
            is Err -> {
                ereply(Embeds.error("Unable to find the **${site.full}** stream **${siteTarget.identifier}**.")).awaitSingle()
                return@with
            }
        }

        val mark = args.optStr("character")
        val dbChannel = MongoStreamChannel.of(streamInfo)
        if(mark == null) {

            settings.marks.removeIf { existing ->
                existing.channel == dbChannel
            }
            ireply(Embeds.fbk("The live mark for **${streamInfo.displayName}** has been removed.")).awaitSingle()
        } else {
            val newMark = ChannelMark(dbChannel, mark)
            settings.marks.removeIf { existing ->
                existing.channel == dbChannel
            }
            settings.marks.add(newMark)

            ireply(Embeds.fbk("The \"live\" mark for **${streamInfo.displayName}** has been set to **$mark**.\nThis will be displayed in the Discord channel name when this stream is live.\n" +
                    "It is recommended to use an emoji to represent a live stream, but you are able to use any combination of characters you wish.\n" +
                    "Note that it is **impossible** to use uploaded/custom emojis in a channel name.")).awaitSingle()
        }
        config.save()
        propagateTransaction {
            StreamWatcher.checkAndRenameChannel(chan, null)
        }
    }

    private suspend fun listOshiMarks(origin: DiscordParameters) = with(origin) {
        val settings = features().streamSettings
        if(!settings.renameEnabled) {
            ereply(Embeds.error("The channel renaming feature is not enabled in **#${guildChan.name}**. If you wish to enable it, you can do so with **/streamcfg rename true**.")).awaitSingle()
            return@with
        }

        if(settings.marks.isEmpty()) {
            ereply(Embeds.fbk("There are no configured channel marks in **#${guildChan.name}**."))
        } else {
            val allMarks = settings.marks.joinToString("\n") { mark ->
                "${mark.channel.site.targetType.full}/${mark.channel.identifier}: ${mark.mark}"
            }
            ireply(
                Embeds.fbk(allMarks).withTitle("Configured stream markers in **#${guildChan.name}**")
            )
        }.awaitSingle()
    }
}