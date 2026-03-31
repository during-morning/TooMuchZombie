package com.frigidora.toomuchzombies.ai;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.mechanics.BeaconManager;

/**
 * Single route-state controller for movement output.
 */
public class RouteController {

    public enum RouteState {
        LOCK_PURSUIT,
        CORRIDOR,
        BREACH,
        CLOSE,
        RECOVER
    }

    private final ZombieAIManager manager;

    public RouteController(ZombieAIManager manager) {
        this.manager = manager;
    }

    public boolean enforceBeaconZone(ZombieAgent agent, LivingEntity combatTarget) {
        Zombie zombie = agent.getZombie();
        if (zombie == null || !zombie.isValid()) {
            return false;
        }

        ConfigManager cfg = ConfigManager.getInstance();
        double radius = cfg.getBeaconForceRadius();
        Location nearestBeacon = BeaconManager.getInstance().getNearestActiveBeacon(zombie.getLocation(), radius);
        if (nearestBeacon == null) {
            return false;
        }

        zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 30, 1, true, false, false));
        if (agent.checkAndResetSkillCooldown("BEACON_FORCE_DOT", 1000L)) {
            double damage = cfg.getBeaconForceDamagePerSecond();
            if (damage > 0.0) {
                zombie.damage(damage);
            }
        }

        boolean inCloseCombat = combatTarget != null
            && combatTarget.isValid()
            && combatTarget.getWorld().equals(zombie.getWorld())
            && zombie.getLocation().distanceSquared(combatTarget.getLocation())
                <= cfg.getBeaconCombatExemptDistance() * cfg.getBeaconCombatExemptDistance();

        if (inCloseCombat) {
            agent.setMovementIntent(ZombieAgent.MovementIntent.CLOSE, 350L);
            return false;
        }

        Vector fleeDir = zombie.getLocation().toVector().subtract(nearestBeacon.toVector()).setY(0);
        if (fleeDir.lengthSquared() < 0.04) {
            fleeDir = zombie.getLocation().getDirection().setY(0);
        }
        if (fleeDir.lengthSquared() < 0.04) {
            return false;
        }
        fleeDir.normalize();

        Location fleeTarget = zombie.getLocation().clone().add(fleeDir.multiply(Math.min(16.0, Math.max(8.0, radius * 0.38))));
        fleeTarget.setY(zombie.getLocation().getY());
        agent.setPathIntent(ZombieAgent.PathIntent.EVADE_BEACON, fleeTarget, 1300L);
        agent.setMovementIntent(ZombieAgent.MovementIntent.RECOVER, 1300L);
        agent.submitMoveIntent(fleeTarget, 0.94, ZombieAgent.MovementPriority.CRITICAL, ZombieAgent.PathIntent.EVADE_BEACON, 1300L);
        return true;
    }

    public boolean allowReplan(ZombieAgent agent, Location nextAnchor, double distanceSq, double thresholdSq) {
        if (agent == null || nextAnchor == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean closeFreeze = agent.getMovementIntent() == ZombieAgent.MovementIntent.CLOSE
            && now < agent.getMovementIntentLeaseUntil();
        if (closeFreeze) {
            return false;
        }

        ZombieAgent.PathFailureType failure = agent.getCurrentPathFailure();
        boolean hardFailure = failure == ZombieAgent.PathFailureType.PATH_MISSING || failure == ZombieAgent.PathFailureType.HARD_STUCK;
        boolean leaseExpired = now > agent.getPathModeLeaseUntil();
        Location currentAnchor = agent.getPathAnchor();
        boolean anchorDrift = currentAnchor == null
            || currentAnchor.getWorld() == null
            || nextAnchor.getWorld() == null
            || !currentAnchor.getWorld().equals(nextAnchor.getWorld())
            || currentAnchor.distanceSquared(nextAnchor) >= Math.max(1.0, thresholdSq * 2.0);

        long minIntervalMs = computeReplanIntervalMs(distanceSq);
        if (!hardFailure && !leaseExpired && !anchorDrift && !agent.canReplanPath(minIntervalMs, nextAnchor, thresholdSq)) {
            return false;
        }
        if (!manager.tryConsumeReplanBudget()) {
            return false;
        }
        manager.recordAiRuntimeStat("replans");
        return true;
    }

    public long computeReplanIntervalMs(double distanceSq) {
        ConfigManager cfg = ConfigManager.getInstance();
        int base = cfg.getRoutingReplanBaseTicks();
        int extra = (int) Math.floor(Math.sqrt(Math.max(0.0, distanceSq)) * cfg.getRoutingReplanDistancePenalty());
        int ticks = Math.max(2, Math.min(20, base + Math.min(6, extra)));
        return ticks * 50L;
    }

    public void applyRouteState(ZombieAgent agent, RouteState state, ZombieAgent.PathIntent intent, Location anchor, long leaseMs) {
        if (agent == null) {
            return;
        }
        long now = System.currentTimeMillis();
        RouteState currentState = mapCurrentState(agent.getMovementIntent());
        boolean forceTransition = state == RouteState.RECOVER
            || state == RouteState.CLOSE
            || intent == ZombieAgent.PathIntent.EVADE_BEACON
            || intent == ZombieAgent.PathIntent.EVADE_LIGHT;
        if (!forceTransition
            && currentState != null
            && currentState != state
            && now < agent.getMovementIntentLeaseUntil()
            && !isTransitionAllowed(currentState, state)) {
            return;
        }

        if (intent != null) {
            agent.setPathIntent(intent, anchor, leaseMs);
        }

        ZombieAgent.MovementIntent moveIntent = switch (state) {
            case LOCK_PURSUIT -> ZombieAgent.MovementIntent.LOCK_PURSUIT;
            case CORRIDOR -> ZombieAgent.MovementIntent.CORRIDOR;
            case BREACH -> ZombieAgent.MovementIntent.BREACH;
            case CLOSE -> ZombieAgent.MovementIntent.CLOSE;
            case RECOVER -> ZombieAgent.MovementIntent.RECOVER;
        };
        agent.setMovementIntent(moveIntent, leaseMs);
    }

    private RouteState mapCurrentState(ZombieAgent.MovementIntent intent) {
        if (intent == null) {
            return null;
        }
        return switch (intent) {
            case LOCK_PURSUIT -> RouteState.LOCK_PURSUIT;
            case CORRIDOR -> RouteState.CORRIDOR;
            case BREACH -> RouteState.BREACH;
            case CLOSE -> RouteState.CLOSE;
            case RECOVER, EVADE_BEACON, EVADE_LIGHT, IDLE -> RouteState.RECOVER;
        };
    }

    private boolean isTransitionAllowed(RouteState from, RouteState to) {
        if (from == null || to == null || from == to) {
            return true;
        }
        return switch (from) {
            case LOCK_PURSUIT -> to == RouteState.CORRIDOR || to == RouteState.CLOSE || to == RouteState.RECOVER;
            case CORRIDOR -> to == RouteState.LOCK_PURSUIT || to == RouteState.BREACH || to == RouteState.CLOSE || to == RouteState.RECOVER;
            case BREACH -> to == RouteState.CORRIDOR || to == RouteState.CLOSE || to == RouteState.RECOVER;
            case CLOSE -> to == RouteState.LOCK_PURSUIT || to == RouteState.RECOVER;
            case RECOVER -> to == RouteState.LOCK_PURSUIT || to == RouteState.CORRIDOR || to == RouteState.CLOSE;
        };
    }
}
