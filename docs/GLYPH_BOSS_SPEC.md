# 遗文聚合体·缄言

项目内置的 `spectrum:manuscript_silence` 是一个使用通用能力组合出来的世界 Boss 示例。它不需要地牢房间、竞技场实例或副本管理系统。

## 通用能力组合

- `movement.controller: hover`：按威胁目标保持高度和距离。
- `model.provider: display`：使用原生 TextDisplay、粒子和资源包字体形成旋转文字视觉；旧版本没有 TextDisplay 时战斗实体仍然可以运行。
- `mechanic.ray.damage`：实现断章射线的持续线段碰撞。
- `mechanic.projectile.homing`：实现错字追踪投射物。
- `mechanic.particles.circle/line`：实现范围和直线预警。
- `mechanic.entity.pull/push`：实现段落重组和爆炸击退。
- `mechanic.mob.summon(..., tag = ...)` 与 `targeter.entities.tagged`：实现文字碑、句核和分身关联。
- 阶段、威胁表、伤害规则和周期行为：由现有 Boss 运行时统一处理。

## 使用

```text
/spectrummobs mob spawn spectrum:manuscript_silence
```

主要资源位于：

- `plugin/src/main/resources/mobs/manuscript_silence.yml`
- `plugin/src/main/resources/mobs/manuscript_*.yml`
- `plugin/src/main/resources/skills/spectrum/manuscript_*.sms`
- `plugin/src/main/resources/skills/spectrum/manuscript_rules.sms`

视觉由 `display` 提供方管理。服务器可以为资源包字体替换字符，也可以把同一套 `MobModelIntegration` 换成外部模型提供方。Boss 的生命值、威胁、阶段和受伤事件仍然属于 Combat Entity；TextDisplay 只是可清理的视觉附属实体。

当前实现聚焦世界 Boss 所需的战斗能力，暂不加入地牢房间、队伍匹配、实例副本、房间生成和副本进度等系统。
