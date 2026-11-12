# 架构设计

## 总体结构

```text
YAML / Spectrum Script
          │
          ▼
配置加载器 / Script 编译器
          │
          ▼
      Typed IR
          │
          ▼
技能运行时与 Tick 调度器
          │
          ▼
TabooLib 平台适配层
          │
          ▼
实体、世界、事件和其他插件
```

## `core`

保存资源 ID、怪物定义、技能定义、诊断信息和与服务器无关的值对象。该模块可以在普通 JVM 测试环境运行。

## `script`

负责 Lexer、Parser、语义分析和 Typed IR。编译器不能访问 Bukkit 对象。脚本中的 `caster`、`Player` 和 `Position` 都先表示为语言层类型，执行时再由平台上下文提供实际对象。

## `plugin`

负责 TabooLib 生命周期、YAML 文件、事件、命令和 Tick 调度。它把 Typed IR 交给运行时，并通过注册表把 `mechanic.damage`、`mechanic.ignite`、粒子等能力绑定到 Minecraft。

## 运行时原则

技能执行实例必须可暂停、恢复和取消。`wait 30t` 只保存恢复时间，不阻塞线程。所有实体访问都在平台允许的调度上下文中执行。

配置重载采用快照替换：扫描结果先按文件解析，合法文件组成完整候选快照后一次性替换；单个文件错误会报告诊断并跳过该文件，不会阻塞其他合法配置。若本次没有任何合法候选，旧快照保持不变。

插件层的用户可见消息统一通过 TabooLib I18n 语言节点输出。核心和脚本模块不依赖平台 API，只返回英文错误诊断；插件层负责将配置诊断和 Script 编译诊断渲染为 I18n 消息。

## 技能运行时

`script` 模块中的 `SkillScheduler` 只处理 Typed IR 和抽象运行时值。插件层每个游戏 tick 调用一次 `tick`，运行时根据恢复时间继续执行等待中的实例。

`ScriptApiRegistry` 是签名的权威来源，`ScriptApiBindings` 把签名与 `RuntimeActionRegistry` 的实现组成同一份快照。Targeter、Condition 和 Mechanics 必须显式注册；编译器不再硬编码第二份服务类型或签名。插件通过 `RuntimeEntityResolver` 提供实体当前状态，脚本模块只接触抽象值。

扩展使用 `SpectrumMobs.registerScriptApi` 追加签名与实现。发布前校验重复、命名空间冲突和实现完整性；已运行技能继续使用原绑定快照。脚本不能直接访问 Bukkit、反射调用 Java 类或执行未注册接口。

每个实例都受到单次恢复步数、循环次数和调用深度限制；调度器还限制全局活动作用域数量。执行失败生成失败事件并终止任务；等待任务保留到目标 tick。正常完成后仍有资源或联动后代的作用域继续保留，取消和失败按技能策略清理，资源与后代全部释放后回收记录。

施法组与策略由技能 `.sms` 声明，冷却使用完整资源 ID。子调用保留父、根和深度，调度器负责取消传播及效果清理回调；服务器操作仍由插件执行。具体语义见 [施法管理规范](CASTING_SPEC.md)。

技能参数和规则模块在加载时编译到同一快照。参数绑定发生在技能条件、冷却和施法组检查之前；规则函数编译为只读 Typed IR，在调用时同步执行并共享规则步数预算。YAML 事件与周期触发只提供调用实参，不复制技能声明。

## Boss、掉落和外部集成

Boss 阶段状态由插件层根据实体预计生命值维护，阶段 ID 写入 PDC；阶段切换只通过显式的 `phase_enter` 和 `phase_exit` 技能进入脚本运行时。掉落概率计算在 `core` 完成，材质和 Bukkit 掉落列表操作留在 `plugin`。

模型和其他外部插件通过 `MobIntegration` 注册表接入。`MobModelIntegration` 只处理声明了匹配模型提供方的怪物，避免 core、script 对 ModelEngine 或其他具体插件产生依赖。

命令动作由插件层的 `CommandPolicy` 管理。`command.run` 只能调用配置白名单模板；`command.raw` 是单独的高风险开关，默认关闭，开启时在插件启动日志中警告服主。
