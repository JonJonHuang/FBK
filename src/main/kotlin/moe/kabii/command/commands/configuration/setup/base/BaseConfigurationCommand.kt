package moe.kabii.command.commands.configuration.setup

import discord4j.core.`object`.entity.Message
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.params.DiscordParameters
import moe.kabii.discord.util.fbkColor
import moe.kabii.rusty.Ok
import moe.kabii.rusty.Result
import moe.kabii.util.DurationFormatter
import moe.kabii.util.DurationParser
import moe.kabii.util.constants.EmojiCharacters
import moe.kabii.util.constants.MagicNumbers
import moe.kabii.util.extensions.EmbedBlock
import org.apache.commons.lang3.StringUtils
import java.time.Duration
import kotlin.reflect.KMutableProperty1

sealed class ConfigurationElement<T>(val fullName: String, val aliases: List<String>)
class StringElement<T>(
    fullName: String,
    aliases: List<String>,
    val prop: KMutableProperty1<T, String>,
    val prompt: String,
    val default: String
) : ConfigurationElement<T>(fullName, aliases)

class BooleanElement<T>(
    fullName: String,
    aliases: List<String>,
    val prop: KMutableProperty1<T, Boolean>
) : ConfigurationElement<T>(fullName, aliases)

class DoubleElement<T>(
    fullName: String,
    aliases: List<String>,
    val prop: KMutableProperty1<T, Double>,
    val range: ClosedRange<Double>,
    val prompt: String
) : ConfigurationElement<T>(fullName, aliases)

class LongElement<T>(
    fullName: String,
    aliases: List<String>,
    val prop: KMutableProperty1<T, Long>,
    val range: LongRange,
    val prompt: String
) : ConfigurationElement<T>(fullName, aliases)

class DurationElement<T>(
    fullName: String,
    aliases: List<String>,
    val prop: KMutableProperty1<T, String?>,
    val prompt: String,
    val default: Duration?
) : ConfigurationElement<T>(fullName, aliases)

class CustomElement<T, VT>(
    fullName: String,
    aliases: List<String>,
    val prop: KMutableProperty1<T, Any?>,
    val prompt: String,
    val default: VT?,
    val parser: suspend (DiscordParameters, Message, String) -> Result<VT?, Unit>, // given input, produce value or invalid
    val value: (T) -> String // given value, produce string for embed output
) : ConfigurationElement<T>(fullName, aliases)

class ViewElement<T, ANY : Any?>(
    fullName: String,
    aliases: List<String>,
    val prop: KMutableProperty1<T, ANY>,
    val redirection: String,
) : ConfigurationElement<T>(fullName, aliases)

open class ConfigurationModule<T>(val name: String, vararg val elements: ConfigurationElement<T>)

class Configurator<T>(private val name: String, private val module: ConfigurationModule<T>, private val instance: T) {
    companion object {
        const val embedTimeout = 120_000L
    }

    fun getValue(element: ConfigurationElement<T>) = when(element) {
        is StringElement -> element.prop.get(instance)
        is BooleanElement -> if(element.prop.get(instance)) "enabled" else "disabled"
        is DoubleElement -> element.prop.get(instance).toString()
        is LongElement -> element.prop.get(instance).toString()
        is DurationElement -> {
            val field = element.prop.get(instance)
            val duration = field?.run(Duration::parse)
            if(duration != null) DurationFormatter(duration).inputTime
            else "disabled"
        }
        is CustomElement<T, *> -> element.value(instance)
        is ViewElement<T, *> -> element.prop.get(instance).toString()
    }
    private fun getName(element: ConfigurationElement<*>) = "${element.fullName} **(${element.aliases.first()})**"

    private val reset = Regex("reset", RegexOption.IGNORE_CASE)
    suspend fun run(origin: DiscordParameters): Boolean { // returns if a property was modified and the config should be saved
        fun updatedEmbed(element: ConfigurationElement<T>, new: Any) = origin.embed {
            setTitle("Configuration Updated")
            val property = element.aliases.first()
            val newState = when(element) {
                is BooleanElement -> if(new as Boolean) "**enabled**" else "**disabled**"
                else -> "set to **${new.toString().ifBlank { "empty" }}**"
            }
            setDescription("The option **$property** has been $newState.")
        }

        // <command> (no args) -> full menu embed

        if(origin.args.isEmpty()) {
            val configEmbed: EmbedBlock = {
                fbkColor(this)
                setAuthor(name, null, null)
                // not filtering or optimizing to preserve the natural indexes here - could use manually assigned indexes otherwise
                if(module.elements.any { element -> element is BooleanElement }) {
                    setTitle("Select the feature to be toggled/edited using its ID or bolded name.")
                    // feature toggles - these can be made into FeatureElements if we have other toggles later on
                    val enabled = module.elements.mapIndexedNotNull { id, element ->
                        if(element is BooleanElement && element.prop.get(instance)) "${id+1}. ${getName(element)}" else null
                    }.joinToString("\n").ifEmpty { "No ${module.name} features are enabled." }
                    val available = module.elements.mapIndexedNotNull { id, element ->
                        if(element is BooleanElement && !element.prop.get(instance)) "${id+1}. ${getName(element)}" else null
                    }.joinToString("\n").ifEmpty { "All ${module.name} features are enabled." }
                    addField("Enabled Features", enabled, true)
                    addField("Available (Disabled) Features", available, true)
                }
                module.elements.mapIndexedNotNull { id, element ->
                    if(element !is BooleanElement) "${id+1}. ${getName(element)}:\n${EmojiCharacters.spacer}**=** ${getValue(element)}" else null
                }.run {
                    if(isNotEmpty())
                        addField(
                            "Custom Settings:",
                            StringUtils.abbreviate(joinToString("\n"), MagicNumbers.Embed.FIELD.VALUE),
                            false
                        )
                }
                setFooter("\"exit\" to save and exit immediately.", null)
            }

            val menu = origin.embedBlock(configEmbed).awaitSingle()
            while(true) {
                val inputStr = origin.getString(timeout = embedTimeout) ?: break

                val inputNum = inputStr.toIntOrNull()
                val element = if(inputNum != null) {
                    if(inputNum !in 1..module.elements.size) break
                    module.elements[inputNum-1]
                } else {
                    module.elements.find { element -> element.aliases.any { alias -> alias.equals(inputStr, ignoreCase = true) } } ?: continue
                }

                when(element) {
                    is BooleanElement -> element.prop.set(instance, !element.prop.get(instance)) // toggle property
                    is StringElement -> {
                        // prompt user for new value
                        val prompt = origin.embed(element.prompt).awaitSingle()
                        val response = origin.getString(timeout = null)
                        if(response != null) {
                            if(response.matches(reset)) {
                                element.prop.set(instance, element.default)
                            } else {
                                element.prop.set(instance, response)
                            }
                        }
                        prompt.delete().subscribe()
                    }
                    is DoubleElement -> {
                        val prompt = origin.embed(element.prompt).awaitSingle()
                        val response = origin.getDouble(element.range, timeout = embedTimeout)
                        if(response != null) element.prop.set(instance, response)
                        prompt.delete().subscribe()
                    }
                    is LongElement -> {
                        val prompt = origin.embed(element.prompt).awaitSingle()
                        val response = origin.getLong(element.range, timeout = embedTimeout)
                        if(response != null) element.prop.set(instance, response)
                        prompt.delete().subscribe()
                    }
                    is DurationElement -> {
                        val prompt = origin.embed(element.prompt).awaitSingle()
                        val response = origin.getDuration(timeout = embedTimeout)
                        if(response != null) element.prop.set(instance, response.toString())
                        prompt.delete().subscribe()
                    }
                    is CustomElement<T, *> -> {
                        val prompt = origin.embed(element.prompt).awaitSingle()
                        val response = origin.getMessage(timeout = embedTimeout)
                        if(response != null) {
                            val parsed = element.parser(origin, response, response.content)
                            if(parsed is Ok) element.prop.set(instance, parsed.value)
                        }
                        prompt.delete().subscribe()
                    }
                    is ViewElement<*, *> -> {
                        origin.error(element.redirection).awaitSingle()
                        continue
                    }
                }
                menu.edit { message ->
                    message.setEmbed(configEmbed)
                }.awaitSingle()
            }
            menu.delete().subscribe()
            return true
        }

        val targetElement = origin.args[0].lowercase()

        // <command> list/all -> list current config

        if(targetElement == "list") {
            origin.embed {
                setTitle("Current ${module.name} configuration:")
                module.elements.forEach { element ->
                    val raw = getValue(element)
                    val value = if(raw.isBlank()) "<NONE>" else StringUtils.abbreviate(raw, MagicNumbers.Embed.FIELD.VALUE)
                    addField(getName(element), value, true)
                }
            }.awaitSingle()
            return false
        }

        val element = module.elements.find { prop -> prop.aliases.any { alias -> alias.lowercase() == targetElement.lowercase() } }
        if(element == null) {
            origin.error("Invalid setting **$targetElement**. The available settings can be found with **${origin.alias} list**. You can also run **${origin.alias}** without any arguments to change settings using an interactive embed.").awaitSingle()
            return false
        }
        val tag = element.aliases.first()
        // <command> prop -> manual get
        // dont run this if this is a custom element w/ attachment
        if(origin.args.size == 1 && !(element is CustomElement<T, *> && origin.event.message.attachments.isNotEmpty())) {
            origin.embed {
                setTitle("From ${module.name} configuration:")
                addField(getName(element), getValue(element), false)
            }.awaitSingle()
            return false
        }

        // <command> prop <toggle/reset> -> specific actions
        if(origin.args.size == 2) {
            val arg = origin.args[1].lowercase()
            when {
                arg.equals("toggle") -> {
                    if(element !is BooleanElement) {
                        origin.error("The setting **$tag** is not a toggle.").awaitSingle()
                        return false
                    }
                    val new = !element.prop.get(instance)
                    element.prop.set(instance, new)
                    updatedEmbed(element, new).subscribe()
                    return true
                }
                arg.matches(reset) -> {
                    when(element) {
                        is StringElement -> element.prop.set(instance, element.default)
                        is DurationElement -> element.prop.set(instance, element.default?.toString())
                        is CustomElement<T, *> -> element.prop.set(instance, element.default)
                        else -> {
                            origin.error("The setting **$tag** is not a resettable custom value.").awaitSingle()
                            return false
                        }
                    }
                    val value = getValue(element)
                    updatedEmbed(element, value).subscribe()
                    return true
                }
            }
        }

        // <command> prop <new value> -> manual set, check input types
        when(element) {
            is BooleanElement -> {
                val input = origin.args[1].lowercase()
                val bool = when {
                    input.startsWith("y")
                            || input.startsWith("en")
                            || input.startsWith("t")
                            || input == "1"
                            || input == "on"
                    -> true
                    input.startsWith("n")
                            || input.startsWith("dis")
                            || input.startsWith("f")
                            || input == "0"
                            || input == "off"
                    -> false
                    else -> null
                }
                if(bool == null) {
                    origin.error("The setting **$tag** is a toggle, I can not set it to **$input**. Example: **${origin.alias} $tag enable**. You can also run **${origin.alias} toggle $tag**").awaitSingle()
                    return false
                }
                element.prop.set(instance, bool)
                updatedEmbed(element, bool).awaitSingle()
                return true
            }
            is StringElement -> {
                val input = origin.args.drop(1).joinToString(" ")
                element.prop.set(instance, input)
                updatedEmbed(element, input).awaitSingle()
                return true
            }
            is DoubleElement -> {
                val input = origin.args[1].toDoubleOrNull()
                if(input == null) {
                    origin.error("The setting **$tag** is a decimal value, I can not set it to **$input**. Example: **${origin.alias} $tag .5**").awaitSingle()
                    return false
                }
                element.prop.set(instance, input)
                updatedEmbed(element, input).awaitSingle()
                return true
            }
            is LongElement -> {
                val input = origin.args[1].toLongOrNull()
                if(input == null) {
                    origin.error("The setting **$tag** is an integer value, I can not set it to **$input**. Example: **${origin.alias} $tag 4**").awaitSingle()
                    return false
                }
                element.prop.set(instance, input)
                updatedEmbed(element, input).awaitSingle()
                return true
            }
            is DurationElement -> {
                val input = origin.args.drop(1).joinToString(" ").run(DurationParser::tryParse)
                if(input == null) {
                    origin.error("The setting **$tag** is a duration field, I can not set it to **$input**. Example **${origin.alias} $tag 6h").awaitSingle()
                    return false
                }
                element.prop.set(instance, input.toString())
                val output = DurationFormatter(input).inputTime
                updatedEmbed(element, output).awaitSingle()
                return true
            }
            is CustomElement<T, *> -> {
                val input = element.parser(origin, origin.event.message, origin.args.drop(1).joinToString(" "))
                return if(input is Ok) {
                    element.prop.set(instance, input.value)
                    val output = element.value(instance)
                    updatedEmbed(element, output).awaitSingle()
                    true
                } else false
            }
            is ViewElement<*, *> -> {
                origin.error(element.redirection).awaitSingle()
                return false
            }
        }
    }
}