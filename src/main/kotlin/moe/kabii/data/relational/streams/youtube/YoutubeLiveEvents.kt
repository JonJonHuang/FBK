package moe.kabii.data.relational.streams.youtube

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object YoutubeLiveEvents : LongIdTable() {
    val ytVideo = reference("yt_video", YoutubeVideos, ReferenceOption.CASCADE)
    val lastTitle = text("last_title")
    val lastThumbnail = text("thumbnail_url")

    val peakViewers = integer("peak_viewers")
    val uptimeTicks = integer("uptime_ticks")
    val averageViewers = integer("average_viewers")
}

class YoutubeLiveEvent(id: EntityID<Long>) : LongEntity(id) {
    var ytVideo by YoutubeVideo referencedOn YoutubeLiveEvents.ytVideo
    var lastTitle by YoutubeLiveEvents.lastTitle
    var lastThumbnail by YoutubeLiveEvents.lastThumbnail

    var peakViewers by YoutubeLiveEvents.peakViewers
    var uptimeTicks by YoutubeLiveEvents.uptimeTicks
    var averageViewers by YoutubeLiveEvents.averageViewers

    fun updateViewers(current: Int) {
        if(current > peakViewers) peakViewers = current
        averageViewers += (current - averageViewers) / ++uptimeTicks
    }

    companion object : LongEntityClass<YoutubeLiveEvent>(YoutubeLiveEvents)
}