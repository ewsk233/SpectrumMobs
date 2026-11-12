# 消息与错误规范

SpectrumMobs 的插件层统一通过 TabooLib I18n 输出消息。语言文件位于 `plugin/src/main/resources/lang/`，当前提供 `zh_CN.yml` 和 `en_US.yml`。语言文件会在插件启动时根据 `config.yml` 的 `general.language` 选择默认语言，并释放到服务器的 `plugins/SpectrumMobs/lang/` 目录供服主覆盖。

命令反馈、启动和关闭日志、重载结果、命令策略诊断、怪物运行时错误以及外部集成错误都必须使用语言节点，代码中不再直接拼接玩家可见消息。脚本中的 `mechanic.message.send`、`mechanic.title.show` 和 `mechanic.actionbar.show` 也会先按 I18n 节点解析；写成 `i18n:node-name` 可以明确使用语言节点，找不到节点时才保留技能原生的 `&`/`§` 文本以兼容已有技能。

错误正文统一使用英文。核心配置解析器和 Script 编译器只产生英文诊断正文，插件层使用 `error-configuration-diagnostic` 和 `error-script-compile` 等 TabooLib I18n 节点添加来源、行列号和颜色格式。这样错误内容不会随着玩家语言变化，同时输出格式仍然可以由语言文件统一调整。

新增插件消息时必须同时更新两个语言文件，并通过 `SpectrumMobsI18n.send`、`SpectrumMobsI18n.text` 或 `SpectrumMobsI18n.log` 访问；禁止在命令、监听器和服务中直接调用 `sendMessage` 或拼接日志文本。

`debug.trace-skills` 打开的触发器、施法结果和运行时事件日志也必须使用 I18n 节点，关闭时不产生调试输出。
