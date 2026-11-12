# 施法管理、打断与效果所有权

## 配置来源

施法策略只在 `.sms` 技能声明中配置，不在怪物 YAML 重复定义。以下四个字段必须使用字面量：

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| `group` | `"default"` | 同一施法者的施法组；1 到 64 个字母、数字、点、下划线或连字符，区分大小写 |
| `concurrency` | `"parallel"` | 新施法遇到相同施法组时的处理策略 |
| `cancelOnPhaseChange` | `true` | 所属阶段离开时取消该实例 |
| `cleanupOnCancel` | `true` | 取消或失败时清理该实例拥有的可清理效果 |

非法值、动态表达式和重复字段产生带文件、行列位置的英文编译错误，通过 TabooLib I18n 输出。单个错误文件不阻塞其他合法配置加载。

## 并发策略

- `parallel`：允许新实例与该组现有实例并行。
- `reject`：组内还有运行中的技能或联动子技能时，拒绝新实例，不消耗其冷却。
- `replace`：先验证条件、冷却、调用深度与容量，再取消该组冲突实例及其联动子实例，启动新实例。

策略由新发起的技能决定；组按施法者 UUID 隔离，不同怪物互不影响。联动子技能不会与自己的祖先争抢组，但同组兄弟技能仍然受策略约束。解除联动的子技能按独立实例参与组竞争。

仅剩飞行投射物或临时血条时，不继续占用施法组。父技能程序执行完毕但联动子程序仍在运行时，父技能的组仍然占用。冷却键使用完整的 `namespace:id` 加施法者 UUID，不同命名空间中同名声明互不共享冷却。

## 子技能调用

正式签名为 `skills.cast(id, caster, target, linked?, ignoreRejected?)`，返回新实例 UUID 字符串或空字符串。`target` 必须显式传入，`linked` 可省略，默认为 `true`；`ignoreRejected` 默认为 `false`，设为 `true` 时冷却、施法组和预算拒绝只返回空字符串，适合周期行为轮询。两参数调用已经移除，没有兼容别名。

子技能获得显式的施法者和目标、原事件上下文、当前施法者阶段以及周期上下文，拥有独立的技能局部状态。父技能的普通局部变量不会隐式复制给子技能。原有 `caster`、`target`、`world`、`global` 状态作用域按各自身份共享。

- 联动子技能：父技能取消或失败会取消它及其联动后代；父技能正常完成不会取消子技能。
- `linked = false`：解除父子取消联动，仍保留父 ID、根 ID 和调用深度以供追踪。
- 所有子技能都继承调用链深度；异步跨 tick 或解除联动都不能重置深度。外部命令和怪物触发器作为新根调用，深度为 1。
- 新子技能安排到后续调度轮次执行，不会在动作中阻塞等待其完成。
- 子技能启动被条件、冷却、组或预算拒绝时，默认 `skills.cast` 报告执行错误，父实例按失败规则结束；`ignoreRejected = true` 时只返回空字符串。缺失技能和动作运行错误始终报告。

解除父子联动不会绕过该实体自身的阶段策略、死亡、移除或世界卸载规则。同一实体的阶段切换仍会独立检查所有相关实例。

例如，以下两个技能分别保存为 `skills/spectrum/charged_attack.sms` 和 `skills/spectrum/charge_warning.sms`：

```sms
skill charged_attack {
    group = "main"
    concurrency = "reject"
    cancelOnPhaseChange = true
    cleanupOnCancel = true
    cooldown = 8s

    let center = caster.position
    let warning = skills.cast("spectrum:charge_warning", caster, target)
    mechanic.particles.circle(center, SOUL, 5, 48)
    wait 3s
    for player in targeter.players.within(center, 5) {
        mechanic.damage(player, 12)
    }
}
```

```sms
skill charge_warning {
    group = "visual"
    concurrency = "parallel"
    for player in targeter.players.within(caster.position, 12) {
        mechanic.bossbar.show(player, "&c正在蓄力", 1, 3s)
    }
    wait 3s
}
```

蓄力期间取消父实例，会停止后续伤害并取消提示子实例、移除它的临时血条。

## 打断与生命周期

阶段切换先取消旧阶段中启用 `cancelOnPhaseChange` 的实例，再启动阶段离开和进入技能。阶段归属记录来自触发上下文，不受脚本同名局部变量影响。

取消会立即从调度器移除待执行任务。即使取消发生在某个动作触发的同步事件中，当前实例也不会继续执行下一条语句；同一 tick 的其他已取消实例不会再从旧调度列表恢复。

父实例失败也会取消其联动后代。一次效果清理失败会报告 `EffectCleanupFailed` 事件，通过 I18n 输出英文错误，其他资源继续清理。

`cleanupOnCancel = false` 只保留可清理效果，不保留程序执行。该实例会显示为已取消，原效果按自己的寿命结束。施法者生命周期结束、世界卸载、插件关闭等清理入口强制回收关联资源。

重载仍使用原子快照：已经启动的技能继续使用原 Typed IR、策略和动作绑定；新实例使用新快照。单纯重载不会隐式打断旧实例。

## 效果所有权

方块投射物与脚本临时 BossBar 记录所属执行实例。

- 投射物命中、到期或被移除时，释放对应所有权；取消时按策略移除所属投射物。
- 临时 BossBar 在同一实例、同一玩家内复用；不同实例可以独立显示和清理，不会互相覆盖。到期或玩家下线时释放所有权。
- 程序已完成但仍拥有资源，或仍有联动后代时，保留作用域供查询和取消；资源和后代全部释放后递归回收记录。
- `engine.max-active-executions` 同时约束这些保留作用域，防止程序不断结束却无限累积效果记录。
- YAML 声明的持久 BossBar 继续由怪物服务管理，不归某个技能实例所有。

已经造成的伤害、发送的消息、播放的声音与粒子不会回滚；已经放置的真实方块也不会自动撤销。召唤物继续由怪物 YAML 的 `summons` 生命周期管理。

平台扩展可以通过 `context.effects.register(context.executionId, key, cleanup)` 登记可清理资源，并在资源自然结束时调用 `context.effects.release(context.executionId, key)`。同一实例内资源键必须唯一。实现与取消回调都在服务器主线程执行。

## 管理命令与调试

```text
/spectrummobs skill active
/spectrummobs skill active <page>
/spectrummobs skill cancel <execution-uuid>
```

命令沿用 `spectrummobs.admin` 权限，支持玩家和控制台。查询每页 5 条，显示实例 ID、完整技能 ID、施法者、组、状态、剩余等待 tick、源码行列、父实例、调用深度、联动子作用域数量、效果数量和取消原因。程序完成后仍持有资源的作用域也会列出。

取消命令接受执行实例 UUID，反馈包含实际取消的联动作用域数量和原因。`skill cast` 的启动反馈同时返回执行 UUID。取消原因也进入受 `debug.trace-skills` 控制的 I18n 调试日志。

## 验收

熔核君王的主要喷发、投射物和召唤技能使用 `main/reject`；阶段反馈使用独立的 `phase/replace`。在喷发预警后、伤害前切换阶段，应取消旧攻击。通过 `skill active` 可以查看等待状态，再手动取消指定实例。

自动测试覆盖命名空间冷却、同实体组冲突、失败替换、容量、父子取消、独立子链深度、同 tick 取消、阶段策略、源码查询、效果保留与自然释放。插件适配测试使用模拟 Bukkit 对象，验证目标传递、投射物实例隔离与血条复用清理；仍需在目标服务器进行实服验收。
