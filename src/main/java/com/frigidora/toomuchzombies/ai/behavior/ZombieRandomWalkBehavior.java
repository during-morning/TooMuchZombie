package com.frigidora.toomuchzombies.ai.behavior;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;

/**
 * 僵尸随机游走行为 - 从 ZombieGame 完整移植
 * 
 * 核心功能：
 * 1. 无目标时的随机游走
 * 2. 避让机制（避免僵尸拥挤）
 * 3. 三种游走模式：NORMAL（随机）、AWAY（远离）、TOWARDS（靠近）
 */
public class ZombieRandomWalkBehavior {
    
    public static final int NORMAL = 0;
    public static final int AWAY = 1;
    public static final int TOWARDS = 2;
    
    private final ZombieAgent agent;
    private final Zombie zombie;
    
    private boolean isWalking = false;
    private int state = NORMAL;
    private long walkingTimeLeft = 0L;
    private boolean isMoreImportant = false;
    private boolean isSetPos = false;
    private Location targetPos = null;
    
    public ZombieRandomWalkBehavior(ZombieAgent agent) {
        this.agent = agent;
        this.zombie = agent.getZombie();
    }

    
    /**
     * 每 tick 更新
     */
    public void tick() {
        if (!isWalking) {
            // 检查是否需要避让
            checkAndAvoidCrowding();
            
            // 无目标时随机游走
            if (agent.isFree() && Math.random() < 0.05) { // 5% 概率
                startRandomWalking(5000L, false);
            }
            return;
        }
        
        walkingTimeLeft -= 50L; // 每 tick 约 50ms
        boolean shouldStop = walkingTimeLeft <= 0L;
        
        if (!agent.isBuilding() && !zombie.getPathfinder().hasPath()) {
            if (shouldStop) {
                stopWalking();
            } else {
                if (!isSetPos) {
                    Location walkTarget = calculateWalkTarget();
                    if (walkTarget != null) {
                        double speed = isMoreImportant ? 1.0 : 0.6;
                        if (TooMuchZombies.getNMSHandler() != null) {
                            TooMuchZombies.getNMSHandler().moveTo(zombie, walkTarget, speed);
                        }
                        isSetPos = true;
                    }
                }
            }
        }
        
        if (shouldStop) {
            stopWalking();
        }
    }

    
    /**
     * 计算游走目标位置
     */
    private Location calculateWalkTarget() {
        Location current = zombie.getLocation();
        Location target = null;
        
        switch (state) {
            case AWAY:
                // 远离目标位置
                if (targetPos != null) {
                    Vector away = current.toVector().subtract(targetPos.toVector()).normalize();
                    target = current.clone().add(away.multiply(10));
                }
                break;
                
            case TOWARDS:
                // 靠近目标位置
                if (targetPos != null) {
                    Vector towards = targetPos.toVector().subtract(current.toVector()).normalize();
                    target = current.clone().add(towards.multiply(10));
                }
                break;
                
            default:
                // 随机方向
                double angle = Math.random() * 2 * Math.PI;
                double distance = 6 + Math.random() * 4;
                Vector random = new Vector(
                    Math.cos(angle) * distance,
                    0,
                    Math.sin(angle) * distance
                );
                target = current.clone().add(random);
                break;
        }
        
        if (target != null) {
            target.setY(current.getY());
        }
        
        return target;
    }

    
    /**
     * 检查并避让拥挤 - ZombieGame 的核心避让机制
     */
    private void checkAndAvoidCrowding() {
        // 每 5 秒检查一次
        if (Math.random() > 0.01) { // 1% 概率 = 约每 5 秒
            return;
        }
        
        // 查找最近的僵尸
        Zombie nearestZombie = null;
        double minDistSq = Double.MAX_VALUE;
        
        for (Entity entity : zombie.getNearbyEntities(100, 60, 100)) {
            if (entity instanceof Zombie && !entity.getUniqueId().equals(zombie.getUniqueId())) {
                double distSq = entity.getLocation().distanceSquared(zombie.getLocation());
                if (distSq < minDistSq) {
                    minDistSq = distSq;
                    nearestZombie = (Zombie) entity;
                }
            }
        }
        
        // 如果距离太近（2格内），触发避让
        if (nearestZombie != null && minDistSq <= 4.0) {
            // 10% 概率触发避让
            if (Math.random() < 0.1) {
                // 检查是否正在建造
                ZombieAgent otherAgent = ZombieAIManager.getInstance().getAgent(nearestZombie.getUniqueId());
                if (otherAgent != null && otherAgent.getMeleeAttackBehavior() != null 
                    && otherAgent.getMeleeAttackBehavior().isStartBuilding()) {
                    // 对方正在建造，不打扰
                    return;
                }
                
                // 10% 概率触发避让
                if (Math.random() < 0.1) {
                    boolean hasTarget = zombie.getTarget() != null;
                    startRandomWalking(5000L, hasTarget);
                }
            }
        }
    }

    
    /**
     * 开始随机游走
     */
    public void startRandomWalking(long timeMs, boolean isMoreImportant) {
        startRandomWalking(timeMs, isMoreImportant, NORMAL);
    }

    
    /**
     * 开始随机游走（带模式）
     */
    public void startRandomWalking(long timeMs, boolean isMoreImportant, int state) {
        if (agent.isBuilding()) {
            return;
        }
        
        if (!isWalking) {
            if (isMoreImportant || agent.isFree()) {
                this.walkingTimeLeft = timeMs;
                this.isMoreImportant = isMoreImportant;
                this.isWalking = true;
                this.isSetPos = false;
                this.state = state;
                agent.setWalking(true);
            }
        }
    }

    
    /**
     * 设置目标位置（用于 AWAY 和 TOWARDS 模式）
     */
    public void setTargetPos(Location targetPos) {
        this.targetPos = targetPos;
    }

    
    /**
     * 调用停止游走（可被打断）
     */
    public void callToStopWalking() {
        if (!isMoreImportant || walkingTimeLeft <= -5000L) {
            stopWalking();
        }
        
        if (zombie.getTarget() != null) {
            double dist = zombie.getLocation().distance(zombie.getTarget().getLocation());
            if (walkingTimeLeft <= -1500L && dist <= 6.0) {
                stopWalking();
            }
        }
    }

    
    /**
     * 停止游走
     */
    public void stopWalking() {
        if (isWalking) {
            isWalking = false;
            zombie.getPathfinder().stopPathfinding();
            agent.setWalking(false);
        }
    }

    
    public boolean isWalking() {
        return isWalking;
    }
}
