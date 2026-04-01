# 声源吸引系统 - 从 ZombieGame 移植

## 系统概述

完整移植了 ZombieGame 的声源感知系统（ZombieSense），让僵尸能够感知并响应各种声音事件。

## 核心特性

### 1. 多种声音类型

| 事件类型 | 吸引范围 | 强度 | 优先级 | 说明 |
|---------|---------|------|--------|------|
| 玩家受伤 | 75格 | 1.5 | 高 | 受到伤害时发出的声音 |
| 玩家攻击 | 75格 | 1.5 | 高 | 攻击实体时发出的声音 |
| 方块破坏 | 50格 | 1.0 | 中 | 破坏方块时发出的声音 |
| 方块放置 | 30格 | 0.5 | 低 | 放置方块时发出的声音 |
| 奔跑/跳跃 | 20格 | 0.3 | 低 | 移动时发出的声音 |
| 低血量 | 40格 | 0.8 | 中 | 血量 ≤ 5 时持续散发的气味 |

### 2. 智能响应机制

```java
// 僵尸对声音的响应：
1. 无目标僵尸 → 直接设置声源为目标
2. 有目标僵尸 → 设置噪音提示，影响目标选择
3. 所有僵尸 → 设置调查目标，引导移动
```

### 3. 声音强度系统

声音强度影响：
- **持续时间**: 强度越高，噪音提示持续越久（最长 10 秒）
- **优先级**: 强度越高，在目标选择时权重越大
- **范围**: 不同事件有不同的吸引范围

## 实现细节

### 核心算法（从 ZombieGame 移植）

```java
// ZombieGame 的 callToAttack 方法
private static void callToAttack(LivingEntity entity) {
    entity.getLevel().getNearestEntity(Zombie.class,
        TargetingConditions.forNonCombat()
            .range(75d)
            .selector((zombie) -> {
                if (zombie.getTarget() == null) {
                    zombie.setTarget(entity);
                }
                return false;
            })
            .ignoreLineOfSight(),
        entity, entity.getX(), entity.getY(), entity.getZ(),
        entity.getBoundingBox().inflate(20D, 10D, 20D)
    );
}

// TooMuchZombies 的适配版本
public void attractZombies(Location location, double range, double strength, LivingEntity source) {
    Collection<Entity> nearbyEntities = location.getWorld().getNearbyEntities(
        location, range, range, range,
        entity -> entity instanceof Zombie && entity.isValid()
    );
    
    for (Entity entity : nearbyEntities) {
        Zombie zombie = (Zombie) entity;
        ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
        
        // 无目标 → 直接设置
        if (zombie.getTarget() == null && source != null) {
            zombie.setTarget(source);
        }
        
        // 设置噪音提示
        long ttl = (long) (5000 * strength);
        agent.setNoiseHint(location, ttl, strength);
        
        // 设置调查目标
        if (source != null && agent.getInvestigationTarget() == null) {
            agent.setInvestigationTarget(source.getLocation(), ttl);
        }
    }
}
```

### 与现有系统的集成

1. **TargetCoordinator**: 噪音提示影响目标选择评分
```java
// 在 evaluateTargetScore 中：
Location noiseHint = agent.getNoiseHintLocation();
if (noiseHint != null) {
    double hintDist = Math.max(1.0, target.getLocation().distance(noiseHint));
    noiseScore = noiseWeight * agent.getNoiseHintStrength() * (1.0 / hintDist);
}
```

2. **SmartPathingBehavior**: 无目标时前往调查位置
```java
Location investigationTarget = agent.getInvestigationTarget();
if (investigationTarget != null) {
    routeController.applyRouteState(agent, RouteController.RouteState.CORRIDOR, 
        ZombieAgent.PathIntent.NAV_CORRIDOR, investigationTarget, 1100L);
    agent.submitMoveIntent(investigationTarget, 1.0, 
        ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.NAV_CORRIDOR, 1100L);
}
```

## 配置选项

### config.yml 新增配置

```yaml
# 声音吸引系统
sound-attraction:
  enabled: true
  
  # 各种声音的范围和强度
  player-hurt:
    range: 75.0
    strength: 1.5
  
  player-attack:
    range: 75.0
    strength: 1.5
  
  block-break:
    range: 50.0
    strength: 1.0
  
  block-place:
    range: 30.0
    strength: 0.5
  
  player-movement:
    range: 20.0
    strength: 0.3
    only-sprinting: true
  
  low-health:
    range: 40.0
    strength: 0.8
    threshold: 5.0

# 调试选项
debug:
  sound-attraction: false  # 启用后会在日志中显示吸引信息
```

## 使用示例

### 1. 手动触发声音

```java
// 在自定义事件中触发声音吸引
Location explosionLoc = tnt.getLocation();
SoundAttractionManager.getInstance().triggerSound(explosionLoc, 100.0, 2.0);
```

### 2. 检查僵尸是否被声音吸引

```java
ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
Location noiseHint = agent.getNoiseHintLocation();
if (noiseHint != null) {
    double strength = agent.getNoiseHintStrength();
    // 僵尸正在响应声音
}
```

### 3. 清除噪音提示

```java
// 噪音提示会自动过期，也可以手动清除
agent.setNoiseHint(null, 0, 0);
```

## 性能优化

### 1. 范围限制
- 每个声音类型都有合理的范围限制
- 避免全地图搜索僵尸

### 2. 频率控制
- 移动声音：每秒最多 1 次
- 低血量：每 2 秒 1 次
- 避免频繁触发

### 3. 条件过滤
```java
// 只检查有效的僵尸
entity -> entity instanceof Zombie && entity.isValid()

// 只影响创造/旁观模式以外的玩家
GameMode mode = player.getGameMode();
return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
```

## 测试场景

### 场景 1: 玩家受伤
```
1. 玩家被怪物攻击
2. 75格范围内的僵尸被吸引
3. 无目标僵尸直接锁定玩家
4. 有目标僵尸收到噪音提示
```

### 场景 2: 玩家挖矿
```
1. 玩家破坏方块
2. 50格范围内的僵尸被吸引
3. 僵尸前往调查位置
4. 发现玩家后开始追击
```

### 场景 3: 低血量玩家
```
1. 玩家血量降至 5 以下
2. 每 2 秒吸引 40 格范围内的僵尸
3. 持续吸引直到血量恢复
4. 形成"血腥气味"效果
```

### 场景 4: 玩家奔跑
```
1. 玩家按住 Shift 奔跑
2. 每秒吸引 20 格范围内的僵尸
3. 强度较低，但持续触发
4. 适合追逐战
```

## 与 ZombieGame 的对比

| 特性 | ZombieGame | TooMuchZombies |
|------|-----------|----------------|
| 受伤吸引 | ✅ 75格 | ✅ 75格 |
| 攻击吸引 | ✅ 75格 | ✅ 75格 |
| 方块破坏 | ✅ 75格 | ✅ 50格（调整） |
| 低血量 | ✅ 持续 | ✅ 持续 |
| 移动声音 | ❌ | ✅ 新增 |
| 方块放置 | ❌ | ✅ 新增 |
| 强度系统 | ❌ | ✅ 新增 |
| 调查目标 | ❌ | ✅ 新增 |

## 已知问题和改进

### 短期改进
1. ✅ 基础声音吸引
2. ✅ 强度系统
3. ✅ 调查目标
4. ⏳ 配置化参数

### 中期改进
1. 声音传播系统（穿墙衰减）
2. 不同方块类型的声音强度
3. 群体响应（一个僵尸发现，通知附近僵尸）
4. 声音记忆（记住最近的声音位置）

### 长期改进
1. 3D 声音定位（考虑高度）
2. 声音叠加（多个声音源）
3. 环境影响（雨天、雷暴）
4. 玩家潜行减少声音

## 调试命令

```
/za debug sound on   # 启用声音吸引调试
/za debug sound off  # 禁用声音吸引调试
/za sound trigger <range> <strength>  # 手动触发声音
```

## 编译状态

✅ 编译成功，无错误

## 下一步

1. 在测试服务器上部署
2. 观察僵尸对声音的响应
3. 调整范围和强度参数
4. 收集玩家反馈
