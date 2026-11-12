# 参数化技能与规则模块规范

## 技能参数

技能可以在名称后声明参数：

```sms
skill flame_nova(radius: Number = 5, damage: Number = 12, windup: Duration = 30t) {
    cooldown = 8s
    requires radius > 0 && damage >= 0

    let center = caster.position
    mechanic.particles.circle(center, FLAME, radius, 48)
    wait windup
    for player in targeter.players.within(center, radius) {
        mechanic.damage(player, damage)
    }
}
```

支持 `Number`、`String`、`Boolean`、`Duration`、`Percent`、`Entity`、`Player`、`Position`、`Particle` 和 `List<T>`。参数名必须唯一。没有默认值的参数必须传入；默认值必须是常量表达式，不能读取实体、调用函数或访问状态。可选参数之后不能再声明必填参数。

参数在实例创建时绑定，然后依次检查 `requires`、冷却和施法组。参数错误不会消耗冷却，也不会创建实例。参数属于当前实例，等待期间保持不变。

## 子技能与 YAML

`skills.cast` 的控制参数为 `id`、`caster`、`target`、可选 `linked` 和 `ignoreRejected`；目标技能声明的其他命名参数作为实参。`ignoreRejected` 适合周期行为在冷却或施法组占用时静默跳过：

```sms
skill parent {
    let child = skills.cast("spectrum:flame_nova", caster, target, linked = true, radius = 8, damage = 20, windup = 2s)
}
```

怪物事件支持字符串和对象格式：

```yaml
events:
  damaged:
    - skill: spectrum:flame_nova
      args:
        radius: 8
        damage: 20
        windup: 2s
```

`intervals[].skills` 支持同样的 `skill/args` 对象。数字、布尔、百分比字符串、时长字符串和普通字符串会转换为 Script 值。默认值属于技能定义，YAML 参数只属于这次触发。

管理命令支持最多六个 `key=value` 参数：

```text
/spectrummobs skill cast spectrum:flame_nova radius=8 damage=20 windup=2s
```

命令参数只影响本次施法，无参数时使用默认值。

## 规则模块

规则模块使用 `fn` 声明，并通过显式导入调用：

```sms
// skills/spectrum/combat.sms
fn can_hit(victim: Entity): Boolean {
    return condition.isLiving(victim) && !condition.hasPotion(victim, "FIRE_RESISTANCE")
}

fn damage_for(source: Entity, base: Number): Number {
    if (condition.healthBelow(source, 40%)) { return base * 1.5 }
    return base
}
```

```sms
import "spectrum:combat" as combat

skill attack {
    for player in targeter.players.within(caster.position, 8) {
        if (combat.can_hit(player)) { mechanic.damage(player, combat.damage_for(caster, 12)) }
    }
}
```

规则函数必须声明参数和返回类型，可以返回布尔、数字、时长、位置、实体或列表。规则函数只能同步只读求值：禁止 `wait`、Mechanics、命令、`skills.cast` 和状态写入；不隐式捕获 `caster` 或 `target`；禁止递归和循环导入；嵌套调用共享调用深度与规则步数预算；所有路径必须返回声明类型。

导入 ID 必须存在，别名不能覆盖内置上下文或重复声明。规则签名、实现和技能 Typed IR 在同一快照中发布；重载后已经运行的技能继续使用旧快照。

规则模块也可以被怪物 YAML 直接引用。`target-rules.threat` 的函数接收 `candidates: List<Entity>` 并返回最终目标；`damage-rules.incoming/outgoing` 的函数接收 `amount: Number`、`cause: String` 并返回伤害倍率；掉落项 `rule` 接收 `amount: Number`、`chance: Number` 并返回概率倍率。这些求值都同步使用当前完整规则快照，并继续受到规则步数和调用深度预算限制。

## 错误与验证

动态默认值、未知类型、未知参数、缺失参数、规则缺少返回、非法返回、规则副作用和缺失导入都会生成带来源位置的英文诊断。错误模块只影响依赖它的技能，其他合法文件继续加载。

测试覆盖参数默认值、运行时覆盖、子技能绑定、YAML/命令绑定、规则导入、只读限制、返回值、步数预算、缺失依赖、部分加载和快照一致性。
