# 威胁表、行为策略与规则计算器

## 威胁表

每个受管怪物拥有独立的威胁表。表项使用目标 UUID、威胁值和最近更新时间保存，不把 Bukkit 实体对象放进核心模块。插件层在每次受管怪物受到实体伤害时增加伤害值，在受管怪物攻击实体时也记录伤害值；每个服务器 tick 按默认 `0.995` 因子衰减，目标死亡、下线、移除或世界卸载时自动失效。

脚本通过以下 API 访问威胁表：

```sms
skill threat_maintenance {
    let victim = targeter.threat.top()
    let candidates = targeter.threat.list()
    if (condition.isLiving(victim)) {
        if (condition.threat.above(victim, 20)) {
            mechanic.threat.remove(victim)
        }
        mechanic.threat.add(victim, 5)
    }
}
```

`targeter.threat.top()` 会先取得按威胁值排序的候选，再检查怪物 YAML 是否配置 `target-rules.threat`。配置了规则时，将候选列表作为 `candidates: List<Entity>` 传给只读 `fn`，规则返回的实体必须来自当前候选列表；返回无效实体时回退到最高威胁目标。`targeter.threat.list()` 始终返回当前有效候选，按威胁降序、UUID 升序排序。

## YAML 行为策略

怪物可以绑定一个高层行为技能：

```yaml
behavior:
  skill: spectrum:magma_lord_behavior
  period: 10t
```

插件把该配置转换成每个实体独立的 interval 计时器，并把触发上下文标记为 `behavior`。行为技能仍然使用普通 `.sms` 语法，因此可以显式组织施法组、冷却、等待、子技能和阶段取消。实体移除、死亡和世界卸载会清理计时器及其联动施法。

## 伤害计算器

在怪物 YAML 中使用 `damage-rules`：

```yaml
damage-rules:
  incoming: spectrum:combat.incoming
  outgoing: spectrum:combat.outgoing
```

规则函数是同步、只读、受步数和调用深度预算限制的 `fn`。`incoming` 在伤害作用于该怪物前执行，`outgoing` 在该怪物造成伤害前执行。函数必须接收 `amount: Number` 与 `cause: String`，返回有限的非负数字作为伤害倍率；`0` 表示免疫，`1` 表示不变，`1.5` 表示增加 50%。规则上下文包含 `caster`、`target` 和 `event`，其中 `event.damage` 是当前原始伤害。

```sms-rule
fn incoming(amount: Number, cause: String): Number {
    if (condition.eventCause("LAVA")) { return 0.5 }
    return 1
}
```

## 掉落计算器

单个掉落可以绑定规则：

```yaml
drops:
  - material: MAGMA_CREAM
    amount: 4
    chance: 1.0
    rule: spectrum:combat.drop_multiplier
```

掉落规则接收 `amount: Number` 与 `chance: Number`，返回有限的非负倍率。插件把 `chance * 返回值` 限制在 `0..1` 后再交给核心掉落抽取器；规则异常或返回非法值时保留原始掉落概率，并通过 TabooLib I18n 记录英文错误。

## 快照和失败隔离

技能、规则函数和怪物 YAML 分别解析为不可变快照。某个规则文件或怪物文件失败时，合法文件继续进入新快照；完整重载失败时旧快照继续生效。规则计算器只读取当前快照，不在运行时重新解析文件。
