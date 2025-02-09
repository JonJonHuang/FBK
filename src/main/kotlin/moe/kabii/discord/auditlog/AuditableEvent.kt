package moe.kabii.discord.auditlog

import discord4j.common.util.Snowflake
import discord4j.core.`object`.audit.AuditLogEntry

abstract class AuditableEvent(val logChannel: Snowflake, val logMessage: Snowflake, val guild: Long) {
    abstract fun match(auditLogEntry: AuditLogEntry): Boolean
    abstract fun appendedContent(auditLogEntry: AuditLogEntry): String?
}