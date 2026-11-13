# 触发器与重载规范

所有怪物技能触发都使用同一种上下文模型。上下文包含：

- `event.name`：`attack`、`damaged`、`interval`、`phase_enter`、`phase_exit` 或手动施法对应的事件名；
- `event.damage`：事件伤害，没有伤害时为 `0`；
- `event.attacker`：当前事件关联的目标实体快照，没有目标时为 `null`；
- `event.cause` 和 `event.hand`：平台事件提供的原因与手部信息；
- `phase`：施法者当前 Boss 阶段；
- `interval`：周期触发器 ID，仅 interval 触发存在；
- `target`：当前触发目标实体快照，没有目标时保留默认运行时目标。

`attack` 和 `damaged` 由 Bukkit 事件路由，`interval` 由每个实体独立的 UUID 计时器路由，`phase_enter` 与 `phase_exit` 由 Boss 阶段转换路由，命令和 `mechanic.skills.cast` 使用相同的技能运行时入口。所有入口都会复用冷却、条件、活动实例上限、调用深度和生命周期取消。

每个实体的 interval 调度器只在到期的 tick 触发一次。实体死亡、从世界移除、无效或世界卸载时会清理其计时器；重新加载区块后重新注册受管实体。

阶段转换先按实例的 `cancelOnPhaseChange` 策略取消旧阶段技能及其联动后代，再发起阶段离开和进入技能。不同入口统一经过施法组并发策略、完整资源 ID 冷却和实例预算。`mechanic.skills.cast` 显式传入目标，继承事件上下文与根调用深度，不隐式复制父实例局部变量。详见 [施法管理规范](CASTING_SPEC.md)。

重载按文件收集合法结果并以一次快照替换当前快照。单个 `.sms` 或 YAML 文件出错时，错误会通过 I18n 报告，但其他合法文件仍会进入新快照。如果本次没有任何合法文件，旧快照保持不变并返回失败结果。

将 `config.yml` 的 `debug.trace-skills` 设为 `true` 后，触发器、技能施放结果和运行时事件会通过 I18n 输出调试日志；默认关闭。
