# Targeter、Condition 与 Mechanics API

## 最终命名空间

| 入口 | 职责 | 返回值 |
| --- | --- | --- |
| `targeter` | 选择目标 | 实体或实体列表 |
| `condition` | 判断当前状态 | `Boolean` |
| `mechanic` | 执行世界操作或反馈 | 通常为 `Unit` |

旧的顶层查询、动作以及实体条件成员已经删除，没有兼容别名。仓库内置技能已全部迁移；升级已有服务器时，也需要修改服务器 `skills/` 下的 `.sms` 文件。旧写法会产生带文件、行列位置的英文编译诊断，由 TabooLib I18n 输出。错误文件不会阻塞其他合法技能加载。

`random`、`state`、`variables`、`command` 是独立的辅助服务。启动子技能使用 `mechanic.skills.cast`，旧 `skills.cast` 不再注册。

## Targeter

| 调用 | 返回值与行为 |
| --- | --- |
| `targeter.self()` | 当前施法者的新实体快照；失效时为 `null` |
| `targeter.target()` | 当前触发目标的新快照；失效时为 `null`；没有显式目标的上下文默认使用施法者 |
| `targeter.attacker()` | `event.attacker` 对应实体的新快照；不存在或失效时为 `null` |
| `targeter.players.within(center, radius)` | 球形范围内有效、在线玩家的 `List<Player>` |
| `targeter.entities.within(center, radius)` | 球形范围内有效且未死亡的已加载实体 `List<Entity>`，包含施法者和非生物实体 |
| `targeter.entities.nearest(center, radius)` | 球形范围内最近的有效实体，排除施法者；没有候选时为 `null` |
| `targeter.entities.tagged(center, radius, tag)` | 选择范围内带有 Bukkit scoreboard tag 的实体 |
| `targeter.threat.top()` | 当前施法者威胁表中的最终目标；可由怪物 YAML 的 `target-rules.threat` 规则重新选择 |
| `targeter.threat.list()` | 当前施法者威胁表中按威胁降序排列的有效实体列表 |
| `targeter.random(targets, count)` | 无放回随机选择，保留元素类型；`List<Player>` 仍返回 `List<Player>` |
| `targeter.summons(owner, batch?, tag?)` | 查询指定主人、可选批次和标签的有效召唤物，不依赖空间公共标签 |

`center` 为 `Position`，`radius` 为有限的非负数字，按实体位置的三维欧氏距离判断，包含范围边界。查询不会主动加载区块。结果先按距离排序，等距时按 UUID 排序，再应用 `engine.max-query-targets`。

`count` 必须为非负整数，`0` 返回空列表。随机选择过滤失效实体、按 UUID 去重，并受目标查询上限约束。扩展 Targeter 返回的列表也统一去重并限制数量。

列表没有 `positions()` 等专用实体方法。获取位置使用 `entity.position`，需要保存时使用普通局部变量。

## Condition

| 调用 | 行为 |
| --- | --- |
| `condition.isPlayer(entity)` | 当前是否为有效、在线玩家 |
| `condition.isLiving(entity)` | 当前是否为有效、未死亡生物 |
| `condition.healthBelow(entity, threshold)` | 当前生命值严格小于阈值 |
| `condition.healthAbove(entity, threshold)` | 当前生命值严格大于阈值 |
| `condition.hasPotion(entity, effect)` | 当前是否具有指定药水效果，例如 `"SPEED"` |
| `condition.distanceBelow(first, second, radius)` | 当前位于同一世界，且距离严格小于半径 |
| `condition.eventCause(cause)` | 事件原因匹配字符串，忽略大小写，例如 `"ENTITY_ATTACK"` |
| `condition.threat.above(entity, amount)` | 当前施法者对实体的威胁值是否严格大于给定值 |
| `condition.threat.isTop(entity)` | 实体是否是当前施法者威胁表最终目标 |
| `condition.hasTag(entity, tag)` | 实体是否拥有指定 scoreboard tag |
| `condition.summons.defeated(owner, batch)` | 本批完整生成且全部成员在战斗中死亡；清理、移除或部分生成不算击破 |
| `condition.all(left, right)` | 两个布尔值同时成立 |
| `condition.any(left, right)` | 至少一个布尔值成立 |
| `condition.not(value)` | 布尔值取反 |

实体条件重新读取当前平台状态。失效实体、已下线玩家、缺失目标和不具备相应能力的实体返回 `false`；无效参数仍然报告错误。

生命阈值 `40` 表示 40 点生命，`40%` 表示最大生命的 40%；数字必须有限且非负，百分比范围为 `0%..100%`。距离半径同样要求有限且非负。

组合函数的实参按源码顺序求值。需要短路保护时使用 `&&`、`||`、`!`；被短路的右侧表达式不会执行。条件可以用于 `requires` 和 `if`。`requires` 失败不消耗冷却。

`requires` 与 `cooldown` 只允许标记为可用于头部的查询、条件和只读辅助函数。Mechanic、状态写入、命令和技能启动会在编译时被拒绝，避免检查条件时提前产生副作用。Targeter 与事件条件始终使用原始触发上下文，不受同名局部变量影响。

## Mechanics

下表参数名也是正式命名参数。`?` 表示可省略，写脚本时不带问号。

| 调用 | 行为 |
| --- | --- |
| `mechanic.damage(target, amount)` | 对生物造成伤害 |
| `mechanic.ignite(target, duration)` | 设置燃烧时长 |
| `mechanic.entity.setHealth(entity, amount)` | 设置生命值，限制到 `0..最大生命` |
| `mechanic.entity.teleport(entity, position)` | 传送到位置快照 |
| `mechanic.entity.velocity(entity, x, y, z)` | 设置速度 |
| `mechanic.entity.remove(entity)` | 移除实体 |
| `mechanic.entity.addTag(entity, tag)` | 添加 scoreboard tag |
| `mechanic.entity.removeTag(entity, tag)` | 移除 scoreboard tag |
| `mechanic.entity.temporaryTag(entity, tag, duration)` | 登记执行所有权的临时标签；并发租约相互隔离，保留原有永久标签 |
| `mechanic.entity.pull(entity, center, strength)` | 将实体向中心推拉一次 |
| `mechanic.entity.push(entity, center, strength)` | 将实体从中心推开一次 |
| `mechanic.entity.potion(entity, effect, duration, amplifier)` | 给予药水效果，等级从 0 开始 |
| `mechanic.particles.circle(at, particle, radius, count)` | 生成水平粒子圆环 |
| `mechanic.particles.line(from, to, particle, step)` | 沿两点生成粒子线 |
| `mechanic.ray.damage(from, to, damage, radius)` | 对线段半径内的生物造成伤害 |
| `mechanic.message.send(player, message)` | 发送聊天消息 |
| `mechanic.title.show(player, title, subtitle, fadeIn, stay, fadeOut)` | 发送 Title，时间参数为时长 |
| `mechanic.actionbar.show(player, message)` | 发送 ActionBar |
| `mechanic.sound.play(player, sound, volume, pitch)` | 播放声音 |
| `mechanic.projectile.launch(from, to, material, speed, damage, lifetime, hitRadius, placeOnHit?)` | 向目标位置发射受控方块投射物 |
| `mechanic.projectile.forward(material, speed, damage, lifetime, hitRadius, placeOnHit?)` | 按施法者当前视线发射 |
| `mechanic.projectile.homing(target, material, speed, turnRate, damage, lifetime, hitRadius, placeOnHit?)` | 发射会逐 tick 转向目标的方块投射物 |
| `mechanic.mob.summon(id, at, count?, tag?, ignoreRejected?, lifetime?)` | 生成受管小兵，默认 1 个，单次最多 64 个，返回 `OperationResult`；批次登记执行资源所有权 |
| `mechanic.mob.cleanup(owner, batch)` | 清理指定批次的存活成员，不计为战斗死亡 |
| `mechanic.skills.cast(id, caster, target, linked?, ignoreRejected?)` | 显式指定目标启动子技能，返回 `OperationResult`；仅允许忽略预期拒绝 |
| `mechanic.threat.add(entity, amount)` | 为施法者增加目标威胁值 |
| `mechanic.threat.set(entity, amount)` | 设置施法者对目标的威胁值 |
| `mechanic.threat.remove(entity)` | 从施法者威胁表移除目标 |
| `mechanic.threat.clear()` | 清空施法者威胁表 |
| `mechanic.bossbar.show(player, title, progress, duration)` | 临时 BossBar，进度支持 `0..1` 数字或百分比 |
| `mechanic.scoreboard.set(player, objective, score)` | 设置玩家记分板分数 |

所有 Mechanics 在服务器主线程执行，复用任务预算、冷却、取消和失败事件。失效实体不能接收动作；等待后可以先用 Condition 检查。失败只结束相应技能实例。

消息动作的 `player` 要求 `Player`；玩家 Targeter 及其随机子集保留该类型。文本使用原生 `&` / `§` 颜色码，也支持 `i18n:节点名`，不解析 MiniMessage。

`placeOnHit` 默认 `false`，开启时只在允许放置的方块碰撞点放置真实方块。小兵归属、并存上限与清理由 YAML `summons` 控制。Boss 的持久血条使用 YAML `bossbar`；脚本动作负责临时反馈。

## 辅助服务

| 调用 | 行为 |
| --- | --- |
| `random.chance(chance)` | 百分比概率判断 |
| `random.number(minimum, maximum)` | 生成范围内数字 |
| `state.get(key)` | 读取施法者数字状态，缺省为 0 |
| `state.set(key, value)` | 设置施法者数字状态 |
| `state.increment(key, amount)` | 累加并返回新值 |
| `variables.get(scope, key)` | 读取作用域数字状态 |
| `variables.set(scope, key, value)` | 设置作用域状态 |
| `variables.increment(scope, key, amount)` | 累加并返回新值 |
| `variables.has(scope, key)` | 判断状态项是否存在 |
| `command.run(id, target)` | 执行白名单命令模板 |
| `command.raw(command)` | 执行控制台命令，默认由 `commands.allow-raw` 关闭 |

变量作用域为 `skill`、`caster`、`target`、`world`、`global`。命令白名单和 raw 开关继续有效，开启 raw 时插件加载日志警告服主。

`mechanic.skills.cast` 必须显式传入目标。`linked` 默认 `true`，设为 `false` 解除父子取消联动，仍继承根实例与调用深度。`ignoreRejected` 适合周期轮询，仅将冷却、施法组占用、条件失败和容量拒绝转换为失败结果。缺失定义、生成异常和深度超限始终报告错误。调用不会等待子技能完成。施法与召唤结果都提供 `success`、`id`、`count`、`requested`、`code`、`reason` 成员；数量是调用时快照。完整契约与迁移说明见 [召唤与临时资源规范](SUMMON_LIFECYCLE_SPEC.md)。

## 快照与当前状态

查询返回实体数据快照，列表成员不会自动变化。`let center = player.position` 保存当时的位置；等待后读取同一份实体快照的 `position` 仍是原坐标。再次调用 Targeter 才获得新快照。Condition 和 Mechanic 则通过实体身份解析当前平台对象。

下面随机选取至多两名玩家；等待后查询旧中心的当前玩家，判断原目标是否躲开：

```sms
skill registered_warning {
    cooldown = 8s
    requires condition.isLiving(caster)

    let center = caster.position
    let selected = targeter.random(targeter.players.within(center, 12), 2)
    for player in selected {
        mechanic.message.send(player, "&b三秒后检查你是否仍在范围内")
    }
    mechanic.particles.circle(center, SOUL, 5, 48)
    wait 3s

    let current = targeter.players.within(center, 5)
    for player in selected {
        if (condition.isPlayer(player)) {
            if (player in current) {
                mechanic.damage(player, 12)
            } else {
                mechanic.message.send(player, "&a你躲过了攻击！")
            }
        }
    }
}
```

## 扩展插件注册

`ScriptApiRegistry` 提供编译签名，`RuntimeActionRegistry` 提供执行实现。`ScriptApiBindings` 将两者组成同一份快照：构造时拒绝重复名称、命名空间冲突、非法类型和缺失实现，执行时验证返回类型。类型检查器不再维护第二份硬编码动作表。

通过 `SpectrumMobs.registerScriptApi` 同时登记签名和实现：

```kotlin
SpectrumMobs.registerScriptApi {
    register(
        "mechanic.example.heal",
        listOf(
            FunctionParameter("target", ScriptType.Entity),
            FunctionParameter("amount", ScriptType.Number)
        ),
        ScriptType.Unit
    ) { context, arguments ->
        val value = arguments.single { it.name == "target" }.value
        val living = context.resolveEntity(value)?.origin as? LivingEntity
            ?: return@register RuntimeValue.UnitValue
        val amount = (arguments.single { it.name == "amount" }.value as RuntimeValue.NumberValue).value
        require(amount.isFinite() && amount >= 0) { "Heal amount must be finite and non-negative" }
        val maximum = living.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: living.health
        living.health = (living.health + amount).coerceAtMost(maximum)
        RuntimeValue.UnitValue
    }
}
```

示例使用 `org.ewsk.spectrummobs.SpectrumMobs`、`org.ewsk.spectrummobs.script.*` 以及 Bukkit 的 `LivingEntity`、`Attribute`。扩展插件依赖 SpectrumMobs，在服务器主线程注册。初始化前可以登记回调，启用后可以追加 API；启用后追加需再执行 `/spectrummobs reload` 编译相应技能。

API 完整名称按大小写不敏感规则保证唯一；参数名区分大小写。扩展使用自己的子命名空间，例如 `targeter.example.*`、`condition.example.*`、`mechanic.example.*`。注册只能追加，不能覆盖已有动作。已经运行的技能持有原绑定快照。扩展不支持热移除，卸载扩展应重启服务器。

Targeter 与 Condition 默认可以用于头部，扩展实现必须保持只读。辅助服务需要通过 `safeInHeaders = true` 显式声明头部可用；Mechanic 不能声明头部可用。

编译器将位置参数绑定为正式名称，按源码顺序求值，拒绝重复、遗漏和未知参数。可选参数省略时没有占位参数，实现应按 `RuntimeArgument.name` 取值。扩展 Kotlin 代码是受信任的平台代码，须遵守主线程、非阻塞和生命周期约定；脚本不能调用未注册的 Kotlin、Java 或系统接口。

## 验证

测试覆盖旧语法拒绝、绑定完整性、扩展注册、参数绑定、泛型玩家列表、返回类型、查询预算、球形边界、最近排序、随机去重、跨世界距离、生命阈值、短路、等待后的状态变化、冷却，以及内置技能整体编译。文档中标记为 `sms` 的完整示例也参与编译测试。
