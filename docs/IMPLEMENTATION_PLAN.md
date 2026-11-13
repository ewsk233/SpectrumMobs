# 实现计划

## 阶段 0：基础工程（已完成）

- 建立 `core`、`script`、`plugin` 三个模块。
- 统一中文文档、构建命令和测试入口。
- 确保 `clean build` 通过。

## 阶段 1：配置模型（已完成基础版本）

- 实现资源 ID、怪物定义和严格 YAML 诊断。
- 完成配置目录扫描、默认资源复制和原子重载。
- 为重复 ID、未知实体类型和错误字段编写测试。

当前已完成资源 ID、怪物定义、属性、装备、事件绑定、诊断结果、重复 ID 检查和原子快照替换。实体类型是否存在由后续平台适配层结合服务器环境校验。

## 阶段 2：脚本编译器（已完成基础编译链）

- 实现词法分析、语法分析和错误定位。
- 实现类型检查和 Typed IR。
- 覆盖变量、条件、循环、函数调用和 `wait`。

当前已完成 Token、词法诊断、递归下降 Parser、带源码位置的 AST、基础类型检查和 Typed IR。更完整的泛型、函数声明和扩展动作签名仍待实现。

## 阶段 3：模拟运行时（已完成基础版本）

- 实现可暂停的执行实例。
- 实现 Tick 时钟、取消令牌和执行预算。
- 使用假的平台动作测试执行顺序，不依赖 Minecraft 服务器。

当前已完成 `SkillScheduler`、可暂停的 `SkillExecution`、运行时值、动作注册表、冷却、条件检查、等待恢复、取消、活动实例上限、步数预算、循环预算和调用深度预算。

## 阶段 4：TabooLib 接入（已完成）

- 接入怪物生成、实体标记和事件触发。
- 将动作注册到 Bukkit/Paper 世界操作。
- 增加命令、日志和运行时追踪。

当前已完成 `.sms` 技能扫描、编译快照、TabooLib 主线程 tick 驱动、粒子、范围查询、伤害、点燃动作、怪物生成、PDC 身份标记、触发上下文、`damaged` 路由、施法者/世界生命周期取消，以及 `/spectrummobs reload`、`/spectrummobs skill list`、`/spectrummobs skill cast <namespace:id>`、`/spectrummobs mob list` 和 `/spectrummobs mob spawn <namespace:id>` 命令。

## 阶段 5：高级能力（已完成基础版本）

- Boss 阶段和行为决策。
- 装备、掉落、模型和外部插件集成。
- 根据稳定的脚本子集开发 Skill Graph。

当前已完成 Boss 阶段阈值、阶段属性、阶段进入/离开技能、声明式掉落、模型 PDC 绑定和 `MobIntegration`/`MobModelIntegration` 扩展点。Skill Graph、复杂掉落池和具体外部插件适配器仍待后续阶段。

## 阶段 6：技能反馈与受控表达式（已完成）

- 增加 `mechanic.message.send`、`mechanic.title.show`、`mechanic.actionbar.show` 和 `mechanic.sound.play` 原生动作。
- 增加字符串插值、列表属性、事件攻击者/原因/手部上下文。
- 增加受循环预算约束的 `repeat`、随机动作和施法者状态服务。
- 使用测试覆盖等待、查询快照、插值、状态和循环预算。

## 阶段 7：怪物触发与周期调度（已完成基础版本）

- 增加受管怪物的 `attack` 触发器。
- 增加按实体 UUID 独立计时的 `intervals` 配置。
- 增加脚本内 `mechanic.skills.cast`，复用现有冷却、实例上限和生命周期取消。
- 在实体死亡、移除和世界卸载时清理间隔计时器。

## 阶段 8：命令动作与安全边界（已完成基础版本）

- 增加配置白名单命令模板和 `command.run`。
- 增加默认关闭的 `command.raw`，开启时输出服主警告。
- 对命令长度、执行者类型、模板 ID 和加载诊断进行限制。

## 阶段 9：通用 Mechanics、Targeter 与变量作用域（已完成基础版本）

- 增加实体生命、传送、速度、移除、药水效果动作。
- 增加 BossBar、记分板、最近实体和附近玩家动作。
- 增加实体类型、药水效果条件和 `skill/caster/target/world/global` 变量作用域；条件正式 API 已在阶段 12 统一。

## 阶段 10：触发器一致性与部分重载（已完成）

- 统一 `attack`、`damaged`、`interval`、`phase_enter`、`phase_exit` 和手动施法的触发上下文。
- 让单个错误的 `.sms` 或 YAML 文件不阻塞其他合法文件进入新快照。
- 把 interval 计时器拆成可测试的无 Bukkit 调度器，保证每个实体独立计时且每个 tick 最多触发一次。
- 增加 `debug.trace-skills` 的 I18n 调试日志，以及等待、冷却、生命周期和重载回归测试。

详细语义见 `docs/TRIGGER_SPEC.md`。

## 阶段 11：受控方块投射物（已完成基础版本）

- 增加 `mechanic.projectile.launch` 脚本动作。
- 增加 `mechanic.projectile.forward`，支持按施法者视线方向发射。
- 增加可选 `placeOnHit`，控制命中方块后是否放置真实方块，默认关闭。
- 增加 `mechanic.mob.summon`，支持 Boss 生成受管小兵并限制单次生成数量。
- 提供不依赖模型插件的 `spectrum:magma_lord` 与 `spectrum:magma_minion` 原版 Boss 套件。
- 将持久 BossBar 改为怪物 YAML 声明，并由插件实时同步血量和阶段标题。
- 增加 `/spectrummobs mob kill <name> <uuid>` 与 `/spectrummobs mob killall` 管理命令。
- 使用 PDC 标记投射物，并按 Tick 执行移动、方块射线、实体命中、伤害和生命周期清理。
- 支持施法者排除、世界/实体生命周期清理和 `engine.max-active-projectiles` 上限。

## 阶段 12：统一技能注册 API（已完成）

- 全部目标选择、实体条件和动作迁入 `targeter.*`、`condition.*`、`mechanic.*`，删除旧入口，不提供兼容别名。
- 将类型签名集中到 `ScriptApiRegistry`，通过 `ScriptApiBindings` 校验并发布完整运行绑定。
- 开放追加式扩展注册，同时校验参数、返回类型、命名空间与查询预算。
- 增加自身、目标、攻击者、实体范围和随机选择；统一球形查询、距离排序、UUID 去重与数量上限。
- 增加条件组合、实体能力、生命阈值、距离和事件原因判断。
- 明确查询快照与当前实体状态的差异，保留等待与冷却语义，修复布尔短路和命名参数绑定。
- 迁移全部内置技能，增加编译器、运行时、平台绑定和文档示例测试。

完整契约见 `docs/SCRIPT_API.md`。

## 阶段 13：施法管理与打断（已完成）

- 增加 `.sms` 施法组、并行/拒绝/替换策略、阶段取消与取消清理策略。
- 冷却改用完整技能资源 ID；子调用记录父、根、深度和联动关系，解除联动也不能重置调用深度。
- 子调用显式传递目标、返回执行 UUID，不继承普通局部变量。
- 统一按实例管理投射物与临时 BossBar 所有权、自然释放、失败和取消清理。
- 在同 tick 取消后停止当前实例和已取消的后续调度条目。
- 增加活动实例分页查询、取消命令与 I18n 反馈，迁移 Boss 示例。
- 增加核心调度、资源清理、平台适配和文档示例测试。

完整契约见 `docs/CASTING_SPEC.md`。

## 阶段 14：参数化技能与规则模块（已完成）

- 增加技能参数、类型、默认值、运行时覆盖和参数实例隔离。
- 让子技能、怪物事件、周期触发和 `/spectrummobs skill cast` 共享同一套参数绑定规则。
- 增加事件与 interval 的 YAML `skill/args` 对象格式，保留纯资源 ID 写法。
- 增加显式导入和只读 `fn` 规则函数，禁止等待、Mechanics、命令、状态写入和子技能调用。
- 将规则签名、实现和技能编译结果放入同一快照，依赖错误按文件部分加载。
- 增加参数默认值、YAML/命令绑定、规则导入、只读约束、返回值和快照测试。

完整契约见 `docs/PARAMETER_RULE_SPEC.md`。

## 阶段 15：威胁表与规则计算器（已完成）

- 在 `core` 实现不依赖 Bukkit 的 `ThreatTable`，在 `plugin` 按实体 UUID 管理、衰减和生命周期清理。
- 增加 `targeter.threat.top/list`、`condition.threat.above/isTop` 和 `mechanic.threat.add/set/remove/clear`。
- 增加 YAML `behavior` 高层行为策略，并复用每个实体独立的 interval 调度器。
- 增加 `target-rules` 目标选择器、`damage-rules` 伤害倍率规则和掉落项 `rule` 概率倍率规则。
- 规则求值复用 Typed IR、只读约束、完整快照、步数和调用深度预算；异常按 I18n 记录并隔离到当前事件。
- 增加威胁表、YAML 规则字段、内置 Boss 行为和规则模块测试。

完整契约见 `docs/THREAT_BEHAVIOR_SPEC.md`。

## 阶段 16：通用世界 Boss 能力与遗文聚合体（已完成）

- 增加通用 `hover` 移动控制器和怪物 `movement` 配置。
- 增加 Display 视觉提供方，支持旋转文字、核心、粒子和实体生命周期清理。
- 增加粒子线、射线伤害、追踪方块投射物、拉拽/击退和实体标签 Targeter。
- 增加带标签的受管召唤物，用于世界 Boss 的文字碑、句核和关联实体。
- 使用上述能力实现 `spectrum:manuscript_silence` 三阶段 Boss 示例。
- 明确跳过地牢房间、实例副本、队伍匹配和副本进度系统。

完整说明见 `docs/GLYPH_BOSS_SPEC.md`。

## 阶段 17：召唤归属与资源生命周期

- 统一施法、召唤的结构化返回值与预期拒绝结果码，保留真正错误原因。
- 用单一后端完成召唤，避免失败时重复尝试；完整失败回收部分实体。
- 增加主人和批次查询、真实死亡判定、寿命及显式清理。
- 为临时标签增加执行租约，完善召唤物与 Display 的卸载和孤儿清理。
- 迁移缄言的机制分支、伤害规则以及全部子技能调用示例。
- 增加批次、临时状态、实际终章脚本和文档编译回归；完整契约见 `docs/SUMMON_LIFECYCLE_SPEC.md`。
