package com.frigidora.toomuchzombies.ai;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.ai.behavior.ZombieBreakerBehavior;
import com.frigidora.toomuchzombies.ai.behavior.ZombieBuilderBehavior;
import com.frigidora.toomuchzombies.ai.behavior.ZombieCooperationBehavior;
import com.frigidora.toomuchzombies.ai.behavior.ZombieSuicideBehavior;
import com.frigidora.toomuchzombies.enums.ZombieRole;
import com.frigidora.toomuchzombies.mechanics.BeaconManager;
import com.frigidora.toomuchzombies.mechanics.LightSourceManager;
import com.frigidora.toomuchzombies.config.ConfigManager;

public class SmartPathingBehavior {
    private final Random random = new Random();
    
    public void tick(ZombieAgent agent) {
        Zombie z = agent.getZombie();
        Location targetLoc = agent.getLastKnownTargetLocation();
        
        // --- 全局 Debuff 系统：白天/强光下减速虚弱 ---
        // 性能优化：每 20 tick (1秒) 检查一次，而不是每 tick
        if (z.getTicksLived() % 20 == 0) {
            applyLightDebuffs(z);
        }

        // 1. 获取行为模块
        ZombieBuilderBehavior builder = agent.getBuilderBehavior();
        ZombieBreakerBehavior breaker = agent.getBreakerBehavior();
        ZombieSuicideBehavior suicide = agent.getSuicideBehavior();
        ZombieCooperationBehavior cooperation = agent.getCooperationBehavior();
        LivingEntity currentTarget = agent.getTargetEntity() != null ? agent.getTargetEntity() : z.getTarget();
        if (currentTarget != null && currentTarget.isValid() && currentTarget.getWorld().equals(z.getWorld())) {
            agent.setLastKnownTargetLocation(currentTarget.getLocation());
            agent.clearInvestigationTarget();
            targetLoc = currentTarget.getLocation();
        }

        final boolean terrainModificationEnabled = ConfigManager.getInstance().isTerrainModificationEnabled();
        if (!terrainModificationEnabled) {
            if (builder.isActive()) {
                builder.setActive(false);
            }
            if (breaker.isBreaking()) {
                breaker.stopBreaking();
            }
        }

        // 2. 自爆僵尸冲锋逻辑 (最高优先级)
        if (suicide.isActive()) {
            suicide.tick();
            return; // 冲锋中，无视其他所有逻辑
        } else {
            suicide.tick(); // 检查是否有请求
            if (suicide.isActive()) return; // 如果刚刚激活了冲锋
        }

        // 3. 移动锁定检查（如果正在搭建或破坏，禁止原版移动）
        if (agent.isAiPaused() || (terrainModificationEnabled && (builder.isActive() || breaker.isBreaking()))) {
            // 只有当不是 Builder 模式，或者 Builder 明确不需要移动时才停止路径
            if (!builder.isActive()) {
                if (z.getPathfinder().hasPath()) {
                    z.getPathfinder().stopPathfinding();
                }
            }
            
            // 维持当前位置的微调
            if (builder.isActive()) {
                builder.tick(); // 委托给 Builder
                return;
            }
            if (breaker.isBreaking()) {
                breaker.tick(); // 委托给 Breaker
                return;
            }
        }
        
        // 4. 协作逻辑 (Combat Zombies)
        if (agent.getRole() != ZombieRole.BUILDER && agent.getRole() != ZombieRole.MINER && agent.getRole() != ZombieRole.SUICIDE) {
            // 有明确目标且距离不远时，减少协作重排，避免来回踱步。
            boolean suppressCoop = targetLoc != null && z.getWorld().equals(targetLoc.getWorld())
                && z.getLocation().distanceSquared(targetLoc) <= 20 * 20;
            if (!suppressCoop) {
                cooperation.tick();
            }
        }

        // 5. 信标避让（增加滞后与战斗豁免，避免来回踱步）
        if (agent.checkAndResetSkillCooldown("BEACON_CHECK", 650)) {
             Location nearestBeacon = BeaconManager.getInstance().getNearestActiveBeacon(z.getLocation(), 24.0);
             if (nearestBeacon != null) {
                 double beaconDistSq = z.getLocation().distanceSquared(nearestBeacon);
                 boolean closeCombat = currentTarget != null
                     && currentTarget.isValid()
                     && currentTarget.getWorld().equals(z.getWorld())
                     && currentTarget.getLocation().distanceSquared(z.getLocation()) <= 16.0;

                 // 已经贴身交战时不强行逃离，避免 AI 在信标边缘反复横跳。
                 if (closeCombat) {
                     z.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 40, 1));
                 } else if (beaconDistSq <= 18.0 * 18.0) {
                     z.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 40, 2));
                     z.damage(1.0);

                     Vector fleeDir = z.getLocation().toVector().subtract(nearestBeacon.toVector()).normalize();
                     Location fleeTarget = z.getLocation().add(fleeDir.multiply(14));
                     agent.setPathIntent(ZombieAgent.PathIntent.EVADE_BEACON, fleeTarget, 1200L);
                     agent.submitMoveIntent(fleeTarget, 1.2, ZombieAgent.MovementPriority.CRITICAL, ZombieAgent.PathIntent.EVADE_BEACON, 1200L);
                     return;
                 }
             }
        }

        // 6. 光源避让 (优化版)
        if (agent.checkAndResetSkillCooldown("LIGHT_CHECK", 500)) {
             // 仅检查缓存的光源位置，避免 Entity 遍历
             Location nearestLight = LightSourceManager.getInstance().getNearestLightSource(z.getLocation(), 15.0);
             
             if (nearestLight != null) {
                 applyLightDebuffs(z);

                 Vector fleeDir = z.getLocation().toVector().subtract(nearestLight.toVector()).normalize();
                 Location fleeTarget = z.getLocation().add(fleeDir.multiply(12));
                 agent.setPathIntent(ZombieAgent.PathIntent.EVADE_LIGHT, fleeTarget, 900L);
                 agent.submitMoveIntent(fleeTarget, 1.0, ZombieAgent.MovementPriority.HIGH, ZombieAgent.PathIntent.EVADE_LIGHT, 900L);
                 return;
             }
        }
        
        // 7. 自爆僵尸 常规逻辑 (如果没有冲锋)
        if (agent.getRole() == ZombieRole.SUICIDE) {
            Block target = z.getLocation().add(z.getLocation().getDirection()).getBlock();
            if (target.getType().isSolid()) {
                z.getWorld().createExplosion(z.getLocation(), 2.0F, false, true);
                z.setHealth(0);
                return;
            }
        }

        if (targetLoc == null) {
            handleNoTargetBehavior(agent);
            return;
        }

        boolean meleeEngaged = currentTarget != null
            && currentTarget.isValid()
            && currentTarget.getWorld().equals(z.getWorld())
            && agent.getRole() != ZombieRole.ARCHER
            && agent.getRole() != ZombieRole.ENDER
            && agent.getRole() != ZombieRole.NURSE
            && z.getLocation().distanceSquared(currentTarget.getLocation()) <= 3.5 * 3.5;
        if (meleeEngaged) {
            agent.clearPathFailures();
            agent.setFlanking(false);
            agent.setPathIntent(ZombieAgent.PathIntent.CLOSE_COMBAT, currentTarget.getLocation(), 650L);
            if (z.getPathfinder().hasPath()) {
                z.getPathfinder().stopPathfinding();
            }
            return;
        }

        if (agent.getTicksStuck() >= ConfigManager.getInstance().getRecoveryStuckWarningTicks()) {
            agent.notePathFailure(ZombieAgent.PathFailureType.HARD_STUCK);
            if (terrainModificationEnabled && (agent.getRole() == ZombieRole.BUILDER || agent.getRole() == ZombieRole.MINER)) {
                builder.setActive(true);
                builder.tick();
                return;
            }
            if (agent.checkAndResetSkillCooldown("FORCED_REPATH", ConfigManager.getInstance().getRecoveryRepathCooldownMs())) {
                agent.submitMoveIntent(targetLoc, 1.1, ZombieAgent.MovementPriority.HIGH, ZombieAgent.PathIntent.NAV_CORRIDOR, 750L);
            }
        }

        LivingEntity focusTarget = cooperation.getFocusTarget();
        boolean allowFormation = focusTarget != null
            && focusTarget.isValid()
            && focusTarget.getWorld().equals(z.getWorld())
            && z.getLocation().distanceSquared(focusTarget.getLocation()) >= 10.0 * 10.0
            && agent.getCurrentPathFailure() == ZombieAgent.PathFailureType.NONE;
        if (allowFormation) {
            applyFocusFormation(agent, focusTarget, cooperation.getFormationSlotIndex());
            return;
        }

        // 8. 决策：是否切换到“建筑师模式” (Structural Pathing)
        
        boolean isSpecialist = (agent.getRole() == ZombieRole.BUILDER || agent.getRole() == ZombieRole.MINER);
        
        // 增加建造意愿：专家僵尸更容易开启建筑模式，普通僵尸如果卡住较久也会尝试
        if (terrainModificationEnabled && isSpecialist) {
            if (shouldStartStructuralMode(agent, targetLoc)) {
                agent.setPathIntent(ZombieAgent.PathIntent.STRUCTURE_BREACH_BUILD, targetLoc, 1500L);
                builder.setActive(true);
                builder.tick();
                return;
            }
        }
        
        // 9. 正常移动逻辑 (Vanilla Pathfinding)
        if (terrainModificationEnabled && isSpecialist && agent.checkAndResetSkillCooldown("STRUCT_OBSTACLE_CHECK", 1000)) {
            Vector flatDir = targetLoc.toVector().subtract(z.getLocation().toVector()).setY(0);
            if (flatDir.lengthSquared() < 0.01) flatDir = z.getLocation().getDirection().setY(0);
            if (flatDir.lengthSquared() > 0.01) flatDir.normalize();
            Block feet = z.getLocation().add(flatDir).getBlock();
            Block head = feet.getRelative(BlockFace.UP);
            if (feet.getType().isSolid() || head.getType().isSolid()) {
                agent.notePathFailure(ZombieAgent.PathFailureType.SHORT_BLOCKED);
                builder.setActive(true);
                builder.tick();
                return;
            }
        }

        Location desiredTarget = resolveDesiredAnchor(agent, z, currentTarget, targetLoc);
        boolean directChase = shouldUseDirectChase(agent, z, currentTarget, desiredTarget);
        if (directChase) {
            agent.clearPathFailures();
            if (agent.canReplanPath(650L, desiredTarget, 7.0)) {
                agent.setPathIntent(ZombieAgent.PathIntent.DIRECT_CHASE, desiredTarget, 900L);
            }
            agent.submitMoveIntent(
                agent.getPathAnchor() != null ? agent.getPathAnchor() : desiredTarget,
                desiredTarget.distanceSquared(z.getLocation()) > 36.0 ? 1.0 : 1.08,
                ZombieAgent.MovementPriority.MEDIUM,
                ZombieAgent.PathIntent.DIRECT_CHASE,
                900L
            );
        } else {
            Location corridorAnchor = resolveCorridorAnchor(agent, z, currentTarget, targetLoc);
            if (corridorAnchor != null) {
                if (!z.getPathfinder().hasPath()) {
                    agent.notePathFailure(ZombieAgent.PathFailureType.PATH_MISSING);
                } else {
                    agent.notePathFailure(ZombieAgent.PathFailureType.SHORT_BLOCKED);
                }
                if (agent.canReplanPath(900L, corridorAnchor, 5.5) || !agent.isPathIntentLeased(ZombieAgent.PathIntent.NAV_CORRIDOR)) {
                    agent.setPathIntent(ZombieAgent.PathIntent.NAV_CORRIDOR, corridorAnchor, 1500L);
                }
                agent.submitMoveIntent(
                    agent.getPathAnchor() != null ? agent.getPathAnchor() : corridorAnchor,
                    1.08,
                    ZombieAgent.MovementPriority.MEDIUM,
                    ZombieAgent.PathIntent.NAV_CORRIDOR,
                    1500L
                );
            }
        }

        boolean canFlank = agent.isFlanking()
            && currentTarget != null
            && currentTarget.isValid()
            && currentTarget.getWorld().equals(z.getWorld())
            && z.getLocation().distanceSquared(currentTarget.getLocation()) >= 14.0 * 14.0
            && !agent.isPathIntentLeased(ZombieAgent.PathIntent.DIRECT_CHASE);
        if (canFlank) {
            Vector toTarget = targetLoc.toVector().subtract(z.getLocation().toVector()).setY(0);
            if (toTarget.lengthSquared() > 0.01) {
                toTarget.normalize();
                Vector right = new Vector(-toTarget.getZ(), 0, toTarget.getX()).normalize();
                Location flankTarget = currentTarget.getLocation().clone().add(right.multiply(3.0));
                flankTarget.setY(z.getLocation().getY());
                agent.submitMoveIntent(flankTarget, 1.12, ZombieAgent.MovementPriority.HIGH, ZombieAgent.PathIntent.NAV_CORRIDOR, 1000L);
                return;
            }
        }
        
        // 10. 简单的障碍物处理 (Fallback for non-specialists or when builder is not active)
        // 仅处理面前的门/玻璃等脆弱物体
        if (terrainModificationEnabled && !builder.isActive() && isSpecialist) {
            handleSimpleObstacle(agent, targetLoc, breaker);
        }
    }


    private void applyLightDebuffs(Zombie z) {
        boolean isDay = z.getWorld().getTime() >= 0 && z.getWorld().getTime() < 12000;
        boolean strongLight = LightSourceManager.getInstance().isExposedToStrongLight(z.getLocation());
        if (!isDay && !strongLight) {
            return;
        }

        z.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 40, strongLight ? 1 : 0, true, false, false));
        org.bukkit.potion.PotionEffectType slowType = org.bukkit.potion.PotionEffectType.getByName("SLOW");
        if (slowType == null) slowType = org.bukkit.potion.PotionEffectType.getByName("SLOWNESS");
        if (slowType != null) {
            int amplifier = strongLight ? 2 : 1;
            z.addPotionEffect(new org.bukkit.potion.PotionEffect(slowType, 40, amplifier, true, false, false));
        }
    }

    private void handleSimpleObstacle(ZombieAgent agent, Location targetLoc, ZombieBreakerBehavior breaker) {
        Zombie z = agent.getZombie();
        // 简单的障碍物检查
        Vector toTargetDir = targetLoc.toVector().subtract(z.getLocation().toVector()).setY(0);
        if (toTargetDir.lengthSquared() > 0.01) toTargetDir.normalize();
        else toTargetDir = z.getLocation().getDirection().setY(0).normalize();

        Block blockAheadFeet = z.getLocation().add(toTargetDir).getBlock();
        Block blockAheadHead = blockAheadFeet.getRelative(BlockFace.UP);

        if (blockAheadFeet.getType().isSolid() || blockAheadHead.getType().isSolid()) {
             // 仅当方块直接位于僵尸和目标之间时才破坏
            Vector toTarget = targetLoc.toVector().subtract(z.getLocation().toVector()).normalize();
            double dot = toTarget.dot(z.getLocation().getDirection());
            if (dot < 0.5) return;

            for (Block b : new Block[]{blockAheadFeet, blockAheadHead}) {
                if (b.getType() == Material.AIR) continue;
                if (isBreakCandidate(agent, b.getType()) && breaker.canBreak(b)) {
                    breaker.startBreaking(b);
                    return;
                }
                if (isBreachWorthy(b.getType())
                    && agent.checkAndResetSkillCooldown("BREACH_REQ_NEAR", 1200L)) {
                    ZombieAIManager.getInstance().requestBreach(b.getLocation().add(0.5, 0.5, 0.5));
                }
            }
        }
    }

    private void applyFocusFormation(ZombieAgent agent, LivingEntity focusTarget, int slotIndex) {
        Zombie z = agent.getZombie();
        Location target = focusTarget.getLocation();
        double spacing = com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getFormationSlotSpacing();
        double separationWeight = com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getFormationSeparationWeight();
        double separationRange = com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getFormationSeparationRange();

        Vector toZombie = z.getLocation().toVector().subtract(target.toVector()).setY(0);
        if (toZombie.lengthSquared() < 0.01) toZombie = z.getLocation().getDirection().setY(0);
        if (toZombie.lengthSquared() < 0.01) return;
        toZombie.normalize();

        Vector right = new Vector(-toZombie.getZ(), 0, toZombie.getX()).normalize();
        int ring = slotIndex / 6 + 1;
        int offset = slotIndex % 6;
        double angle = (Math.PI * 2.0 / 6.0) * offset;

        Vector slotVector = toZombie.clone().multiply(ring * spacing)
            .rotateAroundY(angle)
            .add(right.clone().multiply((offset - 2.5) * spacing * 0.25));

        Vector separation = new Vector(0, 0, 0);
        for (org.bukkit.entity.Entity e : z.getNearbyEntities(separationRange, 3, separationRange)) {
            if (e instanceof Zombie && !e.getUniqueId().equals(z.getUniqueId())) {
                Vector push = z.getLocation().toVector().subtract(e.getLocation().toVector()).setY(0);
                if (push.lengthSquared() > 0.001) {
                    separation.add(push.normalize().multiply(1.0 / Math.max(0.25, push.length())));
                }
            }
        }
        separation.multiply(separationWeight);

        Location slotLoc = target.clone().add(slotVector).add(separation);
        slotLoc.setY(z.getLocation().getY());
        agent.submitMoveIntent(slotLoc, 1.15, ZombieAgent.MovementPriority.MEDIUM, ZombieAgent.PathIntent.NAV_CORRIDOR, 1100L);
    }

    private void handleNoTargetBehavior(ZombieAgent agent) {
        Zombie z = agent.getZombie();
        if (agent.checkAndResetSkillCooldown("IDLE_FLOAT", 600) && z.isInWater()) {
            Location up = z.getLocation().clone().add(0, 1.5, 0);
            agent.submitMoveIntent(up, 1.0, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.IDLE, 700L);
            return;
        }

        Location investigationTarget = agent.getInvestigationTarget();
        if (investigationTarget != null && investigationTarget.getWorld() != null && investigationTarget.getWorld().equals(z.getWorld())) {
            if (z.getLocation().distanceSquared(investigationTarget) <= 4.0) {
                agent.clearInvestigationTarget();
            } else {
                agent.submitMoveIntent(investigationTarget, 1.0, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.NAV_CORRIDOR, 1100L);
                return;
            }
        }

        long time = z.getWorld().getTime();
        if (time >= 13000 && time <= 23000 && agent.checkAndResetSkillCooldown("LIGHT_INVESTIGATE", 1200)) {
            Location distantLight = LightSourceManager.getInstance().getNearestLightSource(z.getLocation(), 40.0);
            if (distantLight != null && z.getLocation().distanceSquared(distantLight) >= 12 * 12) {
                agent.setInvestigationTarget(distantLight, 8000L);
                agent.submitMoveIntent(distantLight, 0.95, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.NAV_CORRIDOR, 1200L);
                return;
            }
        }

        if (agent.checkAndResetSkillCooldown("PACK_DRIFT", 1200)) {
            Vector center = new Vector(0, 0, 0);
            int allies = 0;
            for (org.bukkit.entity.Entity entity : z.getNearbyEntities(12, 6, 12)) {
                if (entity instanceof Zombie other && !other.getUniqueId().equals(z.getUniqueId())) {
                    center.add(other.getLocation().toVector());
                    allies++;
                }
            }
            if (allies >= 2) {
                center.multiply(1.0 / allies);
                Location groupTarget = center.toLocation(z.getWorld());
                groupTarget.setY(z.getLocation().getY());
                agent.submitMoveIntent(groupTarget, 0.9, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.IDLE, 1200L);
                return;
            }
        }

        // 无目标时做低频随机巡游，避免僵尸长时间静止
        if (agent.checkAndResetSkillCooldown("IDLE_WANDER", 1800)) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 4.0 + random.nextDouble() * 3.0;
            Location target = z.getLocation().clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            Block feet = target.getBlock();
            if (!feet.getType().isSolid() && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                agent.submitMoveIntent(target, 0.9, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.IDLE, 1500L);
            }
        }
    }
    

    private boolean isBreakCandidate(ZombieAgent agent, Material material) {
        if (isFragile(material) || isWooden(material)) {
            return true;
        }

        String name = material.name();
        boolean isHardWall = name.contains("STONE") || name.contains("BRICK") || name.contains("COBBLE")
            || name.contains("TERRACOTTA") || name.contains("CONCRETE") || name.contains("DEEPSLATE");

        if (!isHardWall) {
            return false;
        }

        // 专家僵尸总是愿意拆硬质墙体；普通战斗僵尸在卡住时也会尝试。
        if (agent.getRole() == ZombieRole.BUILDER || agent.getRole() == ZombieRole.MINER) {
            return true;
        }
        return agent.isStuckCached();
    }
    private boolean isFragile(Material material) {
        String name = material.name();
        // 移除 DIRT/GRASS_BLOCK，因为需求说 "僵尸无法空手破坏泥土等" (移除特性「破坏」)
        // 除非是 MINER (Breaker)
        return name.contains("GLASS") || name.contains("DOOR") || name.contains("PANE");
    }
    
    private boolean isWooden(Material material) {
        String name = material.name();
        return name.contains("WOOD") || name.contains("LOG") || name.contains("PLANKS") || name.contains("FENCE") || name.contains("CHEST") || name.contains("BARREL");
    }

    private boolean isBreachWorthy(Material material) {
        if (material == null || material == Material.AIR || !material.isBlock()) {
            return false;
        }
        String name = material.name();
        return material.getHardness() >= 3.0f
            || name.contains("OBSIDIAN")
            || name.contains("DEEPSLATE")
            || name.contains("BRICK")
            || name.contains("CONCRETE")
            || name.contains("ANVIL");
    }

    private boolean shouldStartStructuralMode(ZombieAgent agent, Location targetLoc) {
        Zombie z = agent.getZombie();
        if (targetLoc == null || !z.getWorld().equals(targetLoc.getWorld())) {
            return agent.isStuckCached();
        }

        // 对齐 ZombieGame：受击后短时间内优先追击/战斗，而非立即开始搭建。
        if (agent.wasDamagedRecently(1500) && agent.getTicksStuck() < ConfigManager.getInstance().getRecoveryStuckTeleportTicks()) {
            return false;
        }

        if (agent.isStuckCached()) {
            return true;
        }

        ZombieAgent.PathFailureType failureType = agent.getCurrentPathFailure();
        if (failureType == ZombieAgent.PathFailureType.HARD_STUCK) {
            return true;
        }
        if (failureType == ZombieAgent.PathFailureType.PATH_MISSING && agent.getMissingPathStrikes() >= 3) {
            return true;
        }

        double distSq = z.getLocation().distanceSquared(targetLoc);
        if (distSq <= 4.0) {
            return false;
        }

        if (z.getPathfinder() != null && z.getPathfinder().hasPath()
            && distSq <= 24.0 * 24.0 && !agent.isStuckCached()) {
            return false;
        }

        double yDiff = targetLoc.getY() - z.getLocation().getY();
        if (yDiff >= 1.8 && distSq <= 16.0 * 16.0) {
            return true;
        }

        Vector dir = targetLoc.toVector().subtract(z.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.04) {
            return false;
        }
        dir.normalize();

        Block first = z.getLocation().add(dir.clone().multiply(1.0)).getBlock();
        Block firstHead = first.getRelative(BlockFace.UP);
        Block second = z.getLocation().add(dir.clone().multiply(2.0)).getBlock();
        Block secondHead = second.getRelative(BlockFace.UP);

        boolean blockedAhead = first.getType().isSolid() || firstHead.getType().isSolid()
            || second.getType().isSolid() || secondHead.getType().isSolid();
        if (blockedAhead) {
            return failureType == ZombieAgent.PathFailureType.PATH_MISSING
                || failureType == ZombieAgent.PathFailureType.HARD_STUCK
                || distSq <= 12.0 * 12.0;
        }

        Block frontGround = first.getRelative(BlockFace.DOWN);
        boolean gapAhead = !frontGround.getType().isSolid();
        if (gapAhead && distSq >= 9.0) {
            return failureType == ZombieAgent.PathFailureType.PATH_MISSING
                || failureType == ZombieAgent.PathFailureType.HARD_STUCK
                || distSq >= 18.0 * 18.0;
        }

        // 兜底：当原版寻路持续拿不到路径并且已有明显卡顿时，才切结构模式。
        return z.getPathfinder() != null
            && !z.getPathfinder().hasPath()
            && distSq >= 20.0 * 20.0
            && agent.getCurrentPathFailure() != ZombieAgent.PathFailureType.NONE;
    }

    private boolean shouldUseDirectChase(ZombieAgent agent, Zombie zombie, LivingEntity currentTarget, Location desiredTarget) {
        if (desiredTarget == null || desiredTarget.getWorld() == null || !desiredTarget.getWorld().equals(zombie.getWorld())) {
            return false;
        }
        if (agent.isPathIntentLeased(ZombieAgent.PathIntent.DIRECT_CHASE) && agent.getPathAnchor() != null) {
            return agent.getPathAnchor().getWorld() != null
                && agent.getPathAnchor().getWorld().equals(desiredTarget.getWorld())
                && agent.getPathAnchor().distanceSquared(desiredTarget) <= 10.0 * 10.0;
        }
        if (agent.getCurrentPathFailure() == ZombieAgent.PathFailureType.HARD_STUCK
            || agent.getCurrentPathFailure() == ZombieAgent.PathFailureType.PATH_MISSING) {
            return false;
        }
        double distSq = zombie.getLocation().distanceSquared(desiredTarget);
        boolean hasStablePath = zombie.getPathfinder() != null && zombie.getPathfinder().hasPath();
        boolean lineOfSight = currentTarget != null && currentTarget.isValid() && zombie.hasLineOfSight(currentTarget);
        boolean smallVerticalGap = Math.abs(desiredTarget.getY() - zombie.getLocation().getY()) <= 1.75;
        return (lineOfSight || hasStablePath) && smallVerticalGap && distSq <= 40.0 * 40.0;
    }

    private Location resolveDesiredAnchor(ZombieAgent agent, Zombie zombie, LivingEntity currentTarget, Location fallback) {
        if (currentTarget != null && currentTarget.isValid() && currentTarget.getWorld().equals(zombie.getWorld())) {
            Location predicted = currentTarget.getLocation().clone();
            double distance = zombie.getLocation().distance(predicted);
            Vector facing = currentTarget.getLocation().getDirection().setY(0);
            if (distance >= 6.0 && facing.lengthSquared() > 0.01 && zombie.hasLineOfSight(currentTarget)) {
                facing.normalize().multiply(Math.min(1.25, Math.max(0.25, distance * 0.045)));
                predicted.add(facing);
            }
            predicted.setY(zombie.getLocation().getY());
            return predicted;
        }
        return fallback == null ? null : fallback.clone();
    }

    private Location resolveCorridorAnchor(ZombieAgent agent, Zombie zombie, LivingEntity currentTarget, Location fallback) {
        Location target = currentTarget != null && currentTarget.isValid() ? currentTarget.getLocation() : fallback;
        if (target == null || target.getWorld() == null || !target.getWorld().equals(zombie.getWorld())) {
            return null;
        }

        Vector forward = target.toVector().subtract(zombie.getLocation().toVector()).setY(0);
        if (forward.lengthSquared() < 0.04) {
            return target.clone();
        }
        forward.normalize();
        Vector lateral = new Vector(-forward.getZ(), 0, forward.getX()).normalize();

        int bias = agent.getLateralBias();
        if (bias == 0) {
            bias = (Math.abs(zombie.getUniqueId().hashCode()) % 2 == 0) ? 1 : -1;
            agent.lockLateralBias(bias, 2200L);
        }

        double distance = zombie.getLocation().distance(target);
        double forwardDistance = distance < 8.0 ? 3.0 : 4.5;
        double lateralDistance = distance < 8.0 ? 1.2 : 1.8;
        Location corridor = zombie.getLocation().clone()
            .add(forward.clone().multiply(forwardDistance))
            .add(lateral.multiply(lateralDistance * bias));
        corridor.setY(zombie.getLocation().getY());
        return corridor;
    }
}
