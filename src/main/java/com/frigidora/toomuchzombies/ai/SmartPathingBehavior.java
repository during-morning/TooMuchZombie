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
import com.frigidora.toomuchzombies.mechanics.LightSourceManager;
import com.frigidora.toomuchzombies.config.ConfigManager;

public class SmartPathingBehavior {
    private final Random random = new Random();
    private final RouteController routeController;

    public SmartPathingBehavior(RouteController routeController) {
        this.routeController = routeController;
    }
    
    public void tick(ZombieAgent agent) {
        Zombie z = agent.getZombie();
        Location targetLoc = agent.getLastKnownTargetLocation();
        boolean overloadMode = ZombieAIManager.getInstance().isOverloadMode();
        
        // --- 优先级 0: 检查是否正在执行其他行为 ---
        if (agent.isBusy()) {
            // 正在破坏或建造，停止移动让行为完成
            if (z.getPathfinder().hasPath()) {
                z.getPathfinder().stopPathfinding();
            }
            // 继续执行当前行为
            ZombieBuilderBehavior builder = agent.getBuilderBehavior();
            ZombieBreakerBehavior breaker = agent.getBreakerBehavior();
            if (builder.isActive()) {
                builder.tick();
                return;
            }
            if (breaker.isBreaking()) {
                breaker.tick();
                return;
            }
        }
        
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

        final boolean terrainModificationEnabled = ConfigManager.getInstance().isTerrainModificationEnabled() && !overloadMode;
        if (!terrainModificationEnabled) {
            if (builder.isActive()) {
                builder.setActive(false);
            }
            if (breaker.isBreaking()) {
                breaker.stopBreaking();
            }
        }

        if (targetLoc != null && !isChunkLoaded(targetLoc)) {
            Location clipped = clipToLoadedCorridor(z, targetLoc);
            if (clipped != null) {
                targetLoc = clipped;
            } else {
                handleNoTargetBehavior(agent);
                return;
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
        if (agent.isAiPaused()) {
            // AI 暂停，完全停止
            if (z.getPathfinder().hasPath()) {
                z.getPathfinder().stopPathfinding();
            }
            return;
        }
        
        // 如果正在执行结构性行为，已经在上面处理过了，这里不应该到达
        // 但为了安全起见，再次检查
        if (terrainModificationEnabled && (builder.isActive() || breaker.isBreaking())) {
            // 这种情况不应该发生，因为上面已经 return 了
            // 但如果发生了，确保停止移动
            if (z.getPathfinder().hasPath()) {
                z.getPathfinder().stopPathfinding();
            }
            return;
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

        // 5. 信标强制区：持续伤害 + 强制驱离（战斗贴脸短窗豁免改路）
        if (routeController.enforceBeaconZone(agent, currentTarget)) {
            return;
        }

        // 6. 光源避让 (优化版)
        if (agent.checkAndResetSkillCooldown("LIGHT_CHECK", 500)) {
             // 仅检查缓存的光源位置，避免 Entity 遍历
             Location nearestLight = LightSourceManager.getInstance().getNearestLightSource(z.getLocation(), 15.0);
             
             if (nearestLight != null) {
                 applyLightDebuffs(z);

                 Vector fleeDir = z.getLocation().toVector().subtract(nearestLight.toVector()).setY(0);
                 if (fleeDir.lengthSquared() < 0.04) {
                     fleeDir = z.getLocation().getDirection().setY(0);
                 }
                 if (fleeDir.lengthSquared() < 0.04) {
                     return;
                 }
                 fleeDir.normalize();
                 Location fleeTarget = z.getLocation().add(fleeDir.multiply(12));
                 routeController.applyRouteState(agent, RouteController.RouteState.RECOVER, ZombieAgent.PathIntent.EVADE_LIGHT, fleeTarget, 900L);
                 agent.submitMoveIntent(fleeTarget, 0.90, ZombieAgent.MovementPriority.HIGH, ZombieAgent.PathIntent.EVADE_LIGHT, 900L);
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
            Location remembered = agent.getLastKnownTargetLocation();
            if (remembered != null
                && remembered.getWorld() != null
                && remembered.getWorld().equals(z.getWorld())
                && !agent.hasMemoryExpired(ConfigManager.getInstance().getRoutingLastKnownMemoryMs())) {
                routeController.applyRouteState(agent, RouteController.RouteState.CORRIDOR, ZombieAgent.PathIntent.NAV_CORRIDOR, remembered, 1400L);
                agent.submitMoveIntent(remembered, 0.92, ZombieAgent.MovementPriority.MEDIUM, ZombieAgent.PathIntent.NAV_CORRIDOR, 1400L);
                return;
            }
            handleNoTargetBehavior(agent);
            return;
        }

        // 保底机制：如果有目标但没有其他移动指令，至少尝试直接移动到目标
        boolean hasMovementPlan = false;

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
            routeController.applyRouteState(agent, RouteController.RouteState.CLOSE, ZombieAgent.PathIntent.CLOSE_COMBAT, currentTarget.getLocation(), 650L);
            if (z.getPathfinder().hasPath()) {
                z.getPathfinder().stopPathfinding();
            }
            hasMovementPlan = true;
            return;
        }

        if (agent.getTicksStuck() >= ConfigManager.getInstance().getRecoveryStuckWarningTicks()) {
            agent.notePathFailure(ZombieAgent.PathFailureType.HARD_STUCK);
            if (terrainModificationEnabled && (agent.getRole() == ZombieRole.BUILDER || agent.getRole() == ZombieRole.MINER)) {
                builder.setActive(true);
                builder.tick();
                hasMovementPlan = true;
                return;
            }
            if (agent.checkAndResetSkillCooldown("FORCED_REPATH", ConfigManager.getInstance().getRecoveryRepathCooldownMs())) {
                routeController.applyRouteState(agent, RouteController.RouteState.RECOVER, ZombieAgent.PathIntent.NAV_CORRIDOR, targetLoc, 750L);
                agent.submitMoveIntent(targetLoc, 0.96, ZombieAgent.MovementPriority.HIGH, ZombieAgent.PathIntent.NAV_CORRIDOR, 750L);
                hasMovementPlan = true;
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
            boolean structureLease = agent.isPathIntentLeased(ZombieAgent.PathIntent.STRUCTURE_BREACH_BUILD);
            ZombieAgent.PathFailureType failureType = agent.getCurrentPathFailure();
            boolean allowStructureEnter = structureLease
                || failureType == ZombieAgent.PathFailureType.PATH_MISSING
                || failureType == ZombieAgent.PathFailureType.HARD_STUCK;
            if (allowStructureEnter
                && (structureLease || agent.checkAndResetSkillCooldown("STRUCT_MODE_ENTER", 1800L))
                && shouldStartStructuralMode(agent, targetLoc)) {
                routeController.applyRouteState(agent, RouteController.RouteState.BREACH, ZombieAgent.PathIntent.STRUCTURE_BREACH_BUILD, targetLoc, 1500L);
                if (!builder.isActive()) {
                    ZombieAIManager.getInstance().recordAiRuntimeStat("breachEntries");
                }
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
            double distSq = desiredTarget.distanceSquared(z.getLocation());
            if (routeController.allowReplan(agent, desiredTarget, distSq, 7.0)
                || !agent.isPathIntentLeased(ZombieAgent.PathIntent.DIRECT_CHASE)) {
                routeController.applyRouteState(agent, RouteController.RouteState.LOCK_PURSUIT, ZombieAgent.PathIntent.DIRECT_CHASE, desiredTarget, 900L);
            }
            agent.submitMoveIntent(
                agent.getPathAnchor() != null ? agent.getPathAnchor() : desiredTarget,
                distSq > 36.0 ? 0.92 : 0.96,
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
                ZombieAIManager.getInstance().recordAiRuntimeStat("corridorFallbacks");
                double distSq = corridorAnchor.distanceSquared(z.getLocation());
                if (routeController.allowReplan(agent, corridorAnchor, distSq, 5.5)
                    || !agent.isPathIntentLeased(ZombieAgent.PathIntent.NAV_CORRIDOR)) {
                    routeController.applyRouteState(agent, RouteController.RouteState.CORRIDOR, ZombieAgent.PathIntent.NAV_CORRIDOR, corridorAnchor, 1500L);
                }
                agent.submitMoveIntent(
                    agent.getPathAnchor() != null ? agent.getPathAnchor() : corridorAnchor,
                    0.94,
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
                routeController.applyRouteState(agent, RouteController.RouteState.CORRIDOR, ZombieAgent.PathIntent.NAV_CORRIDOR, flankTarget, 1000L);
                agent.submitMoveIntent(flankTarget, 0.97, ZombieAgent.MovementPriority.HIGH, ZombieAgent.PathIntent.NAV_CORRIDOR, 1000L);
                return;
            }
        }
        
        // 10. 简单的障碍物处理 (Fallback for non-specialists or when builder is not active)
        // 仅处理面前的门/玻璃等脆弱物体
        if (terrainModificationEnabled && !builder.isActive() && isSpecialist) {
            handleSimpleObstacle(agent, targetLoc, breaker);
        }
        
        // 11. 保底机制：如果有目标但没有提交任何移动指令，强制尝试移动到目标
        if (!hasMovementPlan && targetLoc != null && targetLoc.getWorld() != null && targetLoc.getWorld().equals(z.getWorld())) {
            double distSq = z.getLocation().distanceSquared(targetLoc);
            if (distSq > 4.0) { // 距离大于2格才移动
                agent.submitMoveIntent(targetLoc, 0.90, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.NAV_CORRIDOR, 800L);
                ZombieAIManager.getInstance().recordAiRuntimeStat("fallbackMoves");
            }
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
        if (!agent.checkAndResetSkillCooldown("FORMATION_RECOMPUTE", 220L)) {
            Location cached = agent.getPathAnchor();
            if (cached != null
                && cached.getWorld() != null
                && cached.getWorld().equals(z.getWorld())) {
                agent.submitMoveIntent(cached, 0.92, ZombieAgent.MovementPriority.MEDIUM, ZombieAgent.PathIntent.NAV_CORRIDOR, 600L);
            }
            return;
        }
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
        routeController.applyRouteState(agent, RouteController.RouteState.CORRIDOR, ZombieAgent.PathIntent.NAV_CORRIDOR, slotLoc, 1100L);
        agent.submitMoveIntent(slotLoc, 0.92, ZombieAgent.MovementPriority.MEDIUM, ZombieAgent.PathIntent.NAV_CORRIDOR, 1100L);
    }

    private void handleNoTargetBehavior(ZombieAgent agent) {
        Zombie z = agent.getZombie();
        if (agent.getRole() == ZombieRole.BUILDER || agent.getRole() == ZombieRole.MINER) {
            return;
        }
        if (agent.checkAndResetSkillCooldown("IDLE_FLOAT", 600) && z.isInWater()) {
            Location up = z.getLocation().clone().add(0, 1.5, 0);
            routeController.applyRouteState(agent, RouteController.RouteState.RECOVER, ZombieAgent.PathIntent.IDLE, up, 700L);
            agent.submitMoveIntent(up, 1.0, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.IDLE, 700L);
            return;
        }

        Location investigationTarget = agent.getInvestigationTarget();
        if (investigationTarget != null && investigationTarget.getWorld() != null && investigationTarget.getWorld().equals(z.getWorld())) {
            if (z.getLocation().distanceSquared(investigationTarget) <= 4.0) {
                agent.clearInvestigationTarget();
            } else {
                routeController.applyRouteState(agent, RouteController.RouteState.CORRIDOR, ZombieAgent.PathIntent.NAV_CORRIDOR, investigationTarget, 1100L);
                agent.submitMoveIntent(investigationTarget, 1.0, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.NAV_CORRIDOR, 1100L);
                return;
            }
        }

        long time = z.getWorld().getTime();
        if (time >= 13000 && time <= 23000 && agent.checkAndResetSkillCooldown("LIGHT_INVESTIGATE", 1200)) {
            Location distantLight = LightSourceManager.getInstance().getNearestLightSource(z.getLocation(), 40.0);
            if (distantLight != null && z.getLocation().distanceSquared(distantLight) >= 12 * 12) {
                agent.setInvestigationTarget(distantLight, 8000L);
                routeController.applyRouteState(agent, RouteController.RouteState.CORRIDOR, ZombieAgent.PathIntent.NAV_CORRIDOR, distantLight, 1200L);
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
                routeController.applyRouteState(agent, RouteController.RouteState.RECOVER, ZombieAgent.PathIntent.IDLE, groupTarget, 1200L);
                agent.submitMoveIntent(groupTarget, 0.9, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.IDLE, 1200L);
                return;
            }
        }

        // 无目标时做低频随机巡游，且仅在长时间完全丢失目标后再启用。
        if (agent.isPursuitLocked() || agent.getPathIntent() == ZombieAgent.PathIntent.NAV_CORRIDOR) {
            return;
        }
        if (!agent.hasMemoryExpired(15000L)) {
            return;
        }
        if (agent.checkAndResetSkillCooldown("IDLE_WANDER", 1800)) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 4.0 + random.nextDouble() * 3.0;
            Location target = z.getLocation().clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            Block feet = target.getBlock();
            if (!feet.getType().isSolid() && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                routeController.applyRouteState(agent, RouteController.RouteState.RECOVER, ZombieAgent.PathIntent.IDLE, target, 1500L);
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

        // 参考 ZombieGame 的简化逻辑：
        // 1. 最近受伤 -> 优先战斗，不建造
        if (agent.wasDamagedRecently(1500)) {
            return false;
        }

        // 2. 已经卡住 -> 立即建造
        if (agent.isStuckCached()) {
            return true;
        }

        // 3. 核心条件：无法寻路 + 距离适中 + 冷却完成
        boolean noPath = z.getPathfinder() == null || !z.getPathfinder().hasPath();
        double distSq = z.getLocation().distanceSquared(targetLoc);
        boolean distanceOk = distSq > 9.0 && distSq <= 400.0; // 3-20 格
        
        if (!noPath || !distanceOk) {
            return false;
        }

        // 4. 检查是否有明显的障碍物
        Vector dir = targetLoc.toVector().subtract(z.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.04) {
            return false;
        }
        dir.normalize();

        // 检查前方 2 格是否有障碍
        Block first = z.getLocation().add(dir.clone().multiply(1.0)).getBlock();
        Block firstHead = first.getRelative(BlockFace.UP);
        Block second = z.getLocation().add(dir.clone().multiply(2.0)).getBlock();
        Block secondHead = second.getRelative(BlockFace.UP);

        boolean blockedAhead = first.getType().isSolid() || firstHead.getType().isSolid()
            || second.getType().isSolid() || secondHead.getType().isSolid();
        
        // 检查前方是否有坑
        Block frontGround = first.getRelative(BlockFace.DOWN);
        boolean gapAhead = !frontGround.getType().isSolid();
        
        // 检查高度差
        double yDiff = targetLoc.getY() - z.getLocation().getY();
        boolean highWall = yDiff >= 1.8;

        // 满足任一条件即可建造：
        // - 前方有障碍物
        // - 前方有坑
        // - 目标在高处
        return blockedAhead || gapAhead || highWall;
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
        if (hasDropRiskAhead(zombie, desiredTarget)) {
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

        ConfigManager cfg = ConfigManager.getInstance();
        int bias = agent.getStrafeLockDirection();
        if (bias == 0) {
            bias = (Math.abs(zombie.getUniqueId().hashCode()) % 2 == 0) ? 1 : -1;
            agent.lockStrafeDirection(bias, cfg.getPathingLaneLockMs());
        }

        double distance = zombie.getLocation().distance(target);
        double forwardDistance = distance < 8.0 ? 3.0 : 4.5;
        double lateralDistance = distance < 8.0 ? 1.2 : 1.8;
        int desiredBias = currentTarget != null
            ? chooseBiasTowardTarget(agent, zombie, currentTarget, bias, cfg.getPathingStrafeReverseFailureThreshold())
            : bias;
        if (desiredBias != bias) {
            bias = desiredBias;
            agent.lockStrafeDirection(bias, cfg.getPathingLaneLockMs());
        }
        Location corridor = zombie.getLocation().clone()
            .add(forward.clone().multiply(forwardDistance))
            .add(lateral.multiply(lateralDistance * bias));
        corridor.setY(zombie.getLocation().getY());
        return corridor;
    }

    private int chooseBiasTowardTarget(
        ZombieAgent agent,
        Zombie zombie,
        LivingEntity currentTarget,
        int currentBias,
        int reverseFailureThreshold
    ) {
        Vector toTarget = currentTarget.getLocation().toVector().subtract(zombie.getLocation().toVector()).setY(0);
        if (toTarget.lengthSquared() < 0.04) {
            return currentBias;
        }
        Vector facing = zombie.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 0.04) {
            return currentBias;
        }
        double cross = facing.getX() * toTarget.getZ() - facing.getZ() * toTarget.getX();
        int desired = cross >= 0 ? 1 : -1;
        if (desired == currentBias) {
            return currentBias;
        }
        if (!agent.canReverseStrafeDirection(desired, reverseFailureThreshold)) {
            return currentBias;
        }
        return desired;
    }

    private boolean hasDropRiskAhead(Zombie zombie, Location desiredTarget) {
        if (zombie == null || !zombie.isValid() || desiredTarget == null || desiredTarget.getWorld() == null) {
            return false;
        }
        if (!zombie.getWorld().equals(desiredTarget.getWorld())) {
            return false;
        }
        if (desiredTarget.getY() < zombie.getLocation().getY() - 0.85) {
            return true;
        }
        Vector direction = desiredTarget.toVector().subtract(zombie.getLocation().toVector()).setY(0);
        if (direction.lengthSquared() < 0.04) {
            return false;
        }
        direction.normalize().multiply(0.9);
        Location probe = zombie.getLocation().clone().add(direction);
        Block below = probe.getBlock().getRelative(BlockFace.DOWN);
        Block below2 = below.getRelative(BlockFace.DOWN);
        return !below.getType().isSolid() && !below2.getType().isSolid();
    }

    private boolean isChunkLoaded(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        return loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    private Location clipToLoadedCorridor(Zombie zombie, Location desiredTarget) {
        if (zombie == null || !zombie.isValid() || desiredTarget == null || desiredTarget.getWorld() == null) {
            return null;
        }
        Location current = zombie.getLocation();
        if (!current.getWorld().equals(desiredTarget.getWorld())) {
            return null;
        }
        Vector direction = desiredTarget.toVector().subtract(current.toVector()).setY(0);
        if (direction.lengthSquared() < 0.04) {
            return null;
        }
        direction.normalize();

        Location best = null;
        for (int i = 1; i <= 8; i++) {
            Location probe = current.clone().add(direction.clone().multiply(i * 1.2));
            probe.setY(current.getY());
            if (!isChunkLoaded(probe)) {
                break;
            }
            best = probe;
        }
        return best;
    }
}
