package com.frigidora.toomuchzombies.ai;

import org.bukkit.Location;

import com.frigidora.toomuchzombies.mechanics.BeaconManager;
import com.frigidora.toomuchzombies.mechanics.LightSourceManager;

public class AbilityArbitrator {

    public AbilityIntent decide(ZombieAgent agent) {
        if (agent.isAiPaused()) {
            return AbilityIntent.IDLE;
        }

        boolean hasRecentTarget = agent.getLastKnownTargetLocation() != null && !agent.hasMemoryExpired(2000);

        if (agent.getSuicideBehavior().isActive()) {
            return AbilityIntent.SUICIDE_CHARGE;
        }

        // 当最近刚锁定目标时，优先进入追击战斗，避免在结构行为/避让行为间来回切换。
        if (hasRecentTarget) {
            return AbilityIntent.CHASE_COMBAT;
        }

        if (agent.getBuilderBehavior().isActive() || agent.getBreakerBehavior().isBreaking()) {
            return AbilityIntent.STRUCTURE;
        }

        Location loc = agent.getZombie().getLocation();
        if (BeaconManager.getInstance().getNearestActiveBeacon(loc, 50.0) != null
            || LightSourceManager.getInstance().getNearestLightSource(loc, 15.0) != null) {
            return AbilityIntent.SURVIVE;
        }

        return AbilityIntent.TARGET_SEARCH;
    }
}
