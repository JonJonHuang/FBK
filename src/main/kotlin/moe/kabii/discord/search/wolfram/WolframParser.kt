package moe.kabii.discord.search.wolfram

import moe.kabii.LOG
import moe.kabii.OkHTTP
import moe.kabii.data.flat.Keys
import moe.kabii.util.extensions.stackTraceString
import okhttp3.Request
import java.io.IOException

object WolframParser {

    private val appId = Keys.config[Keys.Wolfram.appId]

    data class WolframResponse(val success: Boolean, val output: String?)

    @Throws(IOException::class)
    fun query(raw: String): WolframResponse {
        val query = raw.replace("+", "plus")

        val request = Request.Builder()
            .get()
            .header("User-Agent", "srkmfbk/1.0")
            .url("https://api.wolframalpha.com/v1/result?appid=$appId&i=$query")
            .build()

        return try {
            OkHTTP.newCall(request).execute().use { rs ->
                WolframResponse(rs.isSuccessful, rs.body!!.string())
            }
        } catch(e: Exception) {
            LOG.warn("Error calling WolframAlpha conversation API: ${e.message}")
            LOG.trace(e.stackTraceString)
            WolframResponse(false, null)
        }
    }
}