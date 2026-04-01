package com.frigidora.toomuchzombies.ai.behavior;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;
import com.frigidora.toomuchzombies.ai.pathfinding.*;
import com.frigidora.toomuchzombies.config.ConfigManager;

/**
 * 僵尸建造行为 V2 - 使用 ZombieGame 的 PathFinder 算法
 * 
 * 完全重写的建造系统，基于 ZombieGame 的核心算法：
 * 1. 使用 PathFinder 进行路径规划
 * 2. 自动识别方向和高度变化
 * 3. 分步执行建造/破坏
 * 4. 与 Breaker 协作
 */
public class ZombieBuilderBehaviorV2 {
    
    private final ZombieAgent agent;
    private final Zombie zombie;
    private final ZombieBreakerBehavior breaker;
    
    private boolean active = false;
    private PathFinder pathFinder;
    private Location selfPos;
    private long lastPlaceTime = 0;
    private long lastMoveTime = 0;
    private long buildActivatedAt = 0;
    
    private final Map<String, Integer> failureCounters = new ConcurrentHashMap<>();
    private boolean wasInterrupted = false;
    
    public ZombieBuilderBehaviorV2(ZombieAgent agent, ZombieBreakerBehavior breaker) {
        this.agent = agent;
        this.zombie = agent.getZombie();
        this.breaker = breaker;
        initializePathFinder();
    }

    
    /**
     * 初始化 PathFinder
     */
    private void initializePathFinder() {
        // 当僵尸位置需要更新时的回调
        pathFinder = new PathFinder(
            (oldPos, newPos) -> {
                if (zombie.isValid()) {
                    selfPos = newPos.clone();
                    // 移动僵尸到新位置
                    Location moveTo = newPos.clone().add(0.5, 0, 0.5);
                    agent.moveTo(moveTo, 0.7);
                    lastMoveTime = System.currentTimeMillis();
                }
            },
            // 检查结构是否完成
            (blocks) -> {
                for (Map.Entry<Integer, BlockPacket> entry : blocks.entrySet()) {
                    BlockPacket packet = entry.getValue();
                    if (!isBlockDone(packet)) {
                        return false;
                    }
                }
                return true;
            }
        );
    }
    
    /**
     * 检查方块是否符合要求
     */
    private boolean isBlockDone(BlockPacket packet) {
        if (packet == null || packet.isEmpty() || packet.location == null) {
            return true;
        }
        
        Block block = packet.getBlock();
        if (block == null) {
            return false;
        }
        
        if (packet.blockKind == BlockKind.AIR) {
            // 需要是空气或可通过的方块
            return block.getType() == Material.AIR 
                || block.getType() == Material.WATER 
                || block.getType() == Material.LAVA
                || !block.getType().isSolid();
        } else {
            // 需要是固体方块
            return block.getType().isSolid();
        }
    }

    
    public void setActive(boolean active) {
        if (this.active != active) {
            this.active = active;
            if (active) {
                // 开始建造
                Location target = agent.getLastKnownTargetLocation();
                if (target != null && target.getWorld() != null && target.getWorld().equals(zombie.getWorld())) {
                    selfPos = zombie.getLocation().getBlock().getLocation();
                    pathFinder.start(selfPos, target);
                    buildActivatedAt = System.currentTimeMillis();
                    agent.setBuilding(true);
                    zombie.getPathfinder().stopPathfinding();
                }
            } else {
                // 停止建造
                pathFinder.stop();
                breaker.stopBreaking();
                agent.setBuilding(false);
            }
        }
    }
    
    public boolean isActive() {
        return active;
    }
    
    public void tick() {
        if (!active) {
            return;
        }
        
        // 如果 Breaker 正在工作，等待
        if (breaker.isBreaking()) {
            breaker.tick();
            return;
        }
        
        // 检查目标是否还有效
        Location target = agent.getLastKnownTargetLocation();
        if (target == null || !target.getWorld().equals(zombie.getWorld())) {
            // 目标丢失，但给一个短暂的窗口期
            if (System.currentTimeMillis() - buildActivatedAt >= 2200L) {
                setActive(false);
            }
            return;
        }
        
        // 检查是否到达目标
        if (pathFinder.isDone()) {
            setActive(false);
            return;
        }
        
        // 检查僵尸是否在正确的位置
        if (selfPos != null) {
            double distSq = zombie.getLocation().distanceSquared(selfPos.clone().add(0.5, 0, 0.5));
            if (distSq > 2.56) { // 超过 1.6 格
                // 移动到正确位置
                agent.moveTo(selfPos.clone().add(0.5, 0, 0.5), 1.0);
                return;
            }
        }
        
        // 获取当前需要处理的方块
        BlockPacket packet = pathFinder.getBlock();
        if (packet.isEmpty()) {
            pathFinder.next();
            return;
        }
        
        // 检查方块是否已完成
        if (isBlockDone(packet)) {
            pathFinder.next();
            return;
        }
        
        // 处理方块
        if (packet.blockKind == BlockKind.AIR) {
            // 需要破坏
            Block block = packet.getBlock();
            if (block != null && breaker.canBreak(block)) {
                interruptForBreaking();
                breaker.startBreaking(block);
                agent.setBreaking(true);
            } else {
                // 无法破坏，跳过
                pathFinder.next();
            }
        } else {
            // 需要放置
            placeBlock(packet);
        }
    }

    
    /**
     * 放置方块
     */
    private void placeBlock(BlockPacket packet) {
        Block block = packet.getBlock();
        if (block == null) {
            pathFinder.next();
            return;
        }
        
        // 检查冷却
        long now = System.currentTimeMillis();
        long cooldown = ConfigManager.getInstance().getBuilderPlaceCooldownMs();
        if (now - lastPlaceTime < cooldown) {
            return;
        }
        
        // 检查距离
        double distSq = zombie.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5));
        if (distSq > 16.0) { // 超过 4 格
            failureCounters.merge("too_far", 1, Integer::sum);
            pathFinder.next();
            return;
        }
        
        // 获取放置材料
        Material material = getPlacementMaterial();
        if (material == null) {
            failureCounters.merge("no_material", 1, Integer::sum);
            pathFinder.next();
            return;
        }
        
        // 放置方块
        try {
            block.setType(material);
            lastPlaceTime = now;
            
            // 播放效果
            zombie.swingOffHand();
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_PLACE, 0.7f, 0.9f);
            
            // 前进到下一个方块
            pathFinder.next();
        } catch (Exception e) {
            failureCounters.merge("place_error", 1, Integer::sum);
            pathFinder.next();
        }
    }
    
    /**
     * 获取放置材料
     */
    private Material getPlacementMaterial() {
        // 优先使用配置的材料
        String configMaterial = ConfigManager.getInstance().getBuilderPlacementMaterial();
        if (configMaterial != null && !configMaterial.isEmpty()) {
            try {
                Material mat = Material.valueOf(configMaterial.toUpperCase());
                if (mat.isBlock() && mat.isSolid()) {
                    return mat;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        
        // 默认使用泥土
        return Material.DIRT;
    }
    
    // 中断和恢复机制
    public boolean wasInterruptedByBreaker() {
        return wasInterrupted;
    }
    
    public void resumeAfterBreak() {
        if (wasInterrupted) {
            wasInterrupted = false;
            agent.setBuilding(true);
        }
    }
    
    public void interruptForBreaking() {
        if (active) {
            wasInterrupted = true;
            agent.setBuilding(false);
        }
    }
    
    public Map<String, Integer> getFailureCountersSnapshot() {
        return new HashMap<>(failureCounters);
    }
    
    public void resetFailureCounters() {
        failureCounters.clear();
    }
}
