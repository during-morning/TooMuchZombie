package com.frigidora.toomuchzombies.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;

import com.frigidora.toomuchzombies.config.ConfigManager;

/**
 * Single writer for zombie target commitment.
 */
public class TargetCoordinator {

    private final ZombieAIManager manager;

    public TargetCoordinator(ZombieAIManager manager) {
        this.manager = manager;
    }

    public void chooseOrRefreshTarget(ZombieAgent agent) {
        if (!agent.checkAndResetSkillCooldown("TARGET_SCAN", ConfigManager.getInstance().getTargetingScanCooldownMs())) {
            return;
        }

        Zombie zombie = agent.getZombie();
        if (zombie == null || !zombie.isValid()) {
            return;
        }

        ConfigManager cfg = ConfigManager.getInstance();
        Set<EntityType> whitelist = cfg.getTargetWhitelist();
        Set<EntityType> blacklist = cfg.getTargetBlacklist();
        LivingEntity current = resolveCurrentTarget(agent, zombie, whitelist, blacklist);
        if (current != null) {
            agent.lockPursuitOn(current, 1200L);
        }

        UUID currentTargetId = current != null ? current.getUniqueId() : null;
        UUID plannedId = manager.getPlannedTarget(agent.getUuid());
        LivingEntity focusHint = agent.getFocusTargetHint();
        LivingEntity protectHint = agent.getProtectTargetHint();
        double maxRange = Math.min(
            Math.min(cfg.getTargetingMaxRange(), cfg.getHiveMindSensorRange()),
            cfg.getTargetingRecognitionRange()
        );

        if (current != null) {
            double holdRangeSq = (maxRange + 14.0) * (maxRange + 14.0);
            double distSq = zombie.getLocation().distanceSquared(current.getLocation());
            boolean recentlySeen = !agent.hasMemoryExpired(2200L);
            if ((distSq <= holdRangeSq && (zombie.hasLineOfSight(current) || recentlySeen))
                || agent.isPursuitLockedOn(current.getUniqueId())) {
                agent.lockPursuitOn(current, 2600L);
                agent.setLastKnownTargetLocation(current.getLocation());
                return;
            }
        }

        double currentScore = current != null
            ? evaluateTargetScore(agent, zombie, current, currentTargetId, plannedId, focusHint, protectHint)
            : Double.NEGATIVE_INFINITY;

        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (LivingEntity candidate : collectCandidates(agent, zombie, maxRange, focusHint, protectHint, whitelist, blacklist)) {
            double score = evaluateTargetScore(agent, zombie, candidate, currentTargetId, plannedId, focusHint, protectHint);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null) {
            if (plannedId != null) {
                org.bukkit.entity.Entity plannedEntity = org.bukkit.Bukkit.getEntity(plannedId);
                if (plannedEntity instanceof LivingEntity living
                    && isViableTarget(living, zombie, whitelist, blacklist)) {
                    commitTarget(agent, living);
                    return;
                }
            }
            return;
        }

        double delta = Math.max(cfg.getTargetingSwitchScoreDelta(), cfg.getTargetingSwitchHysteresis());
        if (current != null && !best.equals(current) && bestScore < currentScore + delta) {
            return;
        }

        if (current != null && !best.equals(current)) {
            if (zombie.hasLineOfSight(current)
                && zombie.getLocation().distanceSquared(current.getLocation()) <= 16.0 * 16.0) {
                return;
            }
            if (agent.isPursuitLockedOn(current.getUniqueId())) {
                return;
            }

            double currentDistSq = zombie.getLocation().distanceSquared(current.getLocation());
            double bestDistSq = zombie.getLocation().distanceSquared(best.getLocation());
            if (bestDistSq >= currentDistSq - 4.0) {
                return;
            }

            boolean inClosePressure = currentDistSq <= 20.0 * 20.0;
            if (inClosePressure && !agent.canCommitTargetSwitch(best.getUniqueId(), 1600L)) {
                return;
            }
        }

        commitTarget(agent, best);
    }

    private LivingEntity resolveCurrentTarget(ZombieAgent agent, Zombie zombie, Set<EntityType> whitelist, Set<EntityType> blacklist) {
        LivingEntity current = agent.getTargetEntity();
        if (isViableTarget(current, zombie, whitelist, blacklist)) {
            return current;
        }

        LivingEntity vanilla = zombie.getTarget();
        if (isViableTarget(vanilla, zombie, whitelist, blacklist)) {
            agent.setTargetEntity(vanilla);
            agent.setLastKnownTargetLocation(vanilla.getLocation());
            return vanilla;
        }

        return null;
    }

    private List<LivingEntity> collectCandidates(
        ZombieAgent agent,
        Zombie zombie,
        double maxRange,
        LivingEntity focusHint,
        LivingEntity protectHint,
        Set<EntityType> whitelist,
        Set<EntityType> blacklist
    ) {
        List<LivingEntity> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        for (LivingEntity hint : new LivingEntity[] { focusHint, protectHint }) {
            if (!isViableTarget(hint, zombie, whitelist, blacklist)) {
                continue;
            }
            double distSq = zombie.getLocation().distanceSquared(hint.getLocation());
            if (distSq <= (maxRange + 10.0) * (maxRange + 10.0) && seen.add(hint.getUniqueId())) {
                out.add(hint);
            }
        }

        double rangeSq = maxRange * maxRange;
        for (Player player : zombie.getWorld().getPlayers()) {
            if (!isViableTarget(player, zombie, whitelist, blacklist)) {
                continue;
            }
            double distSq = player.getLocation().distanceSquared(zombie.getLocation());
            boolean los = zombie.hasLineOfSight(player);
            if (distSq <= rangeSq || (los && distSq <= (maxRange + 8.0) * (maxRange + 8.0))) {
                if (seen.add(player.getUniqueId())) {
                    out.add(player);
                }
            }
        }

        for (Entity nearby : zombie.getNearbyEntities(maxRange, Math.max(10.0, maxRange * 0.6), maxRange)) {
            if (!(nearby instanceof LivingEntity living)) {
                continue;
            }
            if (seen.contains(living.getUniqueId())) {
                continue;
            }
            if (!isViableTarget(living, zombie, whitelist, blacklist)) {
                continue;
            }
            if (zombie.getLocation().distanceSquared(living.getLocation()) <= rangeSq) {
                seen.add(living.getUniqueId());
                out.add(living);
            }
        }

        LivingEntity current = agent.getTargetEntity();
        if (isViableTarget(current, zombie, whitelist, blacklist) && seen.add(current.getUniqueId())) {
            out.add(current);
        }

        return out;
    }

    private boolean isViableTarget(LivingEntity target, Zombie zombie, Set<EntityType> whitelist, Set<EntityType> blacklist) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (target.getWorld() == null || !target.getWorld().equals(zombie.getWorld())) {
            return false;
        }
        if (target.equals(zombie)) {
            return false;
        }
        if (target.isInvulnerable()) {
            return false;
        }

        if (blacklist.contains(target.getType())) {
            return false;
        }

        if (target instanceof Player player) {
            return player.getGameMode() != GameMode.SPECTATOR && player.getGameMode() != GameMode.CREATIVE;
        }

        if (target instanceof Villager || target instanceof IronGolem) {
            return true;
        }

        return whitelist.contains(target.getType());
    }

    private void commitTarget(ZombieAgent agent, LivingEntity best) {
        Zombie zombie = agent.getZombie();
        if (zombie == null || !zombie.isValid() || best == null || !best.isValid()) {
            return;
        }
        zombie.setTarget(best);
        agent.clearInvestigationTarget();
        agent.setTargetEntity(best);
        agent.markTargetCommitted(best);
        agent.lockPursuitOn(best, 4200L);
        agent.setLastKnownTargetLocation(best.getLocation());
    }

    private double evaluateTargetScore(
        ZombieAgent agent,
        Zombie zombie,
        LivingEntity target,
        UUID currentTargetId,
        UUID plannedId,
        LivingEntity focusHint,
        LivingEntity protectHint
    ) {
        if (!zombie.getWorld().equals(target.getWorld())) {
            return Double.NEGATIVE_INFINITY;
        }

        ConfigManager cfg = ConfigManager.getInstance();
        double distSq = zombie.getLocation().distanceSquared(target.getLocation());
        double distance = Math.max(1.0, Math.sqrt(distSq));
        double positionWeight = cfg.getTargetingPlayerPositionWeight();
        double noiseWeight = Math.min(positionWeight * 0.45, cfg.getTargetingNoiseSourceWeight());
        double distanceScore = positionWeight * (1.0 / distance);

        double maxHealth = target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
            ? target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()
            : 20.0;
        double healthScore = 1.0 - Math.max(0.0, Math.min(1.0, target.getHealth() / Math.max(1.0, maxHealth)));

        boolean lineOfSight = zombie.hasLineOfSight(target);
        double lineOfSightBonus = lineOfSight ? 0.42 : 0.0;
        double verticalPenalty = Math.min(0.38, Math.abs(target.getLocation().getY() - zombie.getLocation().getY()) * 0.03);

        Location noiseHint = agent.getNoiseHintLocation();
        double noiseScore = 0.0;
        if (noiseHint != null && noiseHint.getWorld() != null && noiseHint.getWorld().equals(zombie.getWorld())) {
            double hintDist = Math.max(1.0, target.getLocation().distance(noiseHint));
            noiseScore = noiseWeight * agent.getNoiseHintStrength() * (1.0 / hintDist);
        }

        int foodLevel = target instanceof Player player ? player.getFoodLevel() : 20;
        double hungerScore = target instanceof Player ? (1.0 - Math.max(0.0, Math.min(1.0, foodLevel / 20.0))) * 0.45 : 0.0;
        double currentBonus = currentTargetId != null && currentTargetId.equals(target.getUniqueId()) ? 0.34 : 0.0;
        double planningBonus = plannedId != null && plannedId.equals(target.getUniqueId()) ? 0.10 : 0.0;
        double focusBonus = focusHint != null && focusHint.getUniqueId().equals(target.getUniqueId()) ? 0.20 : 0.0;
        double protectBonus = protectHint != null && protectHint.getUniqueId().equals(target.getUniqueId()) ? 0.18 : 0.0;
        double pursuitBonus = agent.isPursuitLockedOn(target.getUniqueId()) ? 0.24 : 0.0;

        return distanceScore
            + healthScore * 0.35
            + hungerScore
            + lineOfSightBonus
            + noiseScore
            + currentBonus
            + planningBonus
            + focusBonus
            + protectBonus
            + pursuitBonus
            - verticalPenalty;
    }
}
