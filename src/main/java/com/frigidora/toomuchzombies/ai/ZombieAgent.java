package com.frigidora.toomuchzombies.ai;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;

import com.frigidora.toomuchzombies.enums.ZombieRole;

public class ZombieAgent {
    private final UUID uuid;
    private final Zombie zombie;
    private ZombieRole role;
    private int level = 1;
    private Location lastKnownTargetLocation;
    private WeakReference<LivingEntity> targetEntityRef;
    private long lastSeenTargetTime;
    private boolean isFlanking;
    
    // 冷却时间映射
    private final Map<String, Long> cooldowns = new HashMap<>();
    
    // 破坏状态
    private org.bukkit.block.Block breakingTarget;
    private float breakingProgress = 0; // 0.0 to 1.0
    private long lastBreakTick = 0;
    
    // 搭建状态
    private Location lastBuildLocation;
    private long buildLockUntil = 0; // 锁定移动直到此时间戳
    
    // 战斗状态
    private long lastDamageTime;
    private Location lastDamageSourceLocation;
    
    // 特殊状态
    private int teleportCount = 0;
    private long shieldGuardUntil = 0;
    
    // 卡死检测
    private Location lastStuckCheckLocation;
    private int stuckTicks = 0;
    
    // 空间分区索引
    private long lastSpatialKey = Long.MIN_VALUE;

    // 新 AI 行为
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieBreakerBehavior breakerBehavior;
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehavior builderBehavior;
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieSuicideBehavior suicideBehavior;
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieCooperationBehavior cooperationBehavior;

    public ZombieAgent(Zombie zombie, ZombieRole role) {
        this.zombie = zombie;
        this.uuid = zombie.getUniqueId();
        this.role = role;
        
        this.breakerBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieBreakerBehavior(this);
        this.builderBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehavior(this, this.breakerBehavior);
        this.suicideBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieSuicideBehavior(this);
        this.cooperationBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieCooperationBehavior(this);
    }

    public com.frigidora.toomuchzombies.ai.behavior.ZombieBreakerBehavior getBreakerBehavior() {
        return breakerBehavior;
    }

    public com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehavior getBuilderBehavior() {
        return builderBehavior;
    }
    
    public com.frigidora.toomuchzombies.ai.behavior.ZombieSuicideBehavior getSuicideBehavior() {
        return suicideBehavior;
    }
    
    public com.frigidora.toomuchzombies.ai.behavior.ZombieCooperationBehavior getCooperationBehavior() {
        return cooperationBehavior;
    }

    public void recordDamage(Location sourceLocation) {
        this.lastDamageTime = System.currentTimeMillis();
        this.lastDamageSourceLocation = sourceLocation;
    }
    
    public boolean wasDamagedRecently(long durationMillis) {
        return System.currentTimeMillis() - lastDamageTime < durationMillis;
    }
    
    public Location getLastDamageSourceLocation() {
        return lastDamageSourceLocation;
    }

    public void setLastDamageSourceLocation(Location lastDamageSourceLocation) {
        this.lastDamageSourceLocation = lastDamageSourceLocation;
    }

    public int getTeleportCount() {
        return teleportCount;
    }

    public void incrementTeleportCount() {
        this.teleportCount++;
    }

    public void activateShieldGuard(long durationMs) {
        shieldGuardUntil = System.currentTimeMillis() + durationMs;
    }

    public boolean isShieldGuardActive() {
        return System.currentTimeMillis() <= shieldGuardUntil;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Zombie getZombie() {
        return zombie;
    }

    public ZombieRole getRole() {
        return role;
    }

    public void setRole(ZombieRole role) {
        this.role = role;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public Location getLastKnownTargetLocation() {
        return lastKnownTargetLocation;
    }

    public void setTargetLocation(Location target) {
        setLastKnownTargetLocation(target);
    }

    public void setLastKnownTargetLocation(Location lastKnownTargetLocation) {
        this.lastKnownTargetLocation = lastKnownTargetLocation;
        this.lastSeenTargetTime = System.currentTimeMillis();
    }
    
    public void setTargetEntity(LivingEntity target) {
        this.targetEntityRef = new WeakReference<>(target);
    }
    
    public LivingEntity getTargetEntity() {
        return targetEntityRef != null ? targetEntityRef.get() : null;
    }
    
    public boolean hasMemoryExpired(long durationMillis) {
        return System.currentTimeMillis() - lastSeenTargetTime > durationMillis;
    }

    public boolean isFlanking() {
        return isFlanking;
    }

    public void setFlanking(boolean flanking) {
        isFlanking = flanking;
    }

    public boolean checkAndResetSkillCooldown(String skillKey, long cooldownMs) {
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(skillKey, 0L);
        if (now - last >= cooldownMs) {
            cooldowns.put(skillKey, now);
            return true;
        }
        return false;
    }

    public org.bukkit.block.Block getBreakingTarget() {
        return breakingTarget;
    }

    public void setBreakingTarget(org.bukkit.block.Block breakingTarget) {
        this.breakingTarget = breakingTarget;
    }

    public float getBreakingProgress() {
        return breakingProgress;
    }

    public void setBreakingProgress(float breakingProgress) {
        this.breakingProgress = breakingProgress;
    }

    public long getLastBreakTick() {
        return lastBreakTick;
    }

    public void setLastBreakTick(long lastBreakTick) {
        this.lastBreakTick = lastBreakTick;
    }

    public Location getLastBuildLocation() {
        return lastBuildLocation;
    }

    public void setLastBuildLocation(Location lastBuildLocation) {
        this.lastBuildLocation = lastBuildLocation;
    }

    private boolean aiPaused = false;

    public void setAiPaused(boolean paused) {
        this.aiPaused = paused;
    }

    public boolean isAiPaused() {
        return aiPaused;
    }

    public void moveTo(Location loc, double speed) {
        if (aiPaused) {
            zombie.setTarget(null); // 如果 AI 已暂停，清除目标以防止干扰手动移动
        }
        if (com.frigidora.toomuchzombies.TooMuchZombies.getNMSHandler() != null) {
            com.frigidora.toomuchzombies.TooMuchZombies.getNMSHandler().moveTo(zombie, loc, speed);
        }
    }
    
    public int getTicksStuck() {
        return stuckTicks;
    }

    public boolean isStuck() {
        if (zombie == null || !zombie.isValid()) return false;
        
        if (lastStuckCheckLocation == null || !lastStuckCheckLocation.getWorld().equals(zombie.getWorld())) {
            lastStuckCheckLocation = zombie.getLocation();
            stuckTicks = 0;
            return false;
        }
        
        if (zombie.getLocation().distanceSquared(lastStuckCheckLocation) < 0.04) { // 移动非常少 (< 0.2 blocks)
             stuckTicks++;
        } else {
             lastStuckCheckLocation = zombie.getLocation();
             stuckTicks = 0;
        }
        
        // 如果卡住超过 10 秒 (200 ticks) 且最近没受到伤害
        // 且没有正在攻击目标（或者在攻击但无法移动）
        if (stuckTicks > 200 && !wasDamagedRecently(10000)) {
            // 如果有目标且距离很近，可能是战斗中，不移除
            LivingEntity target = getTargetEntity();
            if (target != null && target.getWorld().equals(zombie.getWorld()) && target.getLocation().distanceSquared(zombie.getLocation()) < 9) {
                return false;
            }
            return true;
        }
        return false;
    }
    
    public long getLastSpatialKey() {
        return lastSpatialKey;
    }

    public void setLastSpatialKey(long lastSpatialKey) {
        this.lastSpatialKey = lastSpatialKey;
    }
}
