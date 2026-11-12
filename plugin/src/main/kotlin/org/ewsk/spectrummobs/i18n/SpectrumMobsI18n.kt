package org.ewsk.spectrummobs.i18n

import org.ewsk.spectrummobs.core.ConfigurationDiagnostic
import org.ewsk.spectrummobs.script.ScriptDiagnostic
import org.bukkit.entity.Player
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.function.console
import taboolib.common.platform.function.info
import taboolib.module.configuration.Configuration
import taboolib.module.lang.Language
import taboolib.module.lang.asLangText
import taboolib.module.lang.asLangTextOrNull
import taboolib.module.lang.sendLang
import taboolib.platform.type.BukkitPlayer
import java.io.File

/**
 * SpectrumMobs 的唯一消息出口。
 *
 * 业务模块只产生结构化结果，插件层在这里通过 TabooLib I18n 渲染给玩家或日志。
 */
object SpectrumMobsI18n {

    private const val DEFAULT_LANGUAGE = "zh_CN"

    fun configure(configFile: File) {
        val configured = runCatching {
            Configuration.loadFromFile(configFile).getString("general.language")
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.matches(Regex("[A-Za-z]{2,3}_[A-Za-z]{2,4}")) }
            ?: DEFAULT_LANGUAGE
        Language.default = configured
        Language.reload()
    }

    fun send(sender: ProxyCommandSender, node: String, vararg args: Any) {
        sender.sendLang(node, *args)
    }

    fun text(node: String, vararg args: Any): String = console().asLangText(node, *args)

    fun text(sender: ProxyCommandSender, node: String, vararg args: Any): String = sender.asLangText(node, *args)

    /**
     * 将技能消息当作 I18n 节点解析；找不到节点时保留技能原生文本，兼容已有 `.sms` 文件。
     * 使用 `i18n:key` 可以明确要求按语言节点查找。
     */
    fun skillText(player: Player, raw: String): String {
        val explicitKey = raw.removePrefix("i18n:")
        val translated = BukkitPlayer(player).asLangTextOrNull(explicitKey)
        return translated ?: raw.removePrefix("i18n:")
    }

    fun log(node: String, vararg args: Any) {
        console().sendMessage(text(node, *args))
    }

    fun formatScriptDiagnostic(diagnostic: ScriptDiagnostic): String = text(
        "error-script-compile",
        diagnostic.source,
        diagnostic.location.line,
        diagnostic.location.column,
        diagnostic.message
    )

    fun sendScriptDiagnostic(sender: ProxyCommandSender, diagnostic: ScriptDiagnostic) {
        send(
            sender,
            "error-script-compile",
            diagnostic.source,
            diagnostic.location.line,
            diagnostic.location.column,
            diagnostic.message
        )
    }

    fun sendConfigurationDiagnostic(sender: ProxyCommandSender, diagnostic: ConfigurationDiagnostic) {
        send(
            sender,
            "error-configuration-diagnostic",
            diagnostic.source,
            diagnostic.path.ifBlank { "<root>" },
            diagnostic.message
        )
    }

    fun logScriptDiagnostic(diagnostic: ScriptDiagnostic) {
        info(formatScriptDiagnostic(diagnostic))
    }

    fun formatConfigurationDiagnostic(diagnostic: ConfigurationDiagnostic): String = text(
        "error-configuration-diagnostic",
        diagnostic.source,
        diagnostic.path.ifBlank { "<root>" },
        diagnostic.message
    )

    fun logConfigurationDiagnostic(diagnostic: ConfigurationDiagnostic) {
        info(formatConfigurationDiagnostic(diagnostic))
    }
}
