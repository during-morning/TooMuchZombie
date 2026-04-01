package com.frigidora.toomuchzombies.ai;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;

import com.frigidora.toomuchzombies.enums.ZombieRole;
import com.frigidora.toomuchzombies.mechanics.LightSourceManager;

public class ZombieAgent {
    public enum PathIntent {
        IDLE,
        DIRECT_CHASE,
        NAV_CORRIDOR,
        STRUCTURE_BREACH_BUILD,
        CLOSE_COMBAT,
        EVADE_BEACON,
        EVADE_LIGHT
    }

    public enum MovementIntent {
        IDLE,
        LOCK_PURSUIT,
        CORRIDOR,
        BREACH,
        CLOSE,
        RECOVER,
        EVADE_BEACON,
        EVADE_LIGHT
    }

    public enum MovementPriority {
        LOW(0),
        MEDIUM(1),
        HIGH(2),
        CRITICAL(3);

        private final int rank;

        MovementPriority(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }
    }

    public enum PathFailureType {
        NONE,
        SHORT_BLOCKED,
        PATH_MISSING,
        HARD_STUCK
    }

    private static final class PendingMove {
        private final Location destination;
        private final double speed;
        private final MovementPriority priority;
        private final PathIntent intent;
        private final long leaseUntil;

        private PendingMove(Location destination, double speed, MovementPriority priority, PathIntent intent, long leaseUntil) {
            this.destination = destination;
            this.speed = speed;
            this.priority = priority;
            this.intent = intent;
            this.leaseUntil = leaseUntil;
        }
    }

    private final UUID uuid;
    private final Zombie zombie;
    private ZombieRole role;
    private int level = 1;
    private Location lastKnownTargetLocation;
    private WeakReference<LivingEntity> targetEntityRef;
    private long lastSeenTargetAt;
    private boolean isFlanking;
    private Location investigationTarget;
    private long investigationExpiry;
    private Location lastNoiseLocation;
    private long lastNoiseExpiry;
    private double lastNoiseStrength;
    private WeakReference<LivingEntity> focusTargetHintRef;
    private long focusTargetHintExpiry;
    private WeakReference<LivingEntity> protectTargetHintRef;
    private long protectTargetHintExpiry;
    private long pursuitLockUntil;
    private UUID pursuitTargetUuid;
    private long targetLeaseUntil = 0L;
    private long lastTargetCommitAt = 0L;
    private UUID lastCommittedTargetUuid;
    
    // 冷却时间映射
    private final Map<String, Long> cooldowns = new HashMap<>();
    
    // 破坏状态
    private org.bukkit.block.Block breakingTarget;
    private float breakingProgress = 0; // 0.0 to 1.0
    private long lastBreakTick = 0;
    
    // 搭建状态
    private Location lastBuildLocation;
    private Location lastMoveCommandLocation;
    private long lastMoveCommandAt = 0L;
    private double lastMoveCommandSpeed = Double.NaN;
    private long buildLockUntil = 0; // 锁定移动直到此时间戳
    
    // 战斗状态
    private long lastDamageTime;
    private Location lastDamageSourceLocation;
    
    // 特殊状态
    private int teleportCount = 0;
    private long shieldGuardUntil = 0;
    private boolean isWalking = false;
    private boolean isBuilding = false;
    
    // 卡死检测
    private Location lastStuckCheckLocation;
    private int stuckTicks = 0;
    private long lastStuckSampleTick = Long.MIN_VALUE;
    private boolean cachedStuckState = false;
    private int shortBlockedStrikes = 0;
    private int missingPathStrikes = 0;
    private long lastPathFailureAt = 0L;
    private long lastPathFailureDecayAt = 0L;
    
    // 空间分区索引
    private long lastSpatialKey = Long.MIN_VALUE;
    private PathIntent pathIntent = PathIntent.IDLE;
    private Location pathAnchor;
    private long pathModeLeaseUntil = 0L;
    private long lastReplanAt = 0L;
    private MovementIntent movementIntent = MovementIntent.IDLE;
    private long movementIntentLeaseUntil = 0L;
    private int lateralBias = 0;
    private long lateralBiasUntil = 0L;
    private int strafeLockDirection = 0;
    private long laneLockUntil = 0L;
    private int replanFailStreak = 0;
    private PendingMove pendingMove;

    // 新 AI 行为
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieBreakerBehavior breakerBehavior;
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehavior builderBehavior;
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieSuicideBehavior suicideBehavior;
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieCooperationBehavior cooperationBehavior;
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehaviorV2 builderBehaviorV2;
    private final com.frigidora.toomuchzombies.ai.behavior.ZombieMeleeAttackBehavior meleeAttackBehavior;

    public ZombieAgent(Zombie zombie, ZombieRole role) {
        this.zombie = zombie;
        this.uuid = zombie.getUniqueId();
        this.role = role;
        
        this.breakerBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieBreakerBehavior(this);
        this.builderBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehavior(this, this.breakerBehavior);
        this.suicideBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieSuicideBehavior(this);
        this.cooperationBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieCooperationBehavior(this);
        this.builderBehaviorV2 = new com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehaviorV2(this, this.breakerBehavior);
        this.meleeAttackBehavior = new com.frigidora.toomuchzombies.ai.behavior.ZombieMeleeAttackBehavior(this, this.builderBehaviorV2);
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
    
    public com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehaviorV2 getBuilderBehaviorV2() {
        return builderBehaviorV2;
    }
    
    public com.frigidora.toomuchzombies.ai.behavior.ZombieMeleeAttackBehavior getMeleeAttackBehavior() {
        return meleeAttackBehavior;
    }
    
    public boolean isWalking() {
        return isWalking;
    }
    
    public void setWalking(boolean walking) {
        this.isWalking = walking;
    }
    
    public boolean isBuilding() {
        return isBuilding || builderBehaviorV2.isActive();
    }
    
    public void setBuilding(boolean building) {
        this.isBuilding = building;
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
        this.lastKnownTargetLocation = lastKnownTargetLocation == null ? null : lastKnownTargetLocation.clone();
        this.lastSeenTargetAt = System.currentTimeMillis();
    }
    
    public void setTargetEntity(LivingEntity target) {
        this.targetEntityRef = new WeakReference<>(target);
        if (target != null) {
            this.pursuitTargetUuid = target.getUniqueId();
        }
    }
    
    public LivingEntity getTargetEntity() {
        return targetEntityRef != null ? targetEntityRef.get() : null;
    }
    
    public boolean hasMemoryExpired(long durationMillis) {
        return System.currentTimeMillis() - lastSeenTargetAt > durationMillis;
    }

    public boolean isFlanking() {
        return isFlanking;
    }

    public void setFlanking(boolean flanking) {
        isFlanking = flanking;
    }

    public void setInvestigationTarget(Location target, long ttlMs) {
        this.investigationTarget = target == null ? null : target.clone();
        this.investigationExpiry = System.currentTimeMillis() + Math.max(250L, ttlMs);
    }

    public Location getInvestigationTarget() {
        if (investigationTarget == null || System.currentTimeMillis() > investigationExpiry) {
            investigationTarget = null;
            return null;
        }
        return investigationTarget.clone();
    }

    public void clearInvestigationTarget() {
        investigationTarget = null;
        investigationExpiry = 0L;
    }

    public void setNoiseHint(Location location, long ttlMs, double strength) {
        this.lastNoiseLocation = location == null ? null : location.clone();
        this.lastNoiseExpiry = System.currentTimeMillis() + Math.max(250L, ttlMs);
        this.lastNoiseStrength = Math.max(0.0, Math.min(2.0, strength));
    }

    public Location getNoiseHintLocation() {
        if (lastNoiseLocation == null || System.currentTimeMillis() > lastNoiseExpiry) {
            lastNoiseLocation = null;
            lastNoiseStrength = 0.0;
            return null;
        }
        return lastNoiseLocation.clone();
    }

    public double getNoiseHintStrength() {
        if (lastNoiseLocation == null || System.currentTimeMillis() > lastNoiseExpiry) {
            return 0.0;
        }
        return lastNoiseStrength;
    }

    public void setFocusTargetHint(LivingEntity target, long ttlMs) {
        this.focusTargetHintRef = new WeakReference<>(target);
        this.focusTargetHintExpiry = target == null ? 0L : System.currentTimeMillis() + Math.max(250L, ttlMs);
    }

    public LivingEntity getFocusTargetHint() {
        if (focusTargetHintRef == null || System.currentTimeMillis() > focusTargetHintExpiry) {
            focusTargetHintRef = null;
            focusTargetHintExpiry = 0L;
            return null;
        }
        LivingEntity target = focusTargetHintRef.get();
        if (target == null || !target.isValid() || target.isDead()) {
            focusTargetHintRef = null;
            focusTargetHintExpiry = 0L;
            return null;
        }
        return target;
    }

    public void setProtectTargetHint(LivingEntity target, long ttlMs) {
        this.protectTargetHintRef = new WeakReference<>(target);
        this.protectTargetHintExpiry = target == null ? 0L : System.currentTimeMillis() + Math.max(250L, ttlMs);
    }

    public LivingEntity getProtectTargetHint() {
        if (protectTargetHintRef == null || System.currentTimeMillis() > protectTargetHintExpiry) {
            protectTargetHintRef = null;
            protectTargetHintExpiry = 0L;
            return null;
        }
        LivingEntity target = protectTargetHintRef.get();
        if (target == null || !target.isValid() || target.isDead()) {
            protectTargetHintRef = null;
            protectTargetHintExpiry = 0L;
            return null;
        }
        return target;
    }

    public void lockPursuitOn(LivingEntity target, long ttlMs) {
        if (target == null || !target.isValid()) {
            return;
        }
        this.pursuitTargetUuid = target.getUniqueId();
        this.pursuitLockUntil = Math.max(this.pursuitLockUntil, System.currentTimeMillis() + Math.max(250L, ttlMs));
    }

    public void leaseTarget(long ttlMs) {
        targetLeaseUntil = Math.max(targetLeaseUntil, System.currentTimeMillis() + Math.max(250L, ttlMs));
    }

    public boolean isTargetLeaseActive() {
        return System.currentTimeMillis() <= targetLeaseUntil;
    }

    public long getTargetLeaseUntil() {
        return targetLeaseUntil;
    }

    public long getLastSeenTargetAt() {
        return lastSeenTargetAt;
    }

    public void markTargetSeenNow() {
        this.lastSeenTargetAt = System.currentTimeMillis();
    }

    public boolean isPursuitLocked() {
        return System.currentTimeMillis() <= pursuitLockUntil;
    }

    public boolean isPursuitLockedOn(UUID targetUuid) {
        if (!isPursuitLocked() || targetUuid == null || pursuitTargetUuid == null) {
            return false;
        }
        return pursuitTargetUuid.equals(targetUuid);
    }

    public boolean canCommitTargetSwitch(UUID nextTargetUuid, long minIntervalMs) {
        if (nextTargetUuid == null) {
            return true;
        }
        if (lastCommittedTargetUuid == null || lastCommittedTargetUuid.equals(nextTargetUuid)) {
            return true;
        }
        return System.currentTimeMillis() - lastTargetCommitAt >= Math.max(0L, minIntervalMs);
    }

    public void markTargetCommitted(LivingEntity target) {
        if (target == null) {
            return;
        }
        lastCommittedTargetUuid = target.getUniqueId();
        lastTargetCommitAt = System.currentTimeMillis();
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
    private boolean isBreaking = false;

    public void setAiPaused(boolean paused) {
        this.aiPaused = paused;
    }

    public boolean isAiPaused() {
        return aiPaused;
    }

    public boolean isBusy() {
        return isBreaking || isBuilding || breakerBehavior.isBreaking() || builderBehavior.isActive();
    }

    public void setBreaking(boolean breaking) {
        this.isBreaking = breaking;
    }

    public void beginBehaviorTick(long currentTick) {
        sampleStuckState(currentTick);
        pendingMove = null;
        long now = System.currentTimeMillis();
        if (now - lastPathFailureAt > 1200L && now - lastPathFailureDecayAt > 450L) {
            shortBlockedStrikes = Math.max(0, shortBlockedStrikes - 1);
            missingPathStrikes = Math.max(0, missingPathStrikes - 1);
            lastPathFailureDecayAt = now;
        }
        if (lateralBiasUntil > 0L && now > lateralBiasUntil) {
            lateralBias = 0;
            lateralBiasUntil = 0L;
        }
        if (laneLockUntil > 0L && now > laneLockUntil) {
            strafeLockDirection = 0;
            laneLockUntil = 0L;
        }
        if (movementIntentLeaseUntil > 0L && now > movementIntentLeaseUntil) {
            movementIntent = MovementIntent.IDLE;
            movementIntentLeaseUntil = 0L;
        }
    }

    public void moveTo(Location loc, double speed) {
        if (loc == null || loc.getWorld() == null || zombie == null || !zombie.isValid()) {
            return;
        }
        if (aiPaused) {
            zombie.setTarget(null); // 如果 AI 已暂停，清除目标以防止干扰手动移动
            return; // 修复：AI暂停时直接返回，不执行移动
        }

        Location currentLoc = zombie.getLocation();
        if (!currentLoc.getWorld().equals(loc.getWorld())) {
            return;
        }

        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            Location clipped = clipToLoadedChunk(currentLoc, loc);
            if (clipped == null) {
                return;
            }
            loc = clipped;
        }

        if (currentLoc.distanceSquared(loc) <= 1.0) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean sameDestination = lastMoveCommandLocation != null
            && lastMoveCommandLocation.getWorld() != null
            && lastMoveCommandLocation.getWorld().equals(loc.getWorld())
            && lastMoveCommandLocation.distanceSquared(loc) <= 2.25;
        boolean similarSpeed = Double.isNaN(lastMoveCommandSpeed) || Math.abs(lastMoveCommandSpeed - speed) <= 0.08;
        boolean recentCommand = now - lastMoveCommandAt < 250L;

        if (sameDestination && similarSpeed && recentCommand && zombie.getPathfinder().hasPath()) {
            return;
        }

        if (com.frigidora.toomuchzombies.TooMuchZombies.getNMSHandler() != null) {
            double effectiveSpeed = speed * getEnvironmentalSpeedMultiplier();
            if (movementIntent == MovementIntent.CLOSE && effectiveSpeed < 0.72) {
                effectiveSpeed = 0.72;
            }
            com.frigidora.toomuchzombies.TooMuchZombies.getNMSHandler().moveTo(zombie, loc, effectiveSpeed);
            lastMoveCommandLocation = loc.clone();
            lastMoveCommandAt = now;
            lastMoveCommandSpeed = effectiveSpeed;
        }
    }

    private Location clipToLoadedChunk(Location from, Location desired) {
        if (from == null || desired == null || from.getWorld() == null || desired.getWorld() == null) {
            return null;
        }
        if (!from.getWorld().equals(desired.getWorld())) {
            return null;
        }
        Vector direction = desired.toVector().subtract(from.toVector()).setY(0);
        if (direction.lengthSquared() < 0.04) {
            return null;
        }
        direction.normalize();

        Location best = null;
        for (int i = 1; i <= 8; i++) {
            Location probe = from.clone().add(direction.clone().multiply(i * 1.2));
            probe.setY(from.getY());
            if (!probe.getWorld().isChunkLoaded(probe.getBlockX() >> 4, probe.getBlockZ() >> 4)) {
                break;
            }
            best = probe;
        }
        return best;
    }

    public boolean submitMoveIntent(Location loc, double speed, MovementPriority priority, PathIntent intent, long leaseMs) {
        if (loc == null || loc.getWorld() == null || zombie == null || !zombie.isValid()) {
            return false;
        }
        if (!zombie.getWorld().equals(loc.getWorld())) {
            return false;
        }

        long now = System.currentTimeMillis();
        long leaseUntil = now + Math.max(100L, leaseMs);
        if (pendingMove != null) {
            boolean stronger = priority.getRank() > pendingMove.priority.getRank();
            boolean samePriority = priority == pendingMove.priority;
            boolean sameIntent = pendingMove.intent == intent;
            boolean nearSameTarget = pendingMove.destination.getWorld().equals(loc.getWorld())
                && pendingMove.destination.distanceSquared(loc) <= 2.25;
            boolean oldMoveLeased = pendingMove.leaseUntil > now;
            if (!stronger) {
                if (oldMoveLeased && (!samePriority || (sameIntent && nearSameTarget))) {
                    return false;
                }
                if (samePriority && !sameIntent && oldMoveLeased) {
                    return false;
                }
            }
        }

        setMovementIntent(mapPathToMovementIntent(intent), leaseMs);
        pendingMove = new PendingMove(loc.clone(), speed, priority, intent, leaseUntil);
        return true;
    }

    public void flushMoveIntent() {
        if (pendingMove == null) {
            return;
        }
        Location destination = pendingMove.destination;
        double speed = sanitizeSpeedForTerrain(destination, pendingMove.speed, pendingMove.intent);

        if (isDropRiskAhead(destination)
            && (pendingMove.intent == PathIntent.DIRECT_CHASE || pendingMove.intent == PathIntent.NAV_CORRIDOR)) {
            Location current = zombie.getLocation();
            Vector direction = destination.toVector().subtract(current.toVector()).setY(0);
            if (direction.lengthSquared() > 0.04) {
                direction.normalize().multiply(0.85);
                destination = current.clone().add(direction);
                destination.setY(current.getY());
            }
            speed = Math.min(speed, 0.80);
        }

        moveTo(destination, speed);
        pendingMove = null;
    }

    public void clearMovementIntent() {
        pendingMove = null;
    }

    public PathIntent getPathIntent() {
        return pathIntent;
    }

    public void setPathIntent(PathIntent pathIntent, Location anchor, long leaseMs) {
        this.pathIntent = pathIntent == null ? PathIntent.IDLE : pathIntent;
        this.pathAnchor = anchor == null ? null : anchor.clone();
        this.pathModeLeaseUntil = System.currentTimeMillis() + Math.max(0L, leaseMs);
        this.lastReplanAt = System.currentTimeMillis();
        replanFailStreak = Math.max(0, replanFailStreak - 1);
    }

    public boolean isPathIntentLeased(PathIntent intent) {
        return this.pathIntent == intent && System.currentTimeMillis() <= pathModeLeaseUntil;
    }

    public long getPathModeLeaseUntil() {
        return pathModeLeaseUntil;
    }

    public void setMovementIntent(MovementIntent intent, long leaseMs) {
        this.movementIntent = intent == null ? MovementIntent.IDLE : intent;
        this.movementIntentLeaseUntil = System.currentTimeMillis() + Math.max(0L, leaseMs);
    }

    public MovementIntent getMovementIntent() {
        return movementIntent;
    }

    public boolean isMovementIntentLeased(MovementIntent intent) {
        return this.movementIntent == intent && System.currentTimeMillis() <= movementIntentLeaseUntil;
    }

    public long getMovementIntentLeaseUntil() {
        return movementIntentLeaseUntil;
    }

    public Location getPathAnchor() {
        return pathAnchor == null ? null : pathAnchor.clone();
    }

    public void updatePathAnchor(Location anchor) {
        this.pathAnchor = anchor == null ? null : anchor.clone();
    }

    public long getLastReplanAt() {
        return lastReplanAt;
    }

    public boolean canReplanPath(long minIntervalMs, Location nextAnchor, double thresholdSq) {
        long now = System.currentTimeMillis();
        if (now - lastReplanAt >= Math.max(0L, minIntervalMs)) {
            return true;
        }
        if (nextAnchor == null || pathAnchor == null) {
            return true;
        }
        if (pathAnchor.getWorld() == null || nextAnchor.getWorld() == null || !pathAnchor.getWorld().equals(nextAnchor.getWorld())) {
            return true;
        }
        return pathAnchor.distanceSquared(nextAnchor) >= Math.max(0.25, thresholdSq);
    }

    public void lockLateralBias(int bias, long ttlMs) {
        this.lateralBias = Integer.compare(bias, 0);
        this.lateralBiasUntil = this.lateralBias == 0 ? 0L : System.currentTimeMillis() + Math.max(250L, ttlMs);
        this.strafeLockDirection = this.lateralBias;
        this.laneLockUntil = this.strafeLockDirection == 0 ? 0L : System.currentTimeMillis() + Math.max(250L, ttlMs);
    }

    public int getLateralBias() {
        if (lateralBiasUntil > 0L && System.currentTimeMillis() > lateralBiasUntil) {
            lateralBias = 0;
            lateralBiasUntil = 0L;
        }
        return lateralBias;
    }

    public void lockStrafeDirection(int direction, long ttlMs) {
        this.strafeLockDirection = Integer.compare(direction, 0);
        this.laneLockUntil = this.strafeLockDirection == 0 ? 0L : System.currentTimeMillis() + Math.max(250L, ttlMs);
        lockLateralBias(strafeLockDirection, ttlMs);
    }

    public int getStrafeLockDirection() {
        if (laneLockUntil > 0L && System.currentTimeMillis() > laneLockUntil) {
            strafeLockDirection = 0;
            laneLockUntil = 0L;
        }
        if (strafeLockDirection == 0) {
            return getLateralBias();
        }
        return strafeLockDirection;
    }

    public long getLaneLockUntil() {
        return laneLockUntil;
    }

    public boolean canReverseStrafeDirection(int nextDirection, int failureThreshold) {
        int current = getStrafeLockDirection();
        int desired = Integer.compare(nextDirection, 0);
        if (current == 0 || desired == 0 || desired == current) {
            return true;
        }
        if (System.currentTimeMillis() > laneLockUntil) {
            return true;
        }
        return replanFailStreak >= Math.max(1, failureThreshold);
    }

    public void notePathFailure(PathFailureType failureType) {
        lastPathFailureAt = System.currentTimeMillis();
        lastPathFailureDecayAt = lastPathFailureAt;
        switch (failureType) {
            case SHORT_BLOCKED:
                shortBlockedStrikes = Math.min(8, shortBlockedStrikes + 1);
                missingPathStrikes = Math.max(0, missingPathStrikes - 1);
                replanFailStreak = Math.min(12, replanFailStreak + 1);
                break;
            case PATH_MISSING:
                missingPathStrikes = Math.min(8, missingPathStrikes + 1);
                replanFailStreak = Math.min(12, replanFailStreak + 2);
                break;
            case HARD_STUCK:
                shortBlockedStrikes = Math.min(8, shortBlockedStrikes + 1);
                missingPathStrikes = Math.min(8, missingPathStrikes + 1);
                replanFailStreak = Math.min(12, replanFailStreak + 3);
                break;
            case NONE:
            default:
                break;
        }
    }

    public void clearPathFailures() {
        shortBlockedStrikes = 0;
        missingPathStrikes = 0;
        replanFailStreak = 0;
        lastPathFailureAt = 0L;
        lastPathFailureDecayAt = 0L;
    }

    public int getReplanFailStreak() {
        return replanFailStreak;
    }

    public int getShortBlockedStrikes() {
        return shortBlockedStrikes;
    }

    public int getMissingPathStrikes() {
        return missingPathStrikes;
    }

    public PathFailureType getCurrentPathFailure() {
        if (cachedStuckState) {
            return PathFailureType.HARD_STUCK;
        }
        if (missingPathStrikes >= 3) {
            return PathFailureType.PATH_MISSING;
        }
        if (shortBlockedStrikes > 0) {
            return PathFailureType.SHORT_BLOCKED;
        }
        return PathFailureType.NONE;
    }
    
    public int getTicksStuck() {
        return stuckTicks;
    }

    public void resetStuckCounter() {
        lastStuckCheckLocation = zombie != null ? zombie.getLocation() : null;
        stuckTicks = 0;
        cachedStuckState = false;
        lastStuckSampleTick = Long.MIN_VALUE;
    }

    public void sampleStuckState(long currentTick) {
        if (zombie == null || !zombie.isValid()) {
            return;
        }
        if (lastStuckSampleTick == currentTick) {
            return;
        }
        lastStuckSampleTick = currentTick;

        if (lastStuckCheckLocation == null || !lastStuckCheckLocation.getWorld().equals(zombie.getWorld())) {
            lastStuckCheckLocation = zombie.getLocation();
            stuckTicks = 0;
            cachedStuckState = false;
            return;
        }

        if (zombie.getLocation().distanceSquared(lastStuckCheckLocation) < 0.04) { // 移动非常少 (< 0.2 blocks)
             stuckTicks++;
        } else {
             lastStuckCheckLocation = zombie.getLocation();
             stuckTicks = 0;
             clearPathFailures();
        }

        cachedStuckState = false;
        if (stuckTicks > 200 && !wasDamagedRecently(10000)) {
            LivingEntity target = getTargetEntity();
            if (target == null
                || !target.getWorld().equals(zombie.getWorld())
                || target.getLocation().distanceSquared(zombie.getLocation()) >= 9) {
                cachedStuckState = true;
                notePathFailure(PathFailureType.HARD_STUCK);
            }
        }
    }

    public boolean isStuck() {
        sampleStuckState(org.bukkit.Bukkit.getCurrentTick());
        return cachedStuckState;
    }

    public boolean isStuckCached() {
        return cachedStuckState;
    }

    public void prepareForRemoval() {
        clearMovementIntent();
        clearPathFailures();
        pathIntent = PathIntent.IDLE;
        pathAnchor = null;
        pathModeLeaseUntil = 0L;
        movementIntent = MovementIntent.IDLE;
        movementIntentLeaseUntil = 0L;
        targetLeaseUntil = 0L;
        strafeLockDirection = 0;
        laneLockUntil = 0L;
        setTargetEntity(null);
        setFocusTargetHint(null, 0L);
        setProtectTargetHint(null, 0L);
        clearInvestigationTarget();
        if (zombie != null && zombie.isValid()) {
            zombie.setTarget(null);
            if (zombie.getPathfinder().hasPath()) {
                zombie.getPathfinder().stopPathfinding();
            }
        }
        builderBehavior.setActive(false);
        breakerBehavior.stopBreaking();
        suicideBehavior.resetCharge();
        builderBehaviorV2.setActive(false);
        meleeAttackBehavior.cancelBuild();
        cooperationBehavior.clearTransientState();
    }
    
    /**
     * 僵尸攻击时的回调 - 从 ZombieGame 移植
     */
    public void onZombieAttack() {
        // 停止建造
        if (builderBehaviorV2.isActive()) {
            builderBehaviorV2.setActive(false);
            meleeAttackBehavior.cancelBuild();
        }
        
        // 停止破坏
        if (breakerBehavior.isBreaking()) {
            breakerBehavior.stopBreaking();
        }
        
        // 停止游走
        setWalking(false);
    }
    
    /**
     * 僵尸受伤时的回调 - 从 ZombieGame 移植
     */
    public void onZombieHurt() {
        // 停止建造
        if (builderBehaviorV2.isActive()) {
            builderBehaviorV2.setActive(false);
            meleeAttackBehavior.cancelBuild();
        }
        
        // 停止破坏
        if (breakerBehavior.isBreaking()) {
            breakerBehavior.stopBreaking();
        }
        
        // 停止游走
        setWalking(false);
        
        // 召唤附近僵尸
        callToAttack(zombie.getTarget());
    }
    
    /**
     * 群体召唤机制 - 从 ZombieGame 完整移植
     * 当僵尸受伤或发现目标时，召唤附近的僵尸一起攻击
     */
    public void callToAttack(LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return;
        }
        
        // 检查目标是否可攻击
        if (target instanceof org.bukkit.entity.Player) {
            org.bukkit.entity.Player player = (org.bukkit.entity.Player) target;
            org.bukkit.GameMode mode = player.getGameMode();
            if (mode == org.bukkit.GameMode.CREATIVE || mode == org.bukkit.GameMode.SPECTATOR) {
                return;
            }
        }
        
        // 获取附近的僵尸（75格范围）
        double range = 75.0;
        java.util.Collection<org.bukkit.entity.Entity> nearbyEntities = zombie.getWorld().getNearbyEntities(
            zombie.getLocation(), range, 50.0, range,
            entity -> entity instanceof org.bukkit.entity.Zombie && entity.isValid()
        );
        
        int called = 0;
        for (org.bukkit.entity.Entity entity : nearbyEntities) {
            if (!(entity instanceof org.bukkit.entity.Zombie)) {
                continue;
            }
            
            org.bukkit.entity.Zombie otherZombie = (org.bukkit.entity.Zombie) entity;
            
            // 跳过自己
            if (otherZombie.getUniqueId().equals(zombie.getUniqueId())) {
                continue;
            }
            
            // 检查视线或游戏阶段
            boolean hasLineOfSight = zombie.hasLineOfSight(otherZombie);
            boolean lateGame = zombie.getWorld().getTime() >= 18000; // 夜晚
            
            if (!hasLineOfSight && !lateGame) {
                continue;
            }
            
            // 如果该僵尸没有目标，设置目标
            if (otherZombie.getTarget() == null) {
                otherZombie.setTarget(target);
                called++;
            }
        }
        
        // 调试日志
        if (called > 0 && com.frigidora.toomuchzombies.TooMuchZombies.getInstance().getConfig().getBoolean("debug.group-call", false)) {
            com.frigidora.toomuchzombies.TooMuchZombies.getInstance().getLogger().info(
                String.format("Zombie %s called %d allies to attack %s",
                    zombie.getUniqueId().toString().substring(0, 8),
                    called,
                    target.getName())
            );
        }
    }
    
    public long getLastSpatialKey() {
        return lastSpatialKey;
    }

    public void setLastSpatialKey(long lastSpatialKey) {
        this.lastSpatialKey = lastSpatialKey;
    }

    private double sanitizeSpeedForTerrain(Location destination, double proposedSpeed, PathIntent intent) {
        double maxSpeed = 1.02;
        if (intent == PathIntent.EVADE_BEACON || intent == PathIntent.EVADE_LIGHT) {
            maxSpeed = 0.98;
        }
        if (isDropRiskAhead(destination)) {
            maxSpeed = Math.min(maxSpeed, 0.86);
        }
        return Math.max(0.65, Math.min(proposedSpeed, maxSpeed));
    }

    private boolean isDropRiskAhead(Location destination) {
        if (zombie == null || !zombie.isValid() || destination == null || destination.getWorld() == null) {
            return false;
        }
        Location current = zombie.getLocation();
        if (!current.getWorld().equals(destination.getWorld())) {
            return false;
        }
        if (destination.getY() < current.getY() - 0.85) {
            return true;
        }
        Vector direction = destination.toVector().subtract(current.toVector()).setY(0);
        if (direction.lengthSquared() < 0.04) {
            return false;
        }
        direction.normalize().multiply(0.9);
        Location probe = current.clone().add(direction);
        Block below = probe.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
        Block below2 = below.getRelative(org.bukkit.block.BlockFace.DOWN);
        return !below.getType().isSolid() && !below2.getType().isSolid();
    }

    private double getEnvironmentalSpeedMultiplier() {
        if (zombie == null || !zombie.isValid() || zombie.getWorld() == null) {
            return 1.0;
        }
        boolean isDay = zombie.getWorld().getTime() >= 0 && zombie.getWorld().getTime() < 12000;
        boolean strongLight = LightSourceManager.getInstance().isExposedToStrongLight(zombie.getLocation());
        if (isDay || strongLight) {
            return 0.5; // 白天/光源：速度 -50%
        }
        return 1.0;
    }

    private MovementIntent mapPathToMovementIntent(PathIntent intent) {
        if (intent == null) {
            return MovementIntent.IDLE;
        }
        return switch (intent) {
            case DIRECT_CHASE -> MovementIntent.LOCK_PURSUIT;
            case NAV_CORRIDOR -> MovementIntent.CORRIDOR;
            case STRUCTURE_BREACH_BUILD -> MovementIntent.BREACH;
            case CLOSE_COMBAT -> MovementIntent.CLOSE;
            case EVADE_BEACON -> MovementIntent.EVADE_BEACON;
            case EVADE_LIGHT -> MovementIntent.EVADE_LIGHT;
            case IDLE -> MovementIntent.IDLE;
        };
    }
}
