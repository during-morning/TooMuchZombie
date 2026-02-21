package com.frigidora.toomuchzombies.ai;

import org.bukkit.Location;

import com.frigidora.toomuchzombies.mechanics.BeaconManager;
import com.frigidora.toomuchzombies.mechanics.LightSourceManager;

public class AbilityArbitrator {

    public AbilityIntent decide(ZombieAgent agent) {
        if (agent.isAiPaused()) {
            return AbilityIntent.IDLE;
        }

        if (agent.getSuicideBehavior().isActive()) {
            return AbilityIntent.SUICIDE_CHARGE;
        }

        if (agent.getBuilderBehavior().isActive() || agent.getBreakerBehavior().isBreaking()) {
            return AbilityIntent.STRUCTURE;
        }

        Location loc = agent.getZombie().getLocation();
        if (BeaconManager.getInstance().getNearestActiveBeacon(loc, 50.0) != null
            || LightSourceManager.getInstance().getNearestLightSource(loc, 15.0) != null) {
            return AbilityIntent.SURVIVE;
        }

        if (agent.getLastKnownTargetLocation() != null && !agent.hasMemoryExpired(2000)) {
            return AbilityIntent.CHASE_COMBAT;
        }

        return AbilityIntent.TARGET_SEARCH;
    }
}
