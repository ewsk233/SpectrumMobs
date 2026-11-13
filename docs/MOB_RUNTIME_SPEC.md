# 怪物运行时规范

## 身份标记

生成的实体使用 PersistentDataContainer 保存两个字段：

- `spectrummobs:mob_definition`：怪物资源 ID。
- `spectrummobs:mob_instance`：本次生成的 UUID。

事件监听器只处理带有这些身份字段的实体，因此不会把服务器中的普通原版实体当成 SpectrumMobs 怪物。

## 生成

```text
/spectrummobs mob spawn spectrum:ember_guardian
```

生成流程会解析实体类型、设置显示名称、最大生命值、攻击伤害、移动速度、跟随距离、击退抗性和装备，然后写入身份标记。

SpectrumMobs 生成的受管实体会设置为持久实体，并关闭 `removeWhenFarAway`，避免实体因为玩家距离过远而按原版规则自然消失。区块卸载时实体仍可能暂时离开内存，区块重新加载后由服务器恢复；实体死亡或被其他插件明确移除时不会被恢复。

通过 `mechanic.mob.summon` 生成的小兵会在 PDC 中记录召唤者 UUID。召唤者的 `summons.max-active` 限制同时存在的小兵数量；当 `summons.cleanup-on-death` 开启时，召唤者死亡、被管理命令移除或世界卸载会清理这些小兵。

怪物还可以使用 `behavior` 绑定周期行为技能，使用 `target-rules` 绑定威胁目标计算器，使用 `damage-rules` 绑定伤害倍率规则。完整语义见 [威胁表、行为策略与规则计算器](THREAT_BEHAVIOR_SPEC.md)。

`movement` 使用通用移动控制器按 Tick 更新实体速度；`model.provider: display` 会创建可清理的 TextDisplay 视觉附属实体。视觉实体不参与生命值和受伤判定，世界卸载、实体移除和死亡时会清理。

## 事件触发

怪物定义中的事件绑定使用资源 ID：

```yaml
events:
  damaged:
    - spectrum:flame_nova
    - skill: spectrum:flame_nova
      args:
        radius: 8
        damage: 20
```

带有身份标记的实体受到未取消伤害时，监听器查找当前配置快照中的 `damaged` 技能并交给技能运行时。技能冷却和 `requires` 条件仍由运行时统一处理。

伤害触发还会传递以下脚本上下文：

- `caster`：受到伤害的 SpectrumMobs 实体。
- `target`：造成伤害的实体；环境伤害没有目标时使用默认上下文。
- `event.name`：`damaged`。
- `event.damage`：本次事件的伤害值。

施法者死亡、从世界移除、世界卸载或插件关闭时，相关等待中的技能会根据 `config.yml` 的取消开关清理。

事件对象的 `args` 会在同一个技能快照中绑定到技能参数；缺失、未知或类型错误只拒绝本次技能实例，不会消耗冷却。`intervals[].skills` 使用相同的参数绑定规则。

受管召唤物死亡时，若存在有效召唤者，会向召唤者触发 `summon_death`，事件目标是死亡的召唤物；这用于通用的召唤物联动，不包含地牢房间逻辑。

脚本召唤同时记录主人、批次和技能执行 UUID。`targeter.summons` 按归属查询，`condition.summons.defeated` 仅认可完整批次的真实死亡。超时、主动清理及区块卸载标记为移除，不触发 `summon_death`。脚本效果所有权、临时标签与升级迁移见 [召唤与临时资源规范](SUMMON_LIFECYCLE_SPEC.md)。

## Boss 阶段

带有 `boss.phases` 的怪物会在生成时写入当前阶段，并在伤害事件中根据预计生命值切换阶段。阶段阈值是生命值比例，越低的阈值表示越晚进入的阶段。阶段切换会：

- 把当前阶段写入 `spectrummobs:mob_phase`；
- 应用阶段显示名称和属性覆盖；
- 执行旧阶段的 `on-exit` 技能，再执行新阶段的 `on-enter` 技能；
- 通知已注册的外部集成。

阶段技能会获得 `phase` 文本变量，以及 `event.name`（`phase_enter` 或 `phase_exit`）和 `event.damage`。

## 掉落与模型

`drops` 在 `EntityDeathEvent` 中按概率追加到死亡掉落列表。材质由 Bukkit 平台层解析，非法材质只记录日志，不会中断死亡事件。

`model` 会写入实体 PDC 的 `model_provider` 和 `model_id`，并通过 `MobModelIntegration` 回调交给外部插件。SpectrumMobs 不直接依赖 ModelEngine、MythicMobs 等具体实现。

外部插件在自身启用时注册适配器：

```kotlin
MobIntegrationRegistry.register(object : MobModelIntegration {
    override val id = "example-model-plugin"
    override fun supports(model: MobModel) = model.provider == "example"
    override fun attach(context: MobSpawnContext, model: MobModel) {
        // 调用外部插件 API 绑定 model.id
    }
})
```
