package com.frigidora.toomuchzombies.config;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import com.frigidora.toomuchzombies.TooMuchZombies;

public class ConfigManager {

    private static ConfigManager instance;
    private FileConfiguration config;

    private ConfigManager() {
        TooMuchZombies.getInstance().saveDefaultConfig();
        config = TooMuchZombies.getInstance().getConfig();
    }

    public static void initialize() {
        if (instance == null) {
            instance = new ConfigManager();
        }
    }

    public static ConfigManager getInstance() {
        return instance;
    }

    public void reload() {
        TooMuchZombies.getInstance().reloadConfig();
        config = TooMuchZombies.getInstance().getConfig();
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean isHiveMindEnabled() {
        return config.getBoolean("hive-mind.enabled", true);
    }

    public int getHiveMindSensorRange() {
        return clampInt(config.getInt("hive-mind.sensor-range", 32), 8, 128);
    }

    public double getHiveMindMemoryDurationSeconds() {
        return clampDouble(config.getDouble("hive-mind.memory-duration", 0.25), 0.05, 30.0);
    }

    public double getBreakSpeed() {
        return clampDouble(config.getDouble("zombie-ai.break-speed", 1.0), 0.1, 10.0);
    }

    public double getBuildSpeed() {
        return clampDouble(config.getDouble("zombie-ai.build-speed", 1.0), 0.1, 10.0);
    }

    public boolean isTerrainModificationEnabled() {
        return config.getBoolean("zombie-ai.terrain-modification-enabled", true);
    }

    public int getNoiseThreshold() {
        return clampInt(config.getInt("zombie-ai.noise-threshold", 10), 1, 100);
    }

    public long getTargetingScanCooldownMs() {
        return clampInt(config.getInt("zombie-ai.targeting.scan-cooldown-ms", 400), 100, 5000);
    }

    public double getTargetingSwitchScoreDelta() {
        return clampDouble(config.getDouble("zombie-ai.targeting.switch-score-delta", 0.20), 0.0, 10.0);
    }

    public double getTargetingMaxRange() {
        return clampDouble(config.getDouble("zombie-ai.targeting.max-range", 48.0), 8.0, 128.0);
    }

    public double getTargetingRecognitionRange() {
        return clampDouble(config.getDouble("zombie-ai.targeting.recognition-range", 22.0), 6.0, 48.0);
    }

    public double getTargetingPlayerPositionWeight() {
        return clampDouble(config.getDouble("zombie-ai.targeting.player-position-weight", 1.45), 0.1, 6.0);
    }

    public double getTargetingNoiseSourceWeight() {
        return clampDouble(config.getDouble("zombie-ai.targeting.noise-source-weight", 0.30), 0.0, 3.0);
    }

    public double getTargetingSwitchHysteresis() {
        return clampDouble(config.getDouble("zombie-ai.targeting.switch-hysteresis", 0.22), 0.0, 2.0);
    }

    public double getTargetingNoiseSwitchBiasDistance() {
        return clampDouble(config.getDouble("zombie-ai.targeting.noise-switch-bias-distance", 9.0), 0.0, 36.0);
    }

    public String getAiOverrideMode() {
        String mode = config.getString("zombie-ai.override.mode", "hybrid");
        return ("full".equalsIgnoreCase(mode) ? "full" : "hybrid");
    }

    public double getCoopRetreatHealthThreshold() {
        return clampDouble(config.getDouble("zombie-ai.cooperation.retreat-health-threshold", 0.35), 0.05, 0.95);
    }

    public double getCoopRegroupDistance() {
        return clampDouble(config.getDouble("zombie-ai.cooperation.regroup-distance", 14.0), 4.0, 48.0);
    }

    public int getCoopAllyDisadvantageThreshold() {
        return clampInt(config.getInt("zombie-ai.cooperation.ally-disadvantage-threshold", 2), 1, 12);
    }

    public long getCoopBreachSupportCooldownMs() {
        return (long) clampInt(config.getInt("zombie-ai.cooperation.breach-support-cooldown-ms", 3000), 500, 30000);
    }

    public double getCoopFocusFireRange() {
        return clampDouble(config.getDouble("zombie-ai.cooperation.focus-fire-range", 22.0), 6.0, 64.0);
    }

    public double getCoopFlankSyncRange() {
        return clampDouble(config.getDouble("zombie-ai.cooperation.flank-sync-range", 16.0), 4.0, 48.0);
    }

    public double getCoopBodyguardScanRange() {
        return clampDouble(config.getDouble("zombie-ai.cooperation.bodyguard-scan-range", 20.0), 6.0, 64.0);
    }

    public double getCoopEncircleRange() {
        return clampDouble(config.getDouble("zombie-ai.cooperation.encircle-range", 18.0), 6.0, 64.0);
    }

    public long getCoopEncircleReplanMs() {
        return clampInt(config.getInt("zombie-ai.cooperation.encircle-replan-ms", 900), 200, 5000);
    }

    public double getCoopBreachArriveRadius() {
        return clampDouble(config.getDouble("zombie-ai.cooperation.breach-arrive-radius", 3.2), 1.0, 10.0);
    }

    public double getFormationSlotSpacing() {
        return clampDouble(config.getDouble("zombie-ai.pathing.formation-slot-spacing", 1.6), 0.8, 4.0);
    }

    public double getFormationReplanThreshold() {
        return clampDouble(config.getDouble("zombie-ai.pathing.formation-replan-threshold", 1.2), 0.2, 8.0);
    }

    public double getFormationSeparationWeight() {
        return clampDouble(config.getDouble("zombie-ai.pathing.separation-weight", 0.6), 0.0, 4.0);
    }

    public double getFormationSeparationRange() {
        return clampDouble(config.getDouble("zombie-ai.pathing.separation-range", 2.2), 0.8, 8.0);
    }

    public int getRoutingReplanBaseTicks() {
        return clampInt(config.getInt("zombie-ai.routing.replan-base-ticks", 5), 1, 40);
    }

    public double getRoutingReplanDistancePenalty() {
        return clampDouble(config.getDouble("zombie-ai.routing.replan-distance-penalty", 0.18), 0.0, 2.0);
    }

    public long getRoutingLastKnownMemoryMs() {
        return clampInt(config.getInt("zombie-ai.routing.last-known-memory-ms", 9000), 1000, 60000);
    }

    public double getBeaconForceRadius() {
        return clampDouble(config.getDouble("zombie-ai.beacon.force-radius", 50.0), 8.0, 128.0);
    }

    public double getBeaconForceDamagePerSecond() {
        return clampDouble(config.getDouble("zombie-ai.beacon.force-damage-per-second", 1.5), 0.0, 20.0);
    }

    public double getBeaconCombatExemptDistance() {
        return clampDouble(config.getDouble("zombie-ai.beacon.combat-exempt-distance", 2.6), 0.8, 8.0);
    }

    public int getAsyncMaxPlansPerTick() {
        return clampInt(config.getInt("zombie-ai.async.max-plans-per-tick", 48), 8, 2000);
    }

    public int getAsyncPlanTimeBudgetMs() {
        return clampInt(config.getInt("zombie-ai.async.plan-time-budget-ms", 3), 1, 50);
    }

    public java.util.Set<org.bukkit.entity.EntityType> getTargetWhitelist() {
        java.util.Set<org.bukkit.entity.EntityType> out = java.util.EnumSet.noneOf(org.bukkit.entity.EntityType.class);
        for (String one : config.getStringList("zombie-ai.targeting.whitelist")) {
            org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.fromName(one.toLowerCase(java.util.Locale.ROOT));
            if (type == null) {
                try {
                    type = org.bukkit.entity.EntityType.valueOf(one.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    type = null;
                }
            }
            if (type != null) {
                out.add(type);
            }
        }
        return out;
    }

    public java.util.Set<org.bukkit.entity.EntityType> getTargetBlacklist() {
        java.util.Set<org.bukkit.entity.EntityType> out = java.util.EnumSet.noneOf(org.bukkit.entity.EntityType.class);
        for (String one : config.getStringList("zombie-ai.targeting.blacklist")) {
            org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.fromName(one.toLowerCase(java.util.Locale.ROOT));
            if (type == null) {
                try {
                    type = org.bukkit.entity.EntityType.valueOf(one.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    type = null;
                }
            }
            if (type != null) {
                out.add(type);
            }
        }
        return out;
    }

    public long getBuilderPlaceCooldownMs() {
        return clampInt(config.getInt("zombie-ai.builder.place-cooldown-ms", 1000), 100, 10000);
    }

    public long getBuilderPlaceCooldownMsLv9() {
        return clampInt(config.getInt("zombie-ai.builder.place-cooldown-ms-lv9", 250), 50, 5000);
    }

    public long getBuilderPlaceCooldownMsLv12() {
        return clampInt(config.getInt("zombie-ai.builder.place-cooldown-ms-lv12", 333), 50, 5000);
    }

    public long getBuilderBreachRequestAfterMs() {
        return clampInt(config.getInt("zombie-ai.builder.breach-request-after-ms", 3000), 500, 60000);
    }

    public int getBuilderMaxBuildFailTicks() {
        return clampInt(config.getInt("zombie-ai.builder.max-build-fail-ticks", 40), 5, 400);
    }

    public long getBuilderTemporaryBlockDurationMs() {
        return clampInt(config.getInt("zombie-ai.builder.temporary-block-duration-ms", 120000), 1000, 600000);
    }

    public long getBuilderTemporaryBlockDecayWindowMs() {
        return clampInt(config.getInt("zombie-ai.builder.temporary-block-decay-window-ms", 30000), 1000, 120000);
    }

    public int getBuilderTemporaryBlockDecayParticleIntervalTicks() {
        return clampInt(config.getInt("zombie-ai.builder.temporary-block-decay-particle-interval-ticks", 10), 1, 40);
    }

    public boolean isBuilderTemporaryBlockPersistenceEnabled() {
        return config.getBoolean("zombie-ai.builder.temporary-block-persistence-enabled", true);
    }

    public int getBuilderTemporaryBlockSaveIntervalTicks() {
        return clampInt(config.getInt("zombie-ai.builder.temporary-block-save-interval-ticks", 200), 20, 2400);
    }

    public int getBuilderPlaceParticleCount() {
        return clampInt(config.getInt("zombie-ai.builder.place-particle-count", 16), 0, 128);
    }

    public long getBreakerHitEffectIntervalMs() {
        return clampInt(config.getInt("zombie-ai.builder.break-hit-effect-interval-ms", 250), 50, 2000);
    }

    public int getBreakerHitParticleCount() {
        return clampInt(config.getInt("zombie-ai.builder.break-hit-particle-count", 6), 0, 64);
    }

    public int getBreakerFinishParticleCount() {
        return clampInt(config.getInt("zombie-ai.builder.break-finish-particle-count", 22), 0, 128);
    }

    public Set<Material> getBreakerBlacklist() {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String one : config.getStringList("zombie-ai.breaker.blacklist")) {
            Material material = Material.matchMaterial(one);
            if (material != null) {
                out.add(material);
            }
        }
        return out;
    }

    public Set<Material> getBreakerWhitelist() {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String one : config.getStringList("zombie-ai.breaker.whitelist")) {
            Material material = Material.matchMaterial(one);
            if (material != null) {
                out.add(material);
            }
        }
        return out;
    }

    public double getMinerChance() {
        return clampDouble(config.getDouble("zombie-roles.miner", 0.1), 0.0, 1.0);
    }

    public double getBuilderChance() {
        return clampDouble(config.getDouble("zombie-roles.builder", 0.1), 0.0, 1.0);
    }

    public double getArcherChance() {
        return clampDouble(config.getDouble("zombie-roles.archer", 0.1), 0.0, 1.0);
    }

    public double getEnderChance() {
        return clampDouble(config.getDouble("zombie-roles.ender", 0.05), 0.0, 1.0);
    }

    public double getNurseChance() {
        return clampDouble(config.getDouble("zombie-roles.nurse", 0.05), 0.0, 1.0);
    }

    public double getRusherChance() {
        return clampDouble(config.getDouble("zombie-roles.rusher", 0.05), 0.0, 1.0);
    }

    public double getSuicideChance() {
        return clampDouble(config.getDouble("zombie-roles.suicide", 0.05), 0.0, 1.0);
    }

    public int getMaxZombiesPerChunk() {
        return clampInt(config.getInt("spawn.max-zombies-per-chunk", 50), 1, 256);
    }

    public int getMsptThreshold() {
        return clampInt(config.getInt("spawn.mspt-threshold", 45), 5, 100);
    }

    public boolean isSpawnAlgorithmEnabled() {
        return config.getBoolean("spawn.algorithm.enabled", true);
    }

    public long getSpawnChunkCooldownMs() {
        return clampInt(config.getInt("spawn.algorithm.chunk-cooldown-ms", 320), 0, 120000);
    }

    public int getSpawnMaxGlobalZombies() {
        return clampInt(config.getInt("spawn.algorithm.max-global-zombies", 720), 10, 10000);
    }

    public int getSpawnMaxNearPlayer() {
        return clampInt(config.getInt("spawn.algorithm.max-near-player", 96), 1, 500);
    }

    public int getSpawnBudgetPerPlayer() {
        return clampInt(config.getInt("spawn.algorithm.spawn-budget-per-player", 7), 1, 200);
    }

    public double getSpawnAcceptChance() {
        return clampDouble(config.getDouble("spawn.algorithm.accept-chance", 0.9), 0.0, 1.0);
    }

    public boolean isSpawnEnforceNightOnly() {
        return config.getBoolean("spawn.algorithm.enforce-night-only", false);
    }

    public int getSpawnMaxYDiff() {
        return clampInt(config.getInt("spawn.algorithm.max-y-diff", 18), 0, 64);
    }

    public int getSpawnVerticalSearchRange() {
        return clampInt(config.getInt("spawn.algorithm.vertical-search-range", 8), 2, 32);
    }

    public long getRecoveryRepathCooldownMs() {
        return clampInt(config.getInt("zombie-ai.recovery.repath-cooldown-ms", 700), 100, 10000);
    }

    public int getRecoveryStuckWarningTicks() {
        return clampInt(config.getInt("zombie-ai.recovery.stuck-warning-ticks", 35), 5, 200);
    }

    public int getRecoveryStuckTeleportTicks() {
        return clampInt(config.getInt("zombie-ai.recovery.stuck-teleport-ticks", 110), 20, 400);
    }

    public double getThreatXpWeight() {
        return clampDouble(config.getDouble("level.threat.xp-weight", 0.22), 0.0, 2.0);
    }

    public double getThreatSurvivalDaysWeight() {
        return clampDouble(config.getDouble("level.threat.survival-days-weight", 0.18), 0.0, 2.0);
    }

    public double getThreatArmorWeight() {
        return clampDouble(config.getDouble("level.threat.armor-weight", 0.24), 0.0, 2.0);
    }

    public double getThreatWeaponWeight() {
        return clampDouble(config.getDouble("level.threat.weapon-weight", 0.36), 0.0, 2.0);
    }

    public double getThreatDamageWeight() {
        return clampDouble(config.getDouble("level.threat.damage-weight", 0.20), 0.0, 2.0);
    }

    public double getThreatKdrWeight() {
        return clampDouble(config.getDouble("level.threat.kdr-weight", 0.16), 0.0, 2.0);
    }

    public double getEncounterSelfWeight() {
        return clampDouble(config.getDouble("level.encounter.self-weight", 0.60), 0.0, 1.0);
    }

    public double getEncounterNearbyMaxWeight() {
        return clampDouble(config.getDouble("level.encounter.nearby-max-weight", 0.40), 0.0, 1.0);
    }

    public double getEncounterNearbyAvgWeight() {
        return clampDouble(config.getDouble("level.encounter.nearby-avg-weight", 0.15), 0.0, 1.0);
    }

    public double getEncounterNearbyRadius() {
        return clampDouble(config.getDouble("level.encounter.nearby-radius", 64.0), 8.0, 192.0);
    }

    public double getHysteresisUpThreshold() {
        return clampDouble(config.getDouble("level.hysteresis.up-threshold", 0.55), 0.0, 2.0);
    }

    public double getHysteresisDownThreshold() {
        return clampDouble(config.getDouble("level.hysteresis.down-threshold", 0.75), 0.0, 2.0);
    }

    public int getLevelMax() {
        return clampInt(config.getInt("level.max", 12), 4, 20);
    }

    public long getBreachLeaseMs() {
        return clampInt(config.getInt("breach.lease-ms", 4000), 500, 60000);
    }

    public int getBreachPrimaryCap() {
        return clampInt(config.getInt("breach.role-caps.primary", 2), 1, 20);
    }

    public int getBreachSupportCap() {
        return clampInt(config.getInt("breach.role-caps.support", 4), 1, 20);
    }

    public int getBreachBodyguardCap() {
        return clampInt(config.getInt("breach.role-caps.bodyguard", 3), 0, 20);
    }

    public double getBreachPrimaryDistanceBias() {
        return clampDouble(config.getDouble("breach.assignment.primary-distance-bias", 0.85), 0.2, 3.0);
    }

    public double getBreachSupportDistanceBias() {
        return clampDouble(config.getDouble("breach.assignment.support-distance-bias", 1.0), 0.2, 3.0);
    }

    public double getBreachBodyguardDistanceBias() {
        return clampDouble(config.getDouble("breach.assignment.bodyguard-distance-bias", 1.1), 0.2, 3.0);
    }

    public double getBloodMoonChance() {
        return clampDouble(config.getDouble("blood-moon.chance", 0.05), 0.0, 1.0);
    }

    public double getBloodMoonMultiplier() {
        return clampDouble(config.getDouble("blood-moon.multiplier", 3.0), 1.0, 20.0);
    }

    public double getBloodMoonHealthMultiplier() {
        return clampDouble(config.getDouble("blood-moon.health-multiplier", 5.0), 1.0, 50.0);
    }

    public int getPhantomMaxPerPlayer() {
        return clampInt(config.getInt("phantom.max-per-player", 20), 1, 128);
    }

    public int getPhantomSpawnRadius() {
        return clampInt(config.getInt("phantom.spawn-radius", 50), 4, 256);
    }

    public double getPhantomSpawnChance() {
        return clampDouble(config.getDouble("phantom.spawn-chance", 0.1), 0.0, 1.0);
    }

    public double getPhantomExplodeChance() {
        return clampDouble(config.getDouble("phantom.explode-chance", 0.2), 0.0, 1.0);
    }

    public double getPhantomBreakBlockChance() {
        return clampDouble(config.getDouble("phantom.break-block-chance", 0.3), 0.0, 1.0);
    }

    public double getPhantomAttackEffectChance() {
        return clampDouble(config.getDouble("phantom.attack-effect-chance", 0.25), 0.0, 1.0);
    }

    public int getZombieDropCleanupTriggerWorldItemCount() {
        return clampInt(config.getInt("cleanup.zombie-drops.trigger-world-item-count", 450), 100, 5000);
    }

    public int getZombieDropCleanupBatchPerTick() {
        return clampInt(config.getInt("cleanup.zombie-drops.batch-per-tick", 64), 4, 512);
    }
}
