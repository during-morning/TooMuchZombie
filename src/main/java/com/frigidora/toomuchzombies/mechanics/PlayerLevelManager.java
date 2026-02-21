package com.frigidora.toomuchzombies.mechanics;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.ConfigManager;

public class PlayerLevelManager {

    private static PlayerLevelManager instance;

    private final Map<UUID, Integer> levelOverrides = new ConcurrentHashMap<>();
    private final Map<UUID, double[]> stats = new ConcurrentHashMap<>();
    private final Map<UUID, Double> smoothedThreat = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> levelCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> levelCacheTime = new ConcurrentHashMap<>();

    private PlayerLevelManager() {
        loadStats();
    }

    public static void initialize() {
        instance = new PlayerLevelManager();
    }

    public static PlayerLevelManager getInstance() {
        return instance;
    }

    public void setLevelOverride(Player player, int level) {
        if (player == null) return;
        int maxLevel = ConfigManager.getInstance().getLevelMax();
        levelOverrides.put(player.getUniqueId(), Math.max(1, Math.min(maxLevel, level)));
        invalidateLevelCache(player.getUniqueId());
    }

    public void clearLevelOverride(Player player) {
        if (player == null) return;
        levelOverrides.remove(player.getUniqueId());
        invalidateLevelCache(player.getUniqueId());
    }

    public void recordDamage(Player player, double damage) {
        if (player == null) return;
        stats.compute(player.getUniqueId(), (k, s) -> {
            if (s == null) s = new double[]{0, 0, 0};
            s[0] += damage;
            return s;
        });
        invalidateLevelCache(player.getUniqueId());
        saveStatsAsync();
    }

    public void recordKill(Player player) {
        if (player == null) return;
        stats.compute(player.getUniqueId(), (k, s) -> {
            if (s == null) s = new double[]{0, 0, 0};
            s[1] += 1;
            return s;
        });
        invalidateLevelCache(player.getUniqueId());
        applyHealthStats(player);
        saveStatsAsync();
    }

    public void recordDeath(Player player) {
        if (player == null) return;
        stats.compute(player.getUniqueId(), (k, s) -> {
            if (s == null) s = new double[]{0, 0, 0};
            s[2] += 1;
            return s;
        });
        invalidateLevelCache(player.getUniqueId());
        applyHealthStats(player);
        saveStatsAsync();
    }

    public void saveStats() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, double[]> entry : stats.entrySet()) {
            config.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            config.save(new File(TooMuchZombies.getInstance().getDataFolder(), "player_stats.yml"));
        } catch (IOException e) {
            TooMuchZombies.getInstance().getLogger().warning("Failed to save player_stats.yml: " + e.getMessage());
        }
    }

    private void saveStatsAsync() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                saveStats();
            }
        }.runTaskAsynchronously(TooMuchZombies.getInstance());
    }

    private void loadStats() {
        File file = new File(TooMuchZombies.getInstance().getDataFolder(), "player_stats.yml");
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                List<Double> list = config.getDoubleList(key);
                if (list.size() >= 2) {
                    double[] s = new double[3];
                    s[0] = list.get(0);
                    s[1] = list.get(1);
                    s[2] = list.size() >= 3 ? list.get(2) : 0;
                    stats.put(uuid, s);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void applyHealthStats(Player player) {
        if (player == null) return;

        double[] playerStats = stats.get(player.getUniqueId());
        if (playerStats == null) return;

        double kills = playerStats[1];
        double deaths = playerStats[2];

        double healthFromKills = Math.min(60.0, Math.floor(kills / 100.0) * 2.0);
        double healthLostFromDeaths = Math.floor(deaths / 2.0) * 2.0;
        double finalMaxHealth = Math.max(20.0, Math.min(80.0, 20.0 + healthFromKills - healthLostFromDeaths));

        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(finalMaxHealth);
            if (player.getHealth() > finalMaxHealth) {
                player.setHealth(finalMaxHealth);
            }
        }
    }

    public boolean shouldTriggerDamageBoost(Player player) {
        double[] playerStats = stats.get(player.getUniqueId());
        if (playerStats == null) return false;

        double kills = playerStats[1];
        double chance = Math.floor(kills / 100.0) * 0.01;
        return Math.random() < chance;
    }

    public int getEncounterLevel(Player player) {
        int self = getPlayerLevel(player);
        ConfigManager cfg = ConfigManager.getInstance();

        double nearbyMax = self;
        double nearbySum = self;
        int nearbyCount = 1;
        double radius = cfg.getEncounterNearbyRadius();
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof Player) {
                int other = getPlayerLevel((Player) e);
                nearbyMax = Math.max(nearbyMax, other);
                nearbySum += other;
                nearbyCount++;
            }
        }

        double nearbyAvg = nearbySum / Math.max(1, nearbyCount);
        double wSelf = cfg.getEncounterSelfWeight();
        double wMax = cfg.getEncounterNearbyMaxWeight();
        double wAvg = cfg.getEncounterNearbyAvgWeight();
        double weightSum = Math.max(0.0001, wSelf + wMax + wAvg);
        double score = (self * wSelf + nearbyMax * wMax + nearbyAvg * wAvg) / weightSum;
        int rounded = (int) Math.round(score);
        int maxLevel = cfg.getLevelMax();
        return Math.max(1, Math.min(maxLevel, rounded));
    }

    public int getPlayerLevel(Player player) {
        if (player == null) return 1;
        long now = System.currentTimeMillis();
        Long last = levelCacheTime.get(player.getUniqueId());
        if (last != null && now - last < 1000) {
            Integer cached = levelCache.get(player.getUniqueId());
            if (cached != null) {
                return cached;
            }
        }

        Integer override = levelOverrides.get(player.getUniqueId());
        int maxLevel = ConfigManager.getInstance().getLevelMax();
        if (override != null) return Math.max(1, Math.min(maxLevel, override));

        ConfigManager cfg = ConfigManager.getInstance();
        UUID uuid = player.getUniqueId();
        double[] s = stats.getOrDefault(uuid, new double[]{0, 0, 0});

        double xpNorm = Math.min(1.0, player.getLevel() / 300.0);
        double daysAlive = player.getStatistic(Statistic.TIME_SINCE_DEATH) / 24000.0;
        double dayNorm = Math.min(1.0, daysAlive / 30.0);
        double armorNorm = Math.min(1.0, getArmorPoints(player) / 20.0);
        double weaponNorm = Math.min(1.0, getWeaponDamage(player) / 12.0);
        double damageNorm = Math.min(1.0, s[0] / 5000.0);
        double kdrNorm = Math.min(1.0, (s[1] / Math.max(1.0, s[2] + 1.0)) / 5.0);

        double weighted = xpNorm * cfg.getThreatXpWeight()
            + dayNorm * cfg.getThreatSurvivalDaysWeight()
            + armorNorm * cfg.getThreatArmorWeight()
            + weaponNorm * cfg.getThreatWeaponWeight()
            + damageNorm * cfg.getThreatDamageWeight()
            + kdrNorm * cfg.getThreatKdrWeight();

        double totalWeight = cfg.getThreatXpWeight()
            + cfg.getThreatSurvivalDaysWeight()
            + cfg.getThreatArmorWeight()
            + cfg.getThreatWeaponWeight()
            + cfg.getThreatDamageWeight()
            + cfg.getThreatKdrWeight();
        weighted = weighted / Math.max(0.0001, totalWeight);

        double prev = smoothedThreat.getOrDefault(uuid, weighted);
        double up = cfg.getHysteresisUpThreshold();
        double down = cfg.getHysteresisDownThreshold();
        double next;

        if (weighted >= prev) {
            next = prev + (weighted - prev) * up;
        } else {
            next = prev + (weighted - prev) * down;
        }

        smoothedThreat.put(uuid, next);

        int level = 1 + (int) Math.floor(Math.max(0.0, Math.min(0.9999, next)) * maxLevel);
        int clamped = Math.max(1, Math.min(maxLevel, level));
        levelCache.put(uuid, clamped);
        levelCacheTime.put(uuid, now);
        return clamped;
    }

    private double getArmorPoints(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_ARMOR);
        return attr != null ? attr.getValue() : 0.0;
    }

    private double getWeaponDamage(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == org.bukkit.Material.AIR) return 1.0;

        double baseDamage = 1.0;
        switch (item.getType()) {
            case WOODEN_SWORD:
            case GOLDEN_SWORD:
                baseDamage = 4;
                break;
            case STONE_SWORD:
                baseDamage = 5;
                break;
            case IRON_SWORD:
                baseDamage = 6;
                break;
            case DIAMOND_SWORD:
                baseDamage = 7;
                break;
            case NETHERITE_SWORD:
                baseDamage = 8;
                break;
            case WOODEN_AXE:
            case GOLDEN_AXE:
                baseDamage = 7;
                break;
            case STONE_AXE:
            case IRON_AXE:
            case DIAMOND_AXE:
                baseDamage = 9;
                break;
            case NETHERITE_AXE:
                baseDamage = 10;
                break;
            case TRIDENT:
                baseDamage = 9;
                break;
            case BOW:
            case CROSSBOW:
                baseDamage = 6;
                break;
            default:
                break;
        }

        if (item.hasItemMeta()) {
            Map<org.bukkit.enchantments.Enchantment, Integer> enchants = item.getEnchantments();
            org.bukkit.enchantments.Enchantment sharp = org.bukkit.enchantments.Enchantment.getByName("DAMAGE_ALL");
            if (sharp != null && enchants.containsKey(sharp)) {
                int lvl = enchants.get(sharp);
                baseDamage += 0.5 * lvl + 0.5;
            }

            org.bukkit.enchantments.Enchantment power = org.bukkit.enchantments.Enchantment.getByName("ARROW_DAMAGE");
            if (power != null && enchants.containsKey(power)) {
                int lvl = enchants.get(power);
                baseDamage *= (1.0 + 0.25 * (lvl + 1));
            }
        }

        return baseDamage;
    }

    private void invalidateLevelCache(UUID uuid) {
        levelCache.remove(uuid);
        levelCacheTime.remove(uuid);
    }
}
