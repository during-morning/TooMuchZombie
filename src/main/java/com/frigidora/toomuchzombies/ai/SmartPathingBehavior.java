package com.frigidora.toomuchzombies.ai;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.TooMuchZombies;
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
                     if (TooMuchZombies.getNMSHandler() != null) {
                         TooMuchZombies.getNMSHandler().moveTo(z, fleeTarget, 1.2);
                     }
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
                 z.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, 60, 0));

                 Vector fleeDir = z.getLocation().toVector().subtract(nearestLight.toVector()).normalize();
                 Location fleeTarget = z.getLocation().add(fleeDir.multiply(12));
                 
                 if (TooMuchZombies.getNMSHandler() != null) {
                     TooMuchZombies.getNMSHandler().moveTo(z, fleeTarget, 1.0);
                 }
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
            if (z.getPathfinder().hasPath()) {
                z.getPathfinder().stopPathfinding();
            }
            return;
        }

        if (agent.getTicksStuck() >= ConfigManager.getInstance().getRecoveryStuckWarningTicks()) {
            if (terrainModificationEnabled && (agent.getRole() == ZombieRole.BUILDER || agent.getRole() == ZombieRole.MINER)) {
                builder.setActive(true);
                builder.tick();
                return;
            }
            if (agent.checkAndResetSkillCooldown("FORCED_REPATH", ConfigManager.getInstance().getRecoveryRepathCooldownMs())) {
                agent.moveTo(targetLoc, 1.1);
            }
        }

        LivingEntity focusTarget = cooperation.getFocusTarget();
        boolean allowFormation = focusTarget != null
            && focusTarget.isValid()
            && focusTarget.getWorld().equals(z.getWorld())
            && z.getLocation().distanceSquared(focusTarget.getLocation()) >= 8.0 * 8.0;
        if (allowFormation) {
            applyFocusFormation(agent, focusTarget, cooperation.getFormationSlotIndex());
            return;
        }

        // 8. 决策：是否切换到“建筑师模式” (Structural Pathing)
        
        boolean isSpecialist = (agent.getRole() == ZombieRole.BUILDER || agent.getRole() == ZombieRole.MINER);
        
        // 增加建造意愿：专家僵尸更容易开启建筑模式，普通僵尸如果卡住较久也会尝试
        if (terrainModificationEnabled && isSpecialist) {
            if (agent.isStuck() || Math.random() < 0.05) { // 专家有 5% 概率主动开启建筑模式
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
                builder.setActive(true);
                builder.tick();
                return;
            }
        }

        // 侧翼逻辑 (保持不变)
        boolean canFlank = agent.isFlanking()
            && currentTarget != null
            && currentTarget.isValid()
            && currentTarget.getWorld().equals(z.getWorld())
            && z.getLocation().distanceSquared(currentTarget.getLocation()) >= 9.0 * 9.0;
        if (canFlank) {
            Vector toTarget = targetLoc.toVector().subtract(z.getLocation().toVector()).setY(0);
            if (toTarget.lengthSquared() > 0.01) {
                toTarget.normalize();
                Vector right = new Vector(-toTarget.getZ(), 0, toTarget.getX()).normalize();
                Location flankTarget = currentTarget.getLocation().clone().add(right.multiply(3.0));
                flankTarget.setY(z.getLocation().getY());
                agent.moveTo(flankTarget, 1.12);
                return;
            }
        } else {
            // 确保没有被锁定移动
            if (!agent.isAiPaused() && currentTarget != null) {
                agent.moveTo(currentTarget.getLocation(), currentTarget.getLocation().distanceSquared(z.getLocation()) > 36.0 ? 1.0 : 1.08);
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
        agent.moveTo(slotLoc, 1.15);
    }

    private void handleNoTargetBehavior(ZombieAgent agent) {
        Zombie z = agent.getZombie();
        if (agent.checkAndResetSkillCooldown("IDLE_FLOAT", 600) && z.isInWater()) {
            Location up = z.getLocation().clone().add(0, 1.5, 0);
            agent.moveTo(up, 1.0);
            return;
        }

        Location investigationTarget = agent.getInvestigationTarget();
        if (investigationTarget != null && investigationTarget.getWorld() != null && investigationTarget.getWorld().equals(z.getWorld())) {
            if (z.getLocation().distanceSquared(investigationTarget) <= 4.0) {
                agent.clearInvestigationTarget();
            } else {
                agent.moveTo(investigationTarget, 1.0);
                return;
            }
        }

        long time = z.getWorld().getTime();
        if (time >= 13000 && time <= 23000 && agent.checkAndResetSkillCooldown("LIGHT_INVESTIGATE", 1200)) {
            Location distantLight = LightSourceManager.getInstance().getNearestLightSource(z.getLocation(), 40.0);
            if (distantLight != null && z.getLocation().distanceSquared(distantLight) >= 12 * 12) {
                agent.setInvestigationTarget(distantLight, 8000L);
                agent.moveTo(distantLight, 0.95);
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
                agent.moveTo(groupTarget, 0.9);
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
                agent.moveTo(target, 0.9);
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
        return agent.isStuck();
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
}
