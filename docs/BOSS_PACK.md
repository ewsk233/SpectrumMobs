# 熔核君王 Boss 套件

项目内置一个不依赖模型插件的原版 Boss：`spectrum:magma_lord`，以及它召唤的 `spectrum:magma_minion`。两者都使用原版实体、显示名、属性、装备、粒子、声音和方块投射物完成表现。

项目还内置 `spectrum:manuscript_silence`（遗文聚合体·缄言），用于验证通用飞行、Display 视觉、射线、追踪投射物、区域预警、关联标签目标和三阶段世界 Boss 能力。它不包含地牢房间或副本实例逻辑，详细说明见 [Glyph Boss 规范](GLYPH_BOSS_SPEC.md)。

测试服务器可以使用：

```text
/spectrummobs mob spawn spectrum:magma_lord
```

熔核君王包含以下机制：

- 生成时播放进场 Title、声音、粒子和 BossBar；
- 攻击时沿施法者视线发射熔岩块投射物；
- 周期性释放带有预警的熔岩喷发，延迟后对范围内玩家造成伤害、点燃和击退；
- 周期性召唤熔岩仆从，仆从拥有独立攻击投射物和热脉冲；
- 生命值降到 70% 时进入炽热阶段，降到 40% 时进入过热阶段；
- 阶段切换会改变攻击力、移动速度、BossBar、粒子表现并追加召唤；
- 受击时会对玩家触发小额反击伤害；
- 使用原版装备和声明式掉落，不依赖 ModelEngine 或其他模型插件。
- 召唤物记录所属 Boss，最多同时存在 6 个；Boss 死亡、被管理命令移除或世界卸载时会自动清理。

持久 BossBar 在 `mobs/magma_lord.yml` 的 `bossbar` 节中声明，插件会按配置的范围和刷新周期实时同步血量；技能脚本只负责临时 Title、ActionBar 和战斗提示。

Boss 定义位于 `plugin/src/main/resources/mobs/magma_lord.yml`，技能位于 `plugin/src/main/resources/skills/spectrum/magma_lord_*.sms`。服主可以直接复制这些文件到服务器数据目录后修改生命值、阶段阈值、技能周期和掉落。

喷发、投射物与召唤技能声明 `group = "main"`、`concurrency = "reject"`，预警等待期间不再叠加其他主要攻击。阶段反馈使用 `phase/replace`；阶段切换默认取消旧阶段的施法和归属效果。可在蓄力期间用 `/spectrummobs skill active` 查询实例，再用 `/spectrummobs skill cancel <execution-uuid>` 验证停止伤害与资源清理。已有服务器需要同步更新对应 `.sms` 文件。
