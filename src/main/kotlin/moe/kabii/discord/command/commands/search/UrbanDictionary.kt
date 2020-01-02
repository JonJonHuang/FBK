package moe.kabii.discord.command.commands.search

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import moe.kabii.MOSHI
import moe.kabii.OkHTTP
import moe.kabii.discord.command.Command
import moe.kabii.discord.command.kizunaColor
import moe.kabii.discord.conversation.Page
import moe.kabii.net.NettyFileServer
import moe.kabii.rusty.Ok
import moe.kabii.structure.fromJsonSafe
import okhttp3.Request

object Urban : Command("urbandictionary", "urban", "ud") {
    val udAdapter: JsonAdapter<Response> = MOSHI.adapter(Response::class.java)

    init {
        discord {
            val lookup = if (args.isEmpty()) author.username else noCmd
            val message = embed("Searching for **$lookup**...").block()
            val request = Request.Builder().get().url("https://api.urbandictionary.com/v0/define?term=$lookup")
            val response = OkHTTP.make(request) { response ->
                val body = response.body!!.string()
                udAdapter.fromJsonSafe(body)
            }
            if(response !is Ok) {
                error("Unable to reach UrbanDictionary.").block()
                return@discord
            }
            val define = response.value.orNull()
            if (define == null || define.list.isEmpty()) {
                embed {
                    setAuthor("UrbanDictionary", "https://urbandictionary.com", null)
                    setDescription("No definitions found for **$lookup**.")
                }.block()
                return@discord
            }
            var page: Page? = Page(define.list.size, 0)
            var first = true
            while (page != null) {
                message.edit {
                    it.setContent(null)
                    it.setEmbed { spec ->
                        val page = page!!
                        val def = define.list[page.current]
                        val index = "${page.current + 1} / ${page.pageCount}"
                        spec.apply {
                            kizunaColor(this)
                            setAuthor("UrbanDictionary", "https://urbandictionary.com", NettyFileServer.urbanDictionary)
                            setDescription("Lookup: [${def.word}](${def.permalink})")
                            addField("Definition $index:", def.definition, false)
                            addField("Example:", def.example, false)
                            addField("Upvotes", def.up.toString(), true)
                            addField("Downvotes", def.down.toString(), true)
                        }
                    }
                }.block()
                page = getPage(page, message, add = first)
                first = false
            }
            message.removeAllReactions().subscribe()
        }
    }

    @JsonClass(generateAdapter = true)
    data class Response(val list: List<Definition>)
    @JsonClass(generateAdapter = true)
    data class Definition(
        val definition: String,
        val permalink: String,
        @Json(name = "thumbs_up") val up: Int,
        @Json(name = "thumbs_down") val down: Int,
        val word: String,
        val example: String
    )
}