# Spectrum Script 语言规范

Spectrum Script 文件使用 `.sms` 扩展名。它是受控的领域语言，不是 Kotlin、JavaScript 或任意代码执行器。

## 第一版语法范围

- 技能声明
- 变量声明和赋值
- `if`、`else`
- `for`
- `return`
- `wait`
- `repeat`
- 函数调用
- 技能调用
- 数字、布尔值、字符串、时长、百分比和位置类型

当前 Parser 已支持以下结构：

```text
script       := { skill }
skill        := "skill" identifier "{" { property | statement } "}"
property     := "cooldown" "=" expression line-end
             | "requires" expression line-end
             | ("group" | "concurrency" | "cancelOnPhaseChange" | "cleanupOnCancel") "=" literal line-end
statement    := "let" identifier "=" expression line-end
             | "wait" expression line-end
             | "repeat" expression block
             | "state" identifier ("=" | "+=" | "-=") expression line-end
             | "if" expression block ["else" block]
             | "for" identifier "in" expression block
             | "return" [expression] line-end
             | expression line-end
block        := "{" { statement } "}"
```

表达式支持成员访问、函数调用、位置与命名参数、算术、比较、逻辑、一元运算和列表成员判断 `value in list`。位置参数必须位于命名参数之前，不能重复传入参数，求值顺序与源码一致。`&&` 和 `||` 短路求值。实体成员判断按 UUID 处理，因此等待前后的查询快照可以识别同一个实体。字符串支持 `${变量}` 和 `${对象.成员}` 插值。词法、语法与类型错误记录文件、行号和列号，正文使用英文；插件通过 TabooLib I18n 的 `error-script-compile` 节点输出诊断。

局部变量具有块作用域。分支、循环体和循环变量退出作用域后恢复外层绑定，等待期间保留当前作用域。命名空间与已注册函数可以保存到局部变量后调用，仍然只能访问显式注册的 API。

## 基础类型

当前类型检查器提供 `Number`、`String`、`Boolean`、`Duration`、`Percent`、`Entity`、`Player`、`Position`、`List<T>` 和 `Unit`。`Player` 可以作为 `Entity` 使用；其他不兼容类型会在编译阶段报告错误。

施法组、并发策略、阶段取消和效果清理只在技能头部声明，字段和值见 [施法管理规范](CASTING_SPEC.md)。这些元数据不接受动态表达式。`mechanic.skills.cast` 必须显式传入目标，返回 `OperationResult`，通过 `result.id` 读取子技能执行 UUID。

内置上下文包括 `caster`、`target`、`event`、`phase`、`interval`、`FLAME` 和 `SOUL`。正式动作使用 `targeter`、`condition`、`mechanic` 命名空间，辅助服务为 `random`、`state`、`variables`、`command`。参数、YAML 绑定和规则函数见 [参数化技能与规则模块](PARAMETER_RULE_SPEC.md)；完整签名与扩展注册见 [脚本 API](SCRIPT_API.md)。旧的顶层动作与实体条件成员已经删除。

`event` 提供 `name`、`damage`、`attacker`、`cause` 与 `hand`。`phase` 表示当前阶段 ID，`interval` 表示间隔触发器 ID。实体快照提供 `position`、`health.value` 和 `health.percent`；状态判断使用 `condition.isPlayer(entity)`、`condition.hasPotion(entity, "SPEED")` 等函数。列表提供 `size` 和 `isEmpty`，`targeter.random` 保留列表的具体实体类型。

反馈动作使用服务器原生接口：

```text
mechanic.message.send(player, "&e受到 ${event.damage} 点伤害")
mechanic.title.show(player, "&c熔火守卫", "&4进入狂暴阶段", 5t, 40t, 10t)
mechanic.actionbar.show(player, "§7阶段：${phase}")
mechanic.sound.play(player, "entity.blaze.shoot", 1, 1)
mechanic.projectile.launch(from = caster.position, to = target.position, material = "MAGMA_BLOCK", speed = 1.2, damage = 12, lifetime = 60t, hitRadius = 0.5, placeOnHit = false)
mechanic.projectile.forward(material = "MAGMA_BLOCK", speed = 1.2, damage = 12, lifetime = 60t, hitRadius = 0.5, placeOnHit = false)
mechanic.mob.summon(id = "spectrum:magma_minion", at = caster.position, count = 3)
mechanic.entity.setHealth(target, 20)
mechanic.entity.teleport(target, caster.position)
mechanic.entity.velocity(target, 0, 1, 0)
mechanic.entity.potion(target, "SPEED", 5s, 1)
mechanic.entity.remove(target)
mechanic.bossbar.show(player, "&cBoss", 0.5, 5s)
mechanic.scoreboard.set(player, "boss_phase", 2)
```

`targeter.entities.nearest(center, radius)` 返回最近实体，没有实体时返回 `null`。

变量作用域通过 `variables` 服务访问：

```text
variables.set("skill", "casts", 1)
variables.increment("caster", "rage", 1)
variables.set("target", "marked", 1)
variables.set("world", "bosses", 3)
variables.set("global", "season", 1)
```

`skill` 是当前技能实例作用域，`caster` 在同一施法者的技能之间共享，`target` 按目标实体 UUID 共享，`world` 按世界 UUID 共享，`global` 在当前运行时实例中共享。所有作用域都受状态项上限约束。

文本只执行 `&` 到 `§` 的颜色码转换，不解析 MiniMessage 或其他标记语言。`random.chance(25%)` 返回布尔值，`random.number(0, 1)` 返回数字；`state.get`、`state.set` 和 `state.increment` 读写施法者状态，也可以使用 `state rageCount += 1` 或 `state rageCount = 0` 语法。`mechanic.skills.cast("spectrum:other_skill", caster, target)` 可以从当前技能启动另一个技能，仍然受到活动实例、冷却和生命周期限制。`command.run("registered_id", target)` 只能执行配置白名单中的命令模板；`command.raw("...")` 默认关闭，只有 `config.yml` 显式开启后才能执行。状态在同一施法者的技能实例之间共享，并受状态项上限限制。

完整示例：

```sms
skill flame_nova {
    cooldown = 8s
    requires condition.healthBelow(caster, 50%)

    let center = caster.position
    mechanic.particles.circle(at = center, particle = FLAME, radius = 5, count = 48)
    wait 30t

    for player in targeter.players.within(center, 5) {
        mechanic.damage(player, 12)
        mechanic.ignite(player, 3s)
    }
}
```

投射物示例：

```sms
skill magma_bolt {
    mechanic.projectile.launch(from = caster.position, to = target.position, material = "MAGMA_BLOCK", speed = 1.2, damage = 12, lifetime = 60t, hitRadius = 0.5, placeOnHit = false)
}
```

`mechanic.projectile.launch` 会创建带 PDC 标记的方块投射物，由插件 Tick 服务进行射线和实体碰撞检测。命中生物或玩家后造成一次伤害并移除投射物；施法者、生命周期、速度、伤害、生命周期和命中半径都受到插件限制。`material` 必须是方块材质，`from` 与 `to` 必须位于同一世界。可选参数 `placeOnHit` 默认是 `false`；设为 `true` 时，投射物撞击方块的落点为空气或可通行方块时会放置真实方块。

`mechanic.projectile.forward` 使用施法者当前位置和视线方向发射，不需要 `target`，适合扇形前方或直线技能。

`mechanic.bossbar.show` 的 `progress` 支持 `0.0..1.0` 数字或百分比值，例如 `caster.health.percent`；同一个执行实例内向同一玩家重复显示会更新原 Bar。不同实例拥有独立临时血条，可分别清理。

`mechanic.mob.summon` 按资源 ID 生成受管怪物，在指定位置周围自动分散，并记录主人、批次和执行归属。YAML `summons.max-active` 限制主人同时拥有的数量；`count` 可选，单次最多 64 个。返回 `OperationResult`，通过 `result.count` 读取实际生成数量，`result.success` 判断完整成功。批次查询、击破判定、寿命与清理见 [召唤与临时资源规范](SUMMON_LIFECYCLE_SPEC.md)。

## 语义约定

- `wait 30t` 表示等待 30 个游戏 tick。
- 变量赋值保存值快照；`caster.position` 不会因为实体移动而自动改变。
- 查询列表的成员和实体属性是查询时的快照；再次调用 Targeter 获得当前快照。Condition 与 Mechanic 通过实体身份解析当前平台对象。
- `requires` 在技能消耗冷却前检查，条件失败不会消耗冷却。
- 运行时动作必须通过注册表提供，脚本不能反射调用任意类。

当前阶段已完成源码到 AST、Typed IR 和可暂停运行时的转换。编译器会检查内置对象、成员、函数参数、条件、等待时长、列表成员类型和 `repeat` 次数类型，插件层按游戏 tick 驱动技能执行。`repeat` 和 `for` 共用循环预算，查询目标、状态项和活动实例都有运行时上限。
