# 召唤结果、批次归属与临时资源

## 统一结果

`mechanic.skills.cast` 和 `mechanic.mob.summon` 返回 `OperationResult`。编译签名、成员类型和运行返回值都经过检查，不再用空字符串或数字同时表达成功和拒绝。

| 成员 | 类型 | 含义 |
| --- | --- | --- |
| `success` | `Boolean` | 本次操作完整成功；召唤要求实际数量等于请求数量 |
| `id` | `String` | 施法执行 UUID，或召唤批次 UUID；施法被拒绝时为空 |
| `count` | `Number` | 启动实例数或本次实际生成数量 |
| `requested` | `Number` | 施法固定为 1，召唤为请求数量 |
| `code` | `String` | 稳定的机器可读结果码 |
| `reason` | `String` | 英文原因；成功时为空 |

数量是调用时快照，不随实体死亡变化。不要用 `batch.count == 0` 或查询为空判断是否成功破解机制。

`ignoreRejected` 默认为 `false`。设为 `true` 时，仅以下预期拒绝会作为 `success = false` 的结果返回：

| 结果码 | 含义 |
| --- | --- |
| `cooldown` | 技能仍在冷却 |
| `group_busy` | 施法组占用 |
| `condition_failed` | 技能前置条件未满足 |
| `capacity` | 活动执行作用域或召唤物达到上限 |

`missing_definition`、`invalid_argument`、`world_unavailable`、`spawn_failed`、`start_failed`、`parent_inactive` 和 `depth_limit` 属于真正错误，不能通过此开关忽略。子技能在启动后发生的执行错误仍由自己的执行实例报告。

平台召唤只调用一次返回完整 `MobSpawnResult` 的后端，不再先取得实体、失败后再尝试布尔后端。怪物定义、世界与实体类型先于容量校验，因此容量满额不会掩盖缺失定义。

严格调用被拒绝时会清理本次部分生成的实体；允许预期拒绝时，部分生成结果交给脚本处理。真正错误会清理本批实体并报告原始原因。运行错误通过 TabooLib I18n 的 `error-runtime-execution` 输出，触发器启动错误通过 `error-skill-start` 输出；`debug.trace-skills` 开启后还会记录 `debug-summon-result`。

## 归属与查询

每次召唤创建独立批次，在实体 PDC 中记录：

- `spectrummobs:summon_owner`：主人实体 UUID。
- `spectrummobs:summon_batch`：本次批次 UUID。
- `spectrummobs:summon_execution`：创建批次的技能执行 UUID。
- `spectrummobs:summon_cleanup`：程序清理标记，存在时不能产生战斗击破结果。

`targeter.summons(owner, batch?, tag?)` 查询主人当前已加载、有效的召唤物，不主动加载区块。省略批次时选择该主人的全部召唤物；传入批次时只选择该批的存活成员；可再按标签筛选。返回值仍受目标查询数量预算约束。传入其他主人的批次，或把施法结果当作召唤批次使用，会报告英文错误。

标签描述用途，主人和批次决定归属。`targeter.entities.tagged` 仍是普通空间查询，不提供归属隔离。两个相邻、同种且使用相同标签的 Boss 需要使用 `targeter.summons`，避免相互干扰。

`condition.summons.defeated(owner, batch)` 仅在本批完整生成，且全部成员都收到真实死亡事件时为 `true`。零生成、部分生成、超时、主动移除、失效或卸载均不能算作击破；死亡之后到达的移除事件不会覆盖死亡记录。此判定直接使用完整批次记录，不受 Targeter 返回数量上限影响。终止记录由脚本结果持有，活动索引在资源结束时释放，不设置无限增长的完成批次缓存。

## 生命周期

`mechanic.mob.summon(id, at, count?, tag?, ignoreRejected?, lifetime?)` 的 `count` 默认为 1，要求为 `1..64` 的整数；不截断非法数量。YAML `summons.max-active` 继续限制单个主人同时拥有的全部召唤物。

`lifetime` 默认为 `0t`，表示不设置独立到期时间；正时长从实际召唤 tick 起计算。批次登记创建技能的效果所有权：

- 程序正常完成后，存活召唤物继续存在并保留执行作用域；最后一个成员终止后释放效果。
- 默认取消、执行失败或阶段切换清理该实例的批次。
- `cleanupOnCancel = false` 保留效果，批次按寿命或自然结束释放；施法者生命周期结束、世界卸载或插件关闭仍强制回收所属资源。
- `mechanic.mob.cleanup(owner, batch)` 主动移除指定批次的存活成员，可重复调用；不触发 `summon_death`。
- 临时召唤物所在区块卸载时按移除处理；再次加载时不能重新作为活跃机制成员。服务器重新启动后不恢复旧技能执行，带旧批次标记的孤儿召唤物会在已加载世界扫描或区块加载时清理。

YAML `summons.cleanup-on-death` 提供怪物服务的主人死亡清理策略，适用于未登记技能效果的直接平台召唤等情况；它不解除已登记执行资源的强制生命周期清理。

## 临时标签与视觉

`mechanic.entity.temporaryTag(entity, tag, duration)` 添加具有正时长的临时标签，登记技能效果所有权。并发技能各自持有租约，取消或到期只释放自己的租约；最后一个租约结束后移除标签。调用前已经存在的永久标签会保留。

`mechanic.entity.addTag` 明确添加永久标签，即使存在临时租约也会保留它。`mechanic.entity.removeTag` 明确移除标签并结束该实体该标签的全部临时租约。业务中的永久标签不应该用临时标签替代。

Display 模型附属实体绑定战斗实体主人，使用非持久实体并记录 `spectrummobs:visual_owner`。主人死亡、移除、区块或世界卸载及插件关闭时释放；启动和区块加载时检查孤儿视觉。模型不是地牢场景，不引入房间或副本管理。

## 技能示例

```sms
skill break_cores {
    group = "ultimate"
    concurrency = "reject"
    let batch = mechanic.mob.summon("spectrum:manuscript_core", caster.position, 3, tag = "core", ignoreRejected = true, lifetime = 6s)
    if (batch.success) {
        for player in targeter.players.within(caster.position, 32) {
            mechanic.message.send(player, "&c五秒内击破全部核心！")
        }
        wait 5s
        if (condition.summons.defeated(caster, batch)) {
            mechanic.entity.temporaryTag(caster, "vulnerable", 4s)
        } else {
            mechanic.mob.cleanup(caster, batch)
        }
    } else {
        mechanic.mob.cleanup(caster, batch)
    }
}
```

生成不完整时不启动机制，不公告成功，不执行战斗惩罚，并清理已经生成的部分。

## 已有服务器迁移

先备份服务器的 `plugins/SpectrumMobs/skills/` 和 `mobs/`，再对照新内置示例更新实际服务器文件。更换插件 JAR 不等于迁移自定义脚本。

1. 将旧 `skills.cast(...)` 改为 `mechanic.skills.cast(...)`，没有兼容别名。
2. 将施法返回字符串的用途改为 `result.id`；判断是否启动成功使用 `result.success`。
3. 将召唤返回数量的用途改为 `result.count`；完整生成判断使用 `result.success`。
4. 将 Boss 关联实体的公共标签查询改为 `targeter.summons(caster, tag = "...")`；同一次机制必须保存并传递批次结果。
5. 将等待后手动删除的临时状态改为 `mechanic.entity.temporaryTag`，并为短期机制配置 `lifetime`。
6. 更新缄言的全部 `manuscript_*.sms`，同时更新伤害规则；它们共享同一套归属契约。迁移完成后执行 `/spectrummobs reload`，检查编译诊断。

## 验证边界

自动测试覆盖返回值类型、预期与异常拒绝、无重复召唤、部分失败回收、同主人多批次与不同主人隔离、真实死亡与程序清理、自然作用域释放、等待时阶段打断、主人与世界取消、到期、孤儿处理、重叠临时标签，以及直接执行终章脚本的零生成、部分生成、击破、移除和超时分支。内置技能与文档中的完整示例参与编译测试。

这些测试使用抽象调度器和模拟服务器对象；真实服务器的事件顺序、Display 表现和不同 Minecraft 版本仍需要实服验收。
