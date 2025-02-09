package moe.kabii.command.commands.translator

import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.discord.translation.Translator

object LanguageList : Command("languages", "languagelist", "langs") {
    override val wikiPath = "Translator"

    init {
        discord {
            val service = Translator.defaultService
            embed("The list of supported languages for translation can be found [here.](${service.languageHelp})").awaitSingle()
        }
    }
}