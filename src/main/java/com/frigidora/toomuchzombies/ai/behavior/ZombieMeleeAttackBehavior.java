package com.frigidora.toomuchzombies.ai.behavior;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAgent;
import com.frigidora.toomuchzombies.config.ConfigManager;

/**
 * 僵尸近战攻击行为 - 从 ZombieGame 的 ZombieMeleeAttackGoal 完整移植
 * 
 * 核心功能：
 * 1. 近战攻击逻辑
 * 2. 路径规划失败时触发建造
 * 3. 障碍物检测
 * 4. 跳跃逻辑
 */
public class ZombieMeleeAttackBehavior {
    
    private final ZombieAgent agent;
    private final Zombie zombie;
    private final ZombieBuilderBehaviorV2 builderV2;
    
    private double targetX, targetY, targetZ;
    private int ticksUntilNextPathRecalculation;
    private int ticksUntilNextAttack;
    private long lastCanUseCheck;
    
    // 建造触发状态
    private boolean startBuilding = false;
    private long startBuildingTime = -1000L;
    private long lastStopBuildingTime = 0L;
    
    public ZombieMeleeAttackBehavior(ZombieAgent agent, ZombieBuilderBehaviorV2 builderV2) {
        this.agent = agent;
        this.zombie = agent.getZombie();
        this.builderV2 = builderV2;
    }

    
    public boolean canBeUsed() {
        // 如果正在使用远程武器，不使用近战
        if (agent.getRole() == com.frigidora.toomuchzombies.enums.ZombieRole.ARCHER) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        if (now - lastCanUseCheck < 1000L) { // 1秒检查一次
            return false;
        }
        lastCanUseCheck = now;
        
        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        
        // 检查目标是否可攻击
        if (target instanceof Player) {
            Player player = (Player) target;
            GameMode mode = player.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                return false;
            }
        }
        
        return true;
    }

    
    public void tick() {
        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isValid() || target.isDead()) {
            stop();
            return;
        }
        
        // 更新目标位置
        targetX = target.getLocation().getX();
        targetY = target.getLocation().getY();
        targetZ = target.getLocation().getZ();
        
        // 让僵尸看向目标
        zombie.lookAt(target);
        
        // 计算距离
        double distSq = zombie.getLocation().distanceSquared(target.getLocation());
        
        // 路径重新计算
        ticksUntilNextPathRecalculation = Math.max(ticksUntilNextPathRecalculation - 1, 0);
        
        if (ticksUntilNextPathRecalculation == 0) {
            ticksUntilNextPathRecalculation = 4 + (int)(Math.random() * 7);
            
            // 根据距离调整重新计算频率
            if (distSq > 1024.0) { // > 32 格
                ticksUntilNextPathRecalculation += 10;
            } else if (distSq > 256.0) { // > 16 格
                ticksUntilNextPathRecalculation += 5;
            }
            
            // 尝试寻路
            boolean pathSuccess = tryNavigateToTarget(target, distSq);
            
            // 核心：路径失败时触发建造
            if (!pathSuccess && !startBuilding) {
                checkAndTriggerBuilding(target, distSq);
            }
        }
        
        // 处理建造触发
        if (startBuilding) {
            handleBuildingTrigger(target);
        }
        
        // 攻击逻辑
        ticksUntilNextAttack = Math.max(ticksUntilNextAttack - 1, 0);
        checkAndPerformAttack(target, distSq);
    }

    
    /**
     * 尝试导航到目标
     * @return 是否成功创建路径
     */
    private boolean tryNavigateToTarget(LivingEntity target, double distSq) {
        // 如果正在建造或游走，不导航
        if (agent.isBuilding() || agent.isWalking()) {
            return false;
        }
        
        Location targetLoc = target.getLocation();
        
        // 使用 NMS 导航
        if (TooMuchZombies.getNMSHandler() != null) {
            double speed = 1.0;
            
            // 白天减速
            long time = zombie.getWorld().getTime();
            if (time >= 0 && time < 12000) {
                speed *= 0.6;
            }
            
            TooMuchZombies.getNMSHandler().moveTo(zombie, targetLoc, speed);
            return true;
        }
        
        return false;
    }

    
    /**
     * 检查并触发建造 - ZombieGame 的核心算法
     */
    private void checkAndTriggerBuilding(LivingEntity target, double distSq) {
        long now = System.currentTimeMillis();
        
        // 检查冷却时间（12秒）
        if (now - lastStopBuildingTime < 12000L) {
            return;
        }
        
        // 检查距离（20格内）
        if (distSq > 400.0) {
            return;
        }
        
        // 触发建造准备
        setBuild();
    }

    
    /**
     * 设置建造状态 - 移植自 ZombieGame
     */
    private void setBuild() {
        if (startBuilding) {
            return;
        }
        
        // 先随机移动一下，避免卡在原地
        Vector randomOffset = new Vector(
            (Math.random() - 0.5) * 6,
            0,
            (Math.random() - 0.5) * 6
        );
        
        Location randomLoc = zombie.getLocation().clone().add(randomOffset);
        randomLoc.setY(zombie.getLocation().getY());
        
        if (TooMuchZombies.getNMSHandler() != null) {
            TooMuchZombies.getNMSHandler().moveTo(zombie, randomLoc, 0.7);
        }
        
        startBuildingTime = System.currentTimeMillis();
        startBuilding = true;
    }

    
    /**
     * 处理建造触发 - 等待4秒后启动建造
     */
    private void handleBuildingTrigger(LivingEntity target) {
        long now = System.currentTimeMillis();
        
        // 等待 4 秒（80 ticks = 4000ms）
        if (now - startBuildingTime >= 4000L) {
            // 启动建造
            Location targetPos = target.getLocation().clone();
            targetPos.setY(targetPos.getY() - 1); // 目标脚下
            
            builderV2.setActive(true);
            
            startBuilding = false;
            lastStopBuildingTime = now;
        }
    }

    
    /**
     * 取消建造
     */
    public void cancelBuild() {
        startBuilding = false;
    }

    
    /**
     * 检查并执行攻击
     */
    private void checkAndPerformAttack(LivingEntity target, double distSq) {
        double attackReachSq = getAttackReachSqr(target);
        
        if (distSq <= attackReachSq && ticksUntilNextAttack <= 0) {
            // 如果正在使用物品，先停止
            try {
                if (zombie.getHandRaised() != null) {
                    zombie.clearActiveItem();
                }
            } catch (Exception ignored) {
                // 某些版本可能没有这个方法
            }
            
            // 重置攻击冷却
            resetAttackCoolDown();
            
            // 挥手动画
            zombie.swingMainHand();
            
            // 执行攻击
            zombie.attack(target);
            
            // 通知其他行为
            agent.onZombieAttack();
        }
    }

    
    /**
     * 重置攻击冷却
     */
    private void resetAttackCoolDown() {
        ticksUntilNextAttack = 20; // 1秒冷却
    }

    
    /**
     * 获取攻击范围
     */
    private double getAttackReachSqr(LivingEntity target) {
        float zombieWidth = (float) zombie.getWidth();
        float targetWidth = (float) target.getWidth();
        return (zombieWidth * 2.2F * zombieWidth * 2.2F) + targetWidth;
    }

    
    /**
     * 停止攻击
     */
    public void stop() {
        zombie.setTarget(null);
        startBuilding = false;
    }

    
    public boolean isStartBuilding() {
        return startBuilding;
    }
    
    public long getLastStopBuildingTime() {
        return lastStopBuildingTime;
    }
}
