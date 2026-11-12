# 技能运行时规范

## 驱动方式

运行时由外部按游戏 tick 驱动。插件层调用：

```text
scheduler.tick(currentTick)
```

调用不会创建阻塞线程。每个技能实例在单次恢复中最多执行 `max-steps-per-resume` 步，达到预算后返回下一次 tick 继续执行。

## 等待

`wait 30t` 会把实例置为等待状态，并记录恢复 tick。目标 tick 到达之前，实例不会执行任何动作；到达后从等待语句之后继续执行。

## 生命周期

技能实例有以下状态：

- `RUNNABLE`：可以继续执行。
- `WAITING`：等待目标 tick。
- `COMPLETED`：执行完成。
- `CANCELLED`：被外部取消。
- `FAILED`：运行时动作或表达式执行失败。

完成、取消和失败的实例会从调度器移除。等待和预算让出的实例继续保留。

运行时还可以按施法者 UUID 或世界 UUID 批量取消实例。实体死亡、实体从世界移除、世界卸载和插件关闭都通过这一机制清理等待中的技能。

## 动作注册

动作使用完整名称注册，例如：

```text
mechanic.particles.circle
targeter.players.within
mechanic.damage
mechanic.ignite
targeter.self
targeter.target
targeter.attacker
targeter.entities.within
targeter.random
condition.all
condition.any
condition.not
condition.isPlayer
condition.isLiving
condition.healthBelow
condition.healthAbove
condition.distanceBelow
condition.eventCause
mechanic.message.send
mechanic.title.show
mechanic.actionbar.show
mechanic.sound.play
random.chance
random.number
state.get
state.set
state.increment
skills.cast
command.run
command.raw
mechanic.entity.setHealth
mechanic.entity.teleport
mechanic.entity.velocity
mechanic.entity.remove
mechanic.entity.potion
condition.hasPotion
mechanic.projectile.launch
mechanic.projectile.forward
mechanic.mob.summon
targeter.entities.nearest
mechanic.bossbar.show
mechanic.scoreboard.set
variables.get
variables.set
variables.increment
variables.has
```

动作接收抽象参数并返回运行时值。`ScriptApiBindings` 将签名和实现一起校验并发布为不可变快照，缺失、重复或未知绑定在初始化时失败；返回类型不符合签名时生成可读的执行错误。全部签名与扩展方法见 [脚本 API](SCRIPT_API.md)。平台适配层负责把参数转换为实体和世界操作；当前插件按 Bukkit 主线程调度规则运行。

消息、Title 和 ActionBar 使用 Bukkit/TabooLib 的原生发送接口，颜色文本只转换 `&` 颜色码，已经是 `§` 的字符保持不变。Title 的三个时间参数是游戏 tick。声音名称会转换为 Bukkit `Sound` 枚举。临时 BossBar 以执行 UUID 和玩家 UUID 为键，在实例内复用；当 `progress` 使用 `caster.health.percent` 时，插件每个 Tick 自动同步血量。

命令动作分为两级：`command.run` 只查找 `commands.definitions` 白名单模板；`command.raw` 直接以控制台身份分发字符串，默认关闭。开启 `commands.allow-raw` 后，插件启动日志会明确警告服主。

实体动作全部在服务器主线程执行。`mechanic.entity.teleport` 使用脚本位置值，`mechanic.entity.velocity` 使用 x/y/z 数字，`mechanic.entity.potion` 的 amplifier 从 0 开始。`mechanic.projectile.launch` 创建带 PDC 标记的方块投射物，由插件 Tick 服务执行射线和实体碰撞检测，命中后造成一次伤害并移除；`mechanic.projectile.forward` 使用施法者视线方向发射；两者都支持可选的 `placeOnHit`，开启后会在可放置的方块碰撞点放置真实方块。`mechanic.mob.summon` 只生成配置中已注册的受管怪物，单次调用最多生成 16 个。BossBar 在持续时间结束后自动移除，记分板动作写入目标玩家自己的 Bukkit Scoreboard。

## 预算

运行时执行以下限制：

- 单次恢复最大步数
- 全局活动实例数量
- 单次技能最大循环次数
- 单个施法者最大状态项数量
- 单次查询最大目标数量
- 变量作用域最大状态项数量
- 动作调用最大深度
- 跨 tick、跨施法者和解除联动的子技能链最大深度

超过限制会产生失败或预算让出结果，避免配置错误拖垮服务器 tick。

施法实例以完整技能资源 ID 管理冷却，并支持按施法者、施法组处理并行、拒绝和替换。`max-active-executions` 包括已经完成但仍持有资源或联动后代的作用域。程序完成只移除执行任务；作用域在资源和后代释放后回收。手动取消、阶段切换和实例失败会按策略取消联动子树、清理投射物与临时血条。完整规则见 [施法管理规范](CASTING_SPEC.md)。

## 当前插件接入

插件启动时扫描 `skills/` 目录并编译 `.sms` 文件。`/spectrummobs reload` 会同时重载怪物和技能配置；单个 `.sms` 或 YAML 文件出错时，合法文件会进入新的原子快照并报告诊断；只有本次没有任何合法文件时才继续使用旧快照。

当前调试命令是 `/spectrummobs skill cast <namespace:id>`。它使用玩家作为施法者，验证技能运行时与 Bukkit 动作绑定。`/spectrummobs mob spawn <namespace:id>` 会生成带 PDC 身份标记的怪物，`/spectrummobs mob kill <namespace:id> <uuid>` 只移除匹配的受管实体，`/spectrummobs mob killall` 移除所有受管实体；`damaged` 事件会根据怪物定义触发技能。

受管怪物作为攻击者造成实体伤害时会触发 `attack` 事件。怪物 YAML 中的 `intervals` 由插件为每个实体保存独立的下次触发 tick；实体死亡、从世界移除或世界卸载时会清理计时器。

触发事件会向脚本提供 `target` 和 `event` 上下文：`event.name` 是触发器名称，`event.damage` 是事件伤害值。

`event.attacker` 是造成伤害的实体，没有攻击者时为 `null`；`event.cause` 是 Bukkit 伤害原因名称，`event.hand` 在当前事件没有提供时为 `unknown`。`repeat` 不创建线程，只在后续 tick 中按循环预算恢复执行。

技能可以保存实体位置快照再等待，例如 `blue_flame_ring.sms` 会先保存目标玩家的 `position`，等待 60 tick 后在原位置生成第二个粒子环，并通过 `in` 比较原目标是否仍在伤害范围内。

`RuntimeEntityResolver` 由平台注入。Targeter 的新查询和实体 Condition 会检查当前对象；已死亡、失效、下线的实体不再作为有效目标。已保存的数据快照保持不变，Mechanic 使用实体身份解析平台对象并拒绝失效目标。范围查询采用球形距离，先排序再限制结果数；第三方 Targeter 也受相同的返回类型和数量预算约束。
