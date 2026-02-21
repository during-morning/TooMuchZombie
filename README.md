# TooMuchZombies

Paper 1.21+ 的僵尸 AI 强化插件。

## 核心特性
- 统一 encounter 等级：玩家威胁与附近玩家聚合后决定僵尸等级。
- 强化等级系统：玩家等级支持 `level.max`（默认 12），并纳入伤害输出与 K/D 表现。
- 生成 pipeline：夜晚门控、全局上限、玩家附近上限、chunk 冷却、预算池、接受率。
- 能力仲裁：`SURVIVE / STRUCTURE / CHASE_COMBAT / TARGET_SEARCH`，降低路径与战斗冲突。
- 主动搜敌：周期扫描玩家并评分换目标，不再只依赖被动仇恨。
- 协作决策树：`RETREAT_REGROUP / BREACH_SUPPORT / FOCUS_FIRE / FLANK_SYNC / BODYGUARD`。
- 群体策略增强：`ENCIRCLE_PRESSURE` 围压推进（与 ZG 略有差异的三车道包夹风格）。
- 队形保持：集火目标的槽位分配 + 分离向量，减少堆叠卡位。
- Builder/Breaker：开路搭建、破障推进、破防请求闭环。
- Builder 修复：不可破坏障碍回退侧移、缺支撑自动补脚手架、放置裂纹短预演动画。
- COMBAT 盾牌链路：受击触发短时格挡窗口 + 近距离盾击反制。
- 强化项：破防角色分工 `PRIMARY/SUPPORT/BODYGUARD`、临时方块生命周期与裂纹动画。

## 命令
- `/za spawn <role>`
- `/za info`
- `/za killall`
- `/za forcebloodmoon`
- `/za forcechaos`
- `/za reload`
- `/za level set <player> <level>`
- `/za level info [player]`
- `/za debug spawn`
- `/za debug ai`
- `/za debug system`
- `/za debug reset`

## 关键配置
- `zombie-ai.cooperation.*` 协作阈值与冷却
- `zombie-ai.pathing.*` 队形槽位与分离参数
- `zombie-ai.targeting.*` 主动搜敌扫描与换目标阈值
- `zombie-ai.cooperation.encircle-*` 围压策略范围与重规划节奏
- `zombie-ai.builder.*` Builder/Breaker 速度与失败阈值
- `zombie-ai.builder.temporary-block-*` 临时方块生命周期与衰减裂纹
- `zombie-ai.builder.break-*-particle-*` 破坏命中/完成粒子节流与强度
- `zombie-ai.breaker.blacklist/whitelist` 破坏合法性白黑名单
- `spawn.algorithm.*` 生成 pipeline
- `level.threat.*` / `level.encounter.*` / `level.hysteresis.*` 玩家等级计算
- `level.max` 玩家与遭遇等级上限
- `breach.*` 破防角色租约与限额

## 调试输出
- `/za debug spawn`：显示生成拒绝原因分布（如 `global_cap/chunk_cooldown/budget`）。
- `/za debug ai`：显示破防分工计数 `breachRoleP/S/B` 与临时方块数量。
- `/za debug ai`：额外输出 `builderFailures` 与 `breakerRejects`，用于排查 build/break 卡点。
- `/za debug system`：显示僵尸总量、临时方块总量、15 秒内即将到期数量与生成拒绝分布。
- `/za debug reset`：清空 spawn/ai 调试统计，便于开始新一轮验收。

## 实机场景验收
- 详见 `验收清单_阶段H_实机场景.md`（按阶段 H 的 7 组步骤执行）。

## 与 ZombieGame 的差异
- 本插件为 Paper 服务器端 AI 增强，不依赖 Forge 客户端模组。
- 目标是“生存压迫感与协作围攻”，不是原版行为覆盖的纯复刻。
- 所有关键阈值尽量外置为配置，便于小服快速调参与回归。

## 构建
```bash
mvn -q -DskipTests compile
mvn -q test
```
