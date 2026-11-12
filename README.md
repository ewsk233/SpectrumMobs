# SpectrumMobs

SpectrumMobs 是一个基于 Kotlin 与 TabooLib 的 Minecraft 怪物与技能引擎。项目目标是提供清晰的怪物配置、可静态检查的 Spectrum Script，以及可暂停、可测试、受预算限制的技能运行时。

当前仓库处于重写阶段。基础模块、配置模型、YAML 校验、原子重载以及 Spectrum Script 的词法、语法、基础类型检查、Typed IR 和抽象可暂停运行时已经完成；插件层已经接入怪物生成、PDC 身份标记、`damaged` 技能触发、Boss 阶段、声明式掉落、外部集成扩展点和原生技能反馈动作。

## 项目模块

- `core`：纯 Kotlin 的领域模型和版本定义，不依赖 Bukkit、Paper 或 TabooLib。
- `script`：Spectrum Script 的词法、语法、类型检查、Typed IR 和可暂停运行时。
- `plugin`：TabooLib 插件入口、配置加载、事件监听、实体适配和服务器命令。

模块名称保持简短，避免在每个模块名前重复 `spectrum-` 前缀。

## 文档

- [架构设计](docs/ARCHITECTURE.md)
- [配置规范](docs/CONFIG_SPEC.md)
- [脚本规范](docs/SCRIPT_SPEC.md)
- [Targeter、Condition 与 Mechanics API](docs/SCRIPT_API.md)
- [运行时规范](docs/RUNTIME_SPEC.md)
- [施法管理与打断](docs/CASTING_SPEC.md)
- [参数化技能与规则模块](docs/PARAMETER_RULE_SPEC.md)
- [威胁表、行为策略与规则计算器](docs/THREAT_BEHAVIOR_SPEC.md)
- [遗文聚合体·缄言](docs/GLYPH_BOSS_SPEC.md)
- [实现计划](docs/IMPLEMENTATION_PLAN.md)
- [设计决策](docs/DECISIONS.md)
- [协作约定](AGENTS.md)

## 构建

Windows：

```text
gradlew.bat clean build
```

Linux 或 macOS：

```text
./gradlew clean build
```

构建产物位于 `plugin/build/libs/`。

## 调试命令

```text
/spectrummobs reload
/spectrummobs skill list
/spectrummobs skill cast spectrum:flame_nova
/spectrummobs skill active
/spectrummobs skill active <page>
/spectrummobs skill cancel <execution-uuid>
/spectrummobs mob list
/spectrummobs mob spawn spectrum:ember_guardian
/spectrummobs mob kill spectrum:magma_lord <uuid>
/spectrummobs mob killall
```

命令需要 `spectrummobs.admin` 权限。`skill cast` 和 `mob spawn` 只能由玩家执行，并以玩家当前位置作为上下文。

技能可以使用 `command.run("registered_id", target)` 执行配置白名单命令。`command.raw("...")` 默认关闭，开启 `commands.allow-raw` 后插件启动会警告服主。

技能还支持实体生命/传送/速度/药水/移除、BossBar、记分板、受控方块投射物、附近玩家和最近实体查询，以及 `skill`、`caster`、`target`、`world`、`global` 变量作用域。

技能 API 已统一为 `targeter.*`、`condition.*`、`mechanic.*`，使用同一份签名和运行时绑定快照。内置技能已迁移；旧顶层动作与条件成员不再支持。已有服务器的 `.sms` 文件需要按 [脚本 API](docs/SCRIPT_API.md) 更新。

施法组和 `parallel/reject/replace` 策略在 `.sms` 中声明。阶段切换、手动打断和父子技能取消会按策略清理实例所属的投射物与临时血条。`skills.cast(id, caster, target, linked?)` 显式传入目标，返回执行 UUID；子技能共用调用链深度限制。完整规则见 [施法管理与打断](docs/CASTING_SPEC.md)。

技能支持带类型和默认值的参数，事件、周期触发、子技能和 `/spectrummobs skill cast` 都可以传入参数。`.sms` 还支持显式导入的只读 `fn` 规则模块；规则不能等待、执行 Mechanics、写状态或启动子技能。完整规则见 [参数化技能与规则模块](docs/PARAMETER_RULE_SPEC.md)。

怪物文件可以声明 `boss.phases`、`drops`、`model` 和 `intervals`。受管怪物攻击实体时可以通过 `events.attack` 触发技能。模型提供方通过 `MobModelIntegration` 接口注册，SpectrumMobs 不直接依赖具体模型插件。技能文本使用 `&` 或 `§` 颜色码，不使用 MiniMessage。插件消息使用 TabooLib I18n，语言文件位于 `plugin/src/main/resources/lang/`；配置和 Script 编译错误统一以英文正文输出，并由插件层通过 I18n 格式化来源和位置。

怪物还可以使用威胁表、周期 `behavior`、`target-rules`、`damage-rules` 和掉落规则函数，让 `fn` 同时参与目标选择、伤害倍率和掉落概率计算。详细字段与运行时语义见 [威胁表、行为策略与规则计算器](docs/THREAT_BEHAVIOR_SPEC.md)。

项目内置不依赖模型插件的原版熔核君王 Boss 套件，详见 [docs/BOSS_PACK.md](docs/BOSS_PACK.md)。

## TabooLib 说明

TabooLib 负责平台抽象、模块化依赖和跨版本适配。涉及实体尺寸、碰撞箱或其他 NMS 行为时，仍然需要通过 TabooLib 的 NMS 代理或版本实现提供能力。项目不会把某一个服务器版本当作最终支持范围。
