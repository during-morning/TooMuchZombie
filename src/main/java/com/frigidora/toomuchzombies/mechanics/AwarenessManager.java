package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;

import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;
import com.frigidora.toomuchzombies.config.ConfigManager;

public class AwarenessManager {

    private static final long NOISE_INVESTIGATION_TTL_MS = 10000L;

    private static AwarenessManager instance;

    public static void initialize() {
        if (instance == null) {
            instance = new AwarenessManager();
        }
    }

    public static AwarenessManager getInstance() {
        return instance;
    }

    public void alertBloodTrail(Player player, double radius) {
        // 血气感知已彻底移除，保留空实现以兼容旧调用。
    }

    public void alertNoise(Location location, double radius, Player sourcePlayer) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        for (Entity nearby : location.getWorld().getNearbyEntities(location, radius, Math.max(8.0, radius / 2.0), radius)) {
            if (!(nearby instanceof Zombie zombie)) {
                continue;
            }
            ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
            if (agent == null) {
                continue;
            }
            if (!agent.checkAndResetSkillCooldown("NOISE_REACT", 300L)) {
                continue;
            }
            // 记录噪音提示供目标评分使用；噪音只作为“辅助手段”，不直接压过玩家位置。
            double normalizedStrength = Math.min(1.8, Math.max(0.2, radius / 16.0));
            agent.setNoiseHint(location, NOISE_INVESTIGATION_TTL_MS, normalizedStrength);

            LivingEntity currentTarget = agent.getTargetEntity();
            boolean hasValidTarget = currentTarget != null
                && currentTarget.isValid()
                && !currentTarget.isDead()
                && currentTarget.getWorld().equals(zombie.getWorld());

            if (sourcePlayer != null && sourcePlayer.isValid() && !sourcePlayer.isDead()) {
                double distSq = zombie.getLocation().distanceSquared(sourcePlayer.getLocation());
                boolean strongSignal = distSq <= Math.max(100.0, radius * radius * 0.64) || zombie.hasLineOfSight(sourcePlayer);
                boolean sameTarget = hasValidTarget && sourcePlayer.getUniqueId().equals(currentTarget.getUniqueId());
                boolean lockedOnOther = hasValidTarget
                    && !sameTarget
                    && agent.isPursuitLockedOn(currentTarget.getUniqueId());
                boolean shouldSwitch = !hasValidTarget || sameTarget;
                if (hasValidTarget && !sameTarget) {
                    double currentDistSq = zombie.getLocation().distanceSquared(currentTarget.getLocation());
                    double switchBias = ConfigManager.getInstance().getTargetingNoiseSwitchBiasDistance();
                    shouldSwitch = shouldSwitch || distSq + switchBias < currentDistSq;
                }

                if (strongSignal && shouldSwitch && !lockedOnOther) {
                    agent.clearInvestigationTarget();
                    agent.setNoiseHint(sourcePlayer.getLocation(), 1400L, 0.45);
                    agent.setFocusTargetHint(sourcePlayer, 1400L);
                    agent.setTargetLocation(sourcePlayer.getLocation());
                    agent.lockPursuitOn(sourcePlayer, 3500L);
                    continue;
                }
            }

            if (!hasValidTarget && !agent.isPursuitLocked()) {
                agent.setInvestigationTarget(location, NOISE_INVESTIGATION_TTL_MS);
            }
        }
    }

    public void alertLightAttraction(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        long time = location.getWorld().getTime();
        if (time < 12000 || time > 23000) {
            return;
        }

        for (Entity nearby : location.getWorld().getNearbyEntities(location, radius, 18, radius)) {
            if (!(nearby instanceof Zombie zombie)) {
                continue;
            }
            ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
            if (agent == null) {
                continue;
            }
            if (agent.getTargetEntity() != null && agent.getTargetEntity().isValid()) {
                continue;
            }
            agent.setInvestigationTarget(location, 8000L);
        }
    }

    public void refreshPlayerBloodState(Player player) {
        // 血气感知已彻底移除，保留空实现以兼容旧调用。
    }
}
