package moe.kabii.data

import java.io.File

object GQLQueries {
    val aniListUser: String = File("files/anilist/user.gql").readText()
    val aniListMediaList: String = File("files/anilist/medialist.gql").readText()
}