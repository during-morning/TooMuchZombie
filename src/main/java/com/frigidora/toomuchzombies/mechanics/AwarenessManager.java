package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;

import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;

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

            if (sourcePlayer != null && sourcePlayer.isValid() && !sourcePlayer.isDead()) {
                double distSq = zombie.getLocation().distanceSquared(sourcePlayer.getLocation());
                if (distSq <= Math.max(100.0, radius * radius * 0.64) || zombie.hasLineOfSight(sourcePlayer)) {
                    agent.clearInvestigationTarget();
                    agent.setTargetEntity(sourcePlayer);
                    agent.setTargetLocation(sourcePlayer.getLocation());
                    zombie.setTarget(sourcePlayer);
                    continue;
                }
            }

            if (agent.getTargetEntity() == null || !agent.getTargetEntity().isValid()) {
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
