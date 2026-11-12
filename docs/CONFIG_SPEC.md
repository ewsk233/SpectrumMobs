# 配置规范

## 文件布局

```text
config.yml
mobs/*.yml
skills/**/*.sms
lang/*.yml
```

掉落、Boss 阶段和模型绑定使用怪物文件中的声明字段；复杂行为仍然写在 `.sms` 技能中。

命令白名单示例：

```yaml
commands:
  allow-raw: false
  max-raw-length: 256
  definitions:
    reward_player:
      executor: console
      template: "give {target.name} diamond 1"
```

## 怪物资源

每个怪物使用一个资源 ID，例如 `spectrum:ember_guardian`。资源 ID 由命名空间和路径组成，比较时使用小写形式。

```yaml
schema-version: 1
id: spectrum:ember_guardian
type: WITHER_SKELETON

display:
  name: "&6熔火守卫"
  name-visible: true

attributes:
  max-health: 300.0
  attack-damage: 10.0

events:
  damaged:
    - spectrum:flame_nova
    - skill: spectrum:flame_nova
      args:
        radius: 8
        damage: 20
        windup: 2s

intervals:
  - id: heartbeat
    period: 10s
    skills:
      - skill: spectrum:flame_nova
        args:
          radius: 6
          damage: 14

behavior:
  skill: spectrum:boss_behavior
  period: 10t

movement:
  controller: hover
  hover-height: 3.0
  target-distance: 8.0
  speed: 0.25
  update-period: 1t

target-rules:
  threat: spectrum:combat.select_threat

damage-rules:
  incoming: spectrum:combat.incoming
  outgoing: spectrum:combat.outgoing

boss:
  enabled: true
  phases:
    - id: normal
      health-threshold: 1.0
    - id: enraged
      health-threshold: 0.5
      display-name: "&c熔火守卫（狂暴）"
      attributes:
        attack-damage: 20.0
        movement-speed: 0.34
      on-enter:
        - spectrum:flame_nova

drops:
  - material: DIAMOND
    amount: 2
    chance: 0.35
    rule: spectrum:combat.drop_multiplier

model:
  provider: modelengine
  id: ember_guardian
```

## 设计规则

- `type` 只保存原始实体类型文本，由平台层解析。
- 属性、装备和显示信息属于 YAML 声明数据。
- 技能逻辑只在 `.sms` 中定义。
- 技能冷却由技能自身负责，怪物事件只负责绑定触发器。
- `events.attack` 会在受管怪物造成实体伤害时触发；`intervals` 使用每个实体独立的周期计时器。
- `intervals.period` 支持整数 tick 或时长文本，例如 `200t`、`10s`；服务器卡顿时不会补发多次间隔技能。
- `boss.phases` 的 `health-threshold` 是生命值比例，必须包含 `1.0` 的初始阶段；进入和离开阶段分别触发 `phase_enter` 与 `phase_exit` 技能。
- `drops` 只追加 SpectrumMobs 声明的自定义掉落，`chance` 范围为 `0.0` 到 `1.0`。
- `model` 只声明提供方和模型 ID；具体模型插件通过外部集成接口接入，不写入 core 或脚本模块。
- 通过 SpectrumMobs 生成的受管实体默认持久化，并关闭远距离自然移除；这两个行为属于生成服务的身份保证，不由怪物 YAML 重复声明。
- `config.yml` 的 `engine.max-state-entries` 限制单个施法者可以保存的状态项数量，`engine.max-query-targets` 限制一次范围查询返回的玩家数量。
- `config.yml` 的 `engine.max-active-projectiles` 限制同时存在的方块投射物数量。
- 怪物 YAML 的 `summons.max-active` 限制该怪物同时拥有的小兵数量，`summons.cleanup-on-death` 控制 Boss 死亡或移除时是否清理小兵。
- 重载按文件收集合法配置并一次性替换快照；错误文件会报告诊断并跳过，其他合法文件仍会正常加载；如果没有任何合法文件，则继续使用旧快照。
- 插件重载时先建立技能和规则快照，再校验怪物事件、周期、行为、阶段技能、参数以及目标/伤害/掉落规则引用；引用错误的怪物文件会单独跳过。
- `commands.allow-raw` 默认是 `false`；打开后 `command.raw` 可以以控制台身份执行任意命令，插件启动时会输出警告。
- `commands.definitions` 是安全命令模板白名单。模板只能使用 `command.run(id, target)` 调用，并支持 `target`、`target.name`、`target.uuid`、`target.world`、`target.x`、`target.y` 和 `target.z` 占位符。
- `events` 和 `intervals.skills` 既支持资源 ID 字符串，也支持 `skill` 与 `args` 对象；参数类型和默认值由对应 `.sms` 技能声明负责校验。
- `behavior` 为每个受管实体绑定独立周期行为技能；`period` 使用 tick 时长，行为触发上下文的事件名为 `behavior`。
- `movement` 为实体绑定通用移动控制器，例如 `controller: hover`，并可配置 `hover-height`、`target-distance`、`speed` 和 `update-period`。
- `target-rules.threat` 接受返回 `Entity` 的只读 `fn`，函数参数为 `candidates: List<Entity>`，用于从威胁候选中选择目标。
- `damage-rules.incoming/outgoing` 接受返回伤害倍率的只读 `fn`；掉落项的 `rule` 接受返回掉落概率倍率的只读 `fn`。规则资源 ID 必须指向 `namespace:module.function`，完整契约见 `docs/THREAT_BEHAVIOR_SPEC.md`。
- 未知字段在严格模式下产生诊断信息。
