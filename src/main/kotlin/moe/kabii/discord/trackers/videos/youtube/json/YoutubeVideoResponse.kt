package moe.kabii.discord.trackers.videos.youtube.json

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.time.Duration
import java.time.Instant

@JsonClass(generateAdapter = true)
data class YoutubeVideoResponse(
    val items: List<YoutubeVideo>
)

@JsonClass(generateAdapter = true)
data class YoutubeVideo(
    val id: String,
    val snippet: YoutubeVideoSnippet,
    val contentDetails: YoutubeVideoContentDetails,
    val liveStreamingDetails: YoutubeVideoLiveDetails?
) {
    @Transient val premiere = (snippet.live || snippet.upcoming) && contentDetails.duration.toMillis() > 0
}

@JsonClass(generateAdapter = true)
data class YoutubeVideoSnippet(
    @Json(name="publishedAt") val _publishedAt: String,
    val channelId: String,
    val title: String,
    val description: String,
    val thumbnails: YoutubeThumbnails,
    val channelTitle: String,
    @Json(name="liveBroadcastContent") val _liveBroadcastContent: String
) {
    @Transient val publishedAt = Instant.parse(_publishedAt)

    // "live", "none" , or "upcoming"
    @Transient val live: Boolean = _liveBroadcastContent == "live"
    @Transient val upcoming: Boolean = _liveBroadcastContent == "upcoming"
}

@JsonClass(generateAdapter = true)
data class YoutubeVideoContentDetails(
    @Json(name="duration") val _rawDuration: String
) {
    @Transient val duration: Duration = Duration.parse(_rawDuration)
}

@JsonClass(generateAdapter = true)
data class YoutubeVideoLiveDetails(
    @Json(name="actualStartTime") val _startTime: String?,
    @Json(name="concurrentViewers") val _concurrentViewers: String?,
    @Json(name="actualEndTime") val _endTime: String?,
    @Json(name="scheduledStartTime") val _scheduledStartTime: String?
) {
    @Transient val startTime = _startTime?.run(Instant::parse)
    @Transient val concurrentViewers = _concurrentViewers?.toIntOrNull()
    @Transient val endTime = _endTime?.run(Instant::parse)
    @Transient val scheduledStartTime = _scheduledStartTime?.run(Instant::parse)
}