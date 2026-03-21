package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;

import com.frigidora.toomuchzombies.ai.HiveMindManager;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;

public class AwarenessManager {

    private static final double DEFAULT_INVESTIGATION_TTL_MS = 10000.0;

    private static AwarenessManager instance;
    private final HiveMindManager hiveMindManager = new HiveMindManager();
    private final Particle.DustOptions bloodDust = new Particle.DustOptions(Color.fromRGB(160, 0, 0), 1.2f);

    public static void initialize() {
        if (instance == null) {
            instance = new AwarenessManager();
        }
    }

    public static AwarenessManager getInstance() {
        return instance;
    }

    public void alertBloodTrail(Player player, double radius) {
        if (player == null || !player.isValid() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Location smellOrigin = player.getLocation();
        smellOrigin.getWorld().spawnParticle(Particle.DUST, smellOrigin.clone().add(0, 0.1, 0), 6, 0.25, 0.05, 0.25, 0.01, bloodDust);
        Zombie relay = findNearbyZombie(smellOrigin, radius);
        if (relay != null) {
            hiveMindManager.broadcastTarget(player, relay);
        }

        for (Entity nearby : player.getWorld().getNearbyEntities(smellOrigin, radius, Math.max(8.0, radius / 2.0), radius)) {
            if (!(nearby instanceof Zombie zombie)) {
                continue;
            }
            ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
            if (agent == null) {
                continue;
            }
            agent.setTargetEntity(player);
            agent.setTargetLocation(player.getLocation());
            agent.setInvestigationTarget(player.getLocation(), (long) DEFAULT_INVESTIGATION_TTL_MS);
            zombie.setTarget(player);
        }
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

            agent.setInvestigationTarget(location, (long) DEFAULT_INVESTIGATION_TTL_MS);
            if (sourcePlayer != null && sourcePlayer.isValid() && !sourcePlayer.isDead()) {
                double distSq = zombie.getLocation().distanceSquared(sourcePlayer.getLocation());
                if (distSq <= Math.max(100.0, radius * radius * 0.64) || zombie.hasLineOfSight(sourcePlayer)) {
                    agent.setTargetEntity(sourcePlayer);
                    agent.setTargetLocation(sourcePlayer.getLocation());
                    zombie.setTarget(sourcePlayer);
                }
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
        if (player == null || !player.isValid() || player.isDead()) {
            return;
        }

        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
            ? player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()
            : 20.0;
        double healthRatio = player.getHealth() / Math.max(1.0, maxHealth);
        int foodLevel = player.getFoodLevel();

        if (healthRatio > 0.75 && foodLevel > 6 && player.getNoDamageTicks() > 0) {
            return;
        }

        double radius = 18.0;
        if (healthRatio <= 0.55) radius += 10.0;
        if (healthRatio <= 0.35) radius += 12.0;
        if (foodLevel <= 6) radius += 8.0;
        if (foodLevel <= 3) radius += 8.0;
        alertBloodTrail(player, radius);
    }

    private Zombie findNearbyZombie(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        Zombie best = null;
        double bestDistSq = radius * radius;
        for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius / 2.0, radius)) {
            if (!(entity instanceof Zombie zombie)) {
                continue;
            }
            if (ZombieAIManager.getInstance().getAgent(zombie.getUniqueId()) == null) {
                continue;
            }
            double distSq = zombie.getLocation().distanceSquared(location);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = zombie;
            }
        }
        return best;
    }
}
