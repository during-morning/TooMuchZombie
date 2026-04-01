# ZombieGame AI 系统完整移植计划

## 当前问题分析

### 1. 僵尸只会横向搭建，不会准确搭到玩家处
**原因**: 
- 当前 `ZombieBuilderBehaviorV2` 使用了 PathFinder，但没有正确集成到主 AI 循环
- 缺少 ZombieGame 的 `ZombieMeleeAttackGoal` 中的建造触发逻辑
- 没有实现垂直路径规划（向上爬墙）

**ZombieGame 的实现**:
- `ZombieMeleeAttackGoal` 在攻击时检测障碍物
- 自动触发 `ZombiePathBuildingGoal.startBuild(targetPos)`
- PathFinder 自动识别高度变化（UP/DOWN/NONE）

### 2. TNT 僵尸开路未实装
**原因**:
- `ZombieSuicideBehavior` 只实现了自爆，没有实现 TNT 投掷
- `CombatBehavior.throwTNT()` 存在但未与 Builder 协作
- 缺少 ZombieGame 的爆破协调机制

**需要实现**:
- TNT 僵尸在遇到障碍时投掷 TNT
- TNT 爆炸后其他僵尸通过缺口
- 爆破请求系统（已有但未完整使用）

### 3. 群体协作功能未完整移植
**当前状态**:
- ✅ `ZombieCooperationBehavior` 已实现基础协作
- ❌ 缺少 ZombieGame 的 `callToAttack()` 群体召唤
- ❌ 缺少盾牌协作（`ZombieShieldHelpingGoal`）
- ❌ 缺少受伤时的群体响应
- ❌ 缺少随机游走时的避让机制

**ZombieGame 的协作机制**:
```java
// 1. 受伤时召唤附近僵尸
public void zombieHurt() {
    this.hurtTargetAddingGoal.goal.onZombieHurt();
}

// 2. 攻击时定期召唤
if ((this.zombie.getLevel().getGameTime() - this.getLastHurtTime()) % 400 == 5) {
    this.callToAttack(this.zombie.getTarget());
}

// 3. 避让机制
if (zombie_.getEyePosition().distanceTo(this.zombie.getEyePosition()) <= 2d) {
    this.zombieRandomWalkGoal.goal.startRandomWalking(5L, true);
}
```

### 4. 性能问题
**原因**:
- 每个僵尸独立运行完整 AI 循环
- 没有实现 ZombieGame 的分批处理机制
- 缺少智能休眠（远离玩家时降低更新频率）

**ZombieGame 的优化**:
- 使用 `requiresUpdateEveryTick()` 控制更新频率
- 基于距离的动态更新间隔
- 共享计算结果（如目标选择）

## 完整移植方案

### 阶段 1: 修复建造系统 ✅ 优先级最高
1. 创建 `ZombieMeleeAttackBehavior` - 移植 `ZombieMeleeAttackGoal`
2. 实现建造触发逻辑（检测障碍物）
3. 集成 PathFinder 到攻击行为
4. 实现垂直路径规划

### 阶段 2: 完善 TNT 系统
1. 实现 TNT 投掷与建造的协作
2. 完善爆破请求系统
3. 实现爆破后的路径利用

### 阶段 3: 完整群体协作
1. 移植 `callToAttack()` 机制
2. 实现盾牌协作系统
3. 实现受伤响应机制
4. 实现避让和队形保持

### 阶段 4: 性能优化
1. 实现分批处理
2. 实现智能休眠
3. 优化目标选择
4. 实现计算结果共享

## 核心算法对比

### ZombieGame 的 AI 架构
```
ZombieMainGoal (主控制器)
├── ZombieTargetChoosingGoal (目标选择)
├── ZombieMeleeAttackGoal (近战攻击 + 建造触发)
├── ZombiePathBuildingGoal (路径建造)
├── ZombieBreakGoal (方块破坏)
├── ZombieShieldUsingGoal (盾牌使用)
├── ZombieShieldHelpingGoal (盾牌协作)
├── HurtTargetAddingGoal (受伤响应)
└── ZombieRandomWalkGoal (随机游走)
```

### TooMuchZombies 当前架构
```
ZombieAgent (代理)
├── TargetCoordinator (目标选择) ✅
├── SmartPathingBehavior (移动) ✅
├── CombatBehavior (战斗) ⚠️ 部分实现
├── ZombieBuilderBehaviorV2 (建造) ⚠️ 未集成
├── ZombieBreakerBehavior (破坏) ✅
├── ZombieSuicideBehavior (自爆) ⚠️ 缺少 TNT
└── ZombieCooperationBehavior (协作) ⚠️ 部分实现
```

### 缺失的关键组件
1. ❌ `ZombieMeleeAttackBehavior` - 近战攻击逻辑
2. ❌ 建造触发机制 - 检测障碍物并启动建造
3. ❌ 群体召唤系统 - `callToAttack()`
4. ❌ 盾牌协作系统
5. ❌ 受伤响应机制
6. ❌ 避让机制

## 实施步骤

### Step 1: 创建 ZombieMeleeAttackBehavior
移植 `ZombieMeleeAttackGoal` 的核心逻辑：
- 攻击距离判断
- 障碍物检测
- 建造触发
- 攻击执行

### Step 2: 集成建造系统
- 在 `ZombieAgent` 中添加 `meleeAttackBehavior`
- 在 `ZombieAIManager.executeBehavior()` 中调用
- 实现建造触发条件检查

### Step 3: 实现群体协作
- 移植 `callToAttack()` 方法
- 实现受伤时的群体召唤
- 实现攻击时的定期召唤

### Step 4: 性能优化
- 实现更新频率控制
- 实现智能休眠
- 优化计算密集型操作

## 预期效果

### 修复后的行为
1. ✅ 僵尸能够准确搭建到玩家位置（包括垂直方向）
2. ✅ TNT 僵尸能够炸开障碍物
3. ✅ 僵尸之间有明显的协作行为
4. ✅ 性能提升 40-60%

### 与 ZombieGame 的对比
| 功能 | ZombieGame | TooMuchZombies (修复后) |
|------|-----------|------------------------|
| 路径规划 | ✅ PathFinder | ✅ PathFinder (完整移植) |
| 垂直建造 | ✅ 自动识别 | ✅ 自动识别 |
| TNT 开路 | ✅ 协作爆破 | ✅ 协作爆破 |
| 群体召唤 | ✅ callToAttack | ✅ callToAttack |
| 盾牌协作 | ✅ 智能使用 | ✅ 智能使用 |
| 性能优化 | ✅ 分批处理 | ✅ 分批处理 |

## 开始实施
优先级: 阶段 1 > 阶段 3 > 阶段 2 > 阶段 4
