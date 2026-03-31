package com.frigidora.toomuchzombies.mechanics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.enums.ZombieRole;

public class ZombieFactory {

    private static final String MANAGED_MARKER_KEY = "tmz_managed_zombie";
    private static final Random RANDOM = new Random();
    private static List<Material> placeableBlocks = Arrays.asList(
        Material.COBBLESTONE,
        Material.STONE,
        Material.GRANITE,
        Material.STONE_BRICKS,
        Material.NETHERRACK,
        Material.DIRT
    );

    private static final Map<String, LongAdder> spawnRejectReasons = new ConcurrentHashMap<>();
    private static final Map<String, Long> chunkCooldowns = new ConcurrentHashMap<>();

    public static void loadConfig() {
        List<String> configBlocks = TooMuchZombies.getInstance().getConfig().getStringList("zombie-ai.build-blocks");
        if (configBlocks != null && !configBlocks.isEmpty()) {
            List<Material> parsed = new ArrayList<>();
            for (String s : configBlocks) {
                try {
                    Material mat = Material.valueOf(s.toUpperCase());
                    if (mat.isBlock()) parsed.add(mat);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (!parsed.isEmpty()) placeableBlocks = parsed;
        }
        if (placeableBlocks.isEmpty()) {
            placeableBlocks = Arrays.asList(Material.COBBLESTONE);
        }
    }

    public static List<Material> getPlaceableBlocks() {
        return placeableBlocks;
    }

    private static void reject(String reason) {
        spawnRejectReasons.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    public static Map<String, Long> getSpawnRejectStatsSnapshot() {
        Map<String, Long> out = new java.util.TreeMap<>();
        for (Map.Entry<String, LongAdder> entry : spawnRejectReasons.entrySet()) {
            out.put(entry.getKey(), entry.getValue().longValue());
        }
        return out;
    }

    public static String getSpawnRejectStatsLine() {
        Map<String, Long> stats = getSpawnRejectStatsSnapshot();
        if (stats.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Long> e : stats.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static void resetSpawnRejectStats() {
        spawnRejectReasons.clear();
        chunkCooldowns.clear();
    }

    private static String chunkKey(Chunk chunk) {
        return chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    private static double readCurrentTps() {
        try {
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length > 0) {
                return Math.max(0.0, tps[0]);
            }
        } catch (Throwable ignored) {
        }
        return 20.0;
    }

    private static double computeTpsSpawnScale(ConfigManager cfg) {
        double tps = readCurrentTps();
        double soft = cfg.getSpawnTpsSoftThreshold();
        double hard = Math.min(soft - 0.1, cfg.getSpawnTpsHardThreshold());
        double softScale = cfg.getSpawnTpsBudgetSoftScale();
        double hardScale = Math.min(softScale, cfg.getSpawnTpsBudgetHardScale());

        if (tps >= soft) {
            return 1.0;
        }
        if (tps <= hard) {
            return hardScale;
        }
        double ratio = (tps - hard) / Math.max(0.1, (soft - hard));
        return hardScale + (softScale - hardScale) * ratio;
    }

    public static boolean evaluateSpawnPipeline(Location loc, EntityType entityType) {
        ConfigManager cfg = ConfigManager.getInstance();
        if (!cfg.isSpawnAlgorithmEnabled()) return true;
        double spawnScale = computeTpsSpawnScale(cfg);

        if (entityType != EntityType.ZOMBIE
            && entityType != EntityType.DROWNED
            && entityType != EntityType.ZOMBIFIED_PIGLIN
            && entityType != EntityType.CREEPER
            && entityType != EntityType.SPIDER
            && entityType != EntityType.SKELETON
            && entityType != EntityType.ENDERMAN
            && entityType != EntityType.WITCH) {
            return true;
        }

        boolean isDirectZombieSpawn = entityType == EntityType.ZOMBIE || entityType == EntityType.DROWNED;
        if (cfg.isSpawnEnforceNightOnly() && isDirectZombieSpawn) {
            long time = loc.getWorld().getTime();
            if (time >= 0 && time < 12000) {
                reject("daytime");
                return false;
            }
        }

        int effectiveGlobalCap = Math.max(24, (int) Math.floor(cfg.getSpawnMaxGlobalZombies() * Math.max(0.60, spawnScale)));
        if (ZombieAIManager.getInstance().getZombieCount() >= effectiveGlobalCap) {
            reject("global_cap");
            return false;
        }

        if (countZombiesInChunk(loc.getChunk()) >= cfg.getMaxZombiesPerChunk()) {
            reject("chunk_cap");
            return false;
        }

        Player nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Player p : loc.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(loc);
            if (d < nearestDistSq) {
                nearestDistSq = d;
                nearest = p;
            }
        }

        if (nearest == null || nearestDistSq > 192 * 192) {
            reject("no_player_near");
            return false;
        }

        if (Math.abs(loc.getBlockY() - nearest.getLocation().getBlockY()) > cfg.getSpawnMaxYDiff()) {
            reject("y_diff");
            return false;
        }

        Location adjusted = NightHordeManager.getInstance() != null
            ? NightHordeManager.getInstance().findReachableSpawnNearPlayer(nearest, loc.clone())
            : null;
        if (adjusted == null) {
            reject("unreachable_vertical");
            return false;
        }
        loc.setX(adjusted.getX());
        loc.setY(adjusted.getY());
        loc.setZ(adjusted.getZ());

        int nearbyManaged = 0;
        for (Entity e : nearest.getNearbyEntities(96, 96, 96)) {
            if (e instanceof Zombie z) {
                if (ZombieAIManager.getInstance().getAgent(z.getUniqueId()) != null) {
                    nearbyManaged++;
                }
            }
        }

        // 只用插件管理僵尸参与配额判定，避免被原版自然僵尸误伤配额。
        int effectiveNearCap = Math.max(8, (int) Math.floor(cfg.getSpawnMaxNearPlayer() * Math.max(0.55, spawnScale)));
        if (nearbyManaged >= effectiveNearCap) {
            reject("near_player_cap");
            return false;
        }

        // 预算按“每个玩家附近”判定，不再乘以世界人数，避免单玩家场景被过早限制。
        int relaxedBudget = Math.max(1, cfg.getSpawnBudgetPerPlayer()) * 8;
        relaxedBudget = Math.max(4, (int) Math.floor(relaxedBudget * Math.max(0.45, spawnScale)));
        if (nearbyManaged >= relaxedBudget) {
            reject("budget");
            return false;
        }

        String key = chunkKey(loc.getChunk());
        long now = System.currentTimeMillis();
        Long last = chunkCooldowns.get(key);
        long cooldownMs = Math.max(0L, cfg.getSpawnChunkCooldownMs() / 3L);
        cooldownMs = (long) Math.ceil(cooldownMs / Math.max(0.18, spawnScale));
        if (last != null && now - last < cooldownMs) {
            // 冷却后半段允许少量提前通过，降低“刷怪节奏过慢”的体感。
            if (now - last < cooldownMs / 3L || RANDOM.nextDouble() < 0.35) {
                reject("chunk_cooldown");
                return false;
            }
        }

        if (spawnScale < 0.999 && RANDOM.nextDouble() > spawnScale) {
            reject("tps_throttle");
            return false;
        }

        double effectiveAcceptChance = Math.max(cfg.getSpawnAcceptChance(), 0.96) * (0.72 + 0.28 * spawnScale);
        effectiveAcceptChance = Math.max(0.30, Math.min(1.0, effectiveAcceptChance));
        if (RANDOM.nextDouble() > effectiveAcceptChance) {
            reject("accept_rate");
            return false;
        }

        chunkCooldowns.put(key, now);
        return true;
    }

    public static boolean canSpawnManagedZombie(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            reject("invalid_spawn_loc");
            return false;
        }

        ConfigManager cfg = ConfigManager.getInstance();
        double spawnScale = computeTpsSpawnScale(cfg);
        int effectiveGlobalCap = Math.max(24, (int) Math.floor(cfg.getSpawnMaxGlobalZombies() * Math.max(0.60, spawnScale)));
        if (ZombieAIManager.getInstance().getZombieCount() >= effectiveGlobalCap) {
            reject("global_cap_custom");
            return false;
        }

        if (countZombiesInChunk(loc.getChunk()) >= cfg.getMaxZombiesPerChunk()) {
            reject("chunk_cap_custom");
            return false;
        }

        Player nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Player player : loc.getWorld().getPlayers()) {
            double distSq = player.getLocation().distanceSquared(loc);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }

        if (nearest == null || nearestDistSq > 192 * 192) {
            reject("no_player_near_custom");
            return false;
        }

        int nearbyManaged = 0;
        for (Entity entity : nearest.getNearbyEntities(96, 96, 96)) {
            if (entity instanceof Zombie zombie && isManagedZombie(zombie)) {
                nearbyManaged++;
            }
        }

        int effectiveNearCap = Math.max(8, (int) Math.floor(cfg.getSpawnMaxNearPlayer() * Math.max(0.55, spawnScale)));
        if (nearbyManaged >= effectiveNearCap) {
            reject("near_player_cap_custom");
            return false;
        }

        int relaxedBudget = Math.max(1, cfg.getSpawnBudgetPerPlayer()) * 8;
        relaxedBudget = Math.max(4, (int) Math.floor(relaxedBudget * Math.max(0.45, spawnScale)));
        if (nearbyManaged >= relaxedBudget) {
            reject("budget_custom");
            return false;
        }

        return true;
    }

    public static int calculateEncounterLevelNearby(Location loc) {
        int maxLevel = 1;
        double radius = ConfigManager.getInstance().getEncounterNearbyRadius();
        int limit = ConfigManager.getInstance().getLevelMax();

        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= radius * radius) {
                int level = PlayerLevelManager.getInstance().getEncounterLevel(p);
                if (level > maxLevel) maxLevel = level;
            }
        }
        return Math.max(1, Math.min(limit, maxLevel));
    }

    public static int calculateMaxLevelNearby(Zombie zombie) {
        return calculateEncounterLevelNearby(zombie.getLocation());
    }

    public static void assignRole(Zombie zombie) {
        int maxLevel = calculateMaxLevelNearby(zombie);

        if (zombie.getAttribute(Attribute.GENERIC_FOLLOW_RANGE) != null) {
            zombie.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(100.0);
        }

        ZombieRole role = pickRoleByContext(zombie, maxLevel);
        assignRole(zombie, role, maxLevel);
    }

    public static void assignRole(Zombie zombie, ZombieRole role) {
        int level = calculateMaxLevelNearby(zombie);
        assignRole(zombie, role, level);
    }

    public static void assignRole(Zombie zombie, ZombieRole role, int level) {
        markManagedZombie(zombie);
        ZombieAIManager.getInstance().registerZombie(zombie, role, level);
        equipZombie(zombie, role, level);
        storeZombieLevel(zombie, level);
        applyLevelAttributes(zombie, level);
        zombie.setCustomName(role.name() + " (Lv." + level + ")");
        zombie.setCustomNameVisible(true);

        if (role == ZombieRole.RUSHER && zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.12);
        }

        if (TooMuchZombies.getNMSHandler() != null) {
            TooMuchZombies.getNMSHandler().injectCustomAI(zombie);
        }
    }

    private static ZombieRole pickRoleByContext(Zombie zombie, int maxLevel) {
        boolean playerUnderground = false;
        boolean playerInBunker = false;
        boolean playerAir = false;

        Player nearest = null;
        double minDistSq = Double.MAX_VALUE;
        for (Entity e : zombie.getNearbyEntities(64, 64, 64)) {
            if (e instanceof Player) {
                double d = e.getLocation().distanceSquared(zombie.getLocation());
                if (d < minDistSq) {
                    minDistSq = d;
                    nearest = (Player) e;
                }
            }
        }

        if (nearest != null) {
            if (nearest.getLocation().getY() < 62
                && nearest.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.UP, 2).getType().isSolid()) {
                playerUnderground = true;
            } else if (!nearest.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid()
                && !nearest.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN, 2).getType().isSolid()) {
                playerAir = true;
            }

            int surrounding = 0;
            org.bukkit.block.Block head = nearest.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.UP);
            if (head.getRelative(org.bukkit.block.BlockFace.NORTH).getType().isSolid()) surrounding++;
            if (head.getRelative(org.bukkit.block.BlockFace.SOUTH).getType().isSolid()) surrounding++;
            if (head.getRelative(org.bukkit.block.BlockFace.EAST).getType().isSolid()) surrounding++;
            if (head.getRelative(org.bukkit.block.BlockFace.WEST).getType().isSolid()) surrounding++;
            playerInBunker = surrounding >= 2;
        }

        double builderWeight;
        double suicideWeight;
        double rusherWeight;
        double nurseWeight;
        double enderWeight;
        double combatWeight;

        if (maxLevel <= 2) {
            builderWeight = 10.0;
            suicideWeight = 2.0;
            rusherWeight = 1.0;
            nurseWeight = 5.0;
            enderWeight = 10.0;
            combatWeight = 10.0;
        } else if (maxLevel <= 4) {
            builderWeight = 20.0;
            suicideWeight = 5.0;
            rusherWeight = 2.0;
            nurseWeight = 5.0;
            enderWeight = 10.0;
            combatWeight = 10.0;
        } else if (maxLevel <= 6) {
            builderWeight = 30.0;
            suicideWeight = 5.0;
            rusherWeight = 2.0;
            nurseWeight = 10.0;
            enderWeight = 10.0;
            combatWeight = 14.0;
        } else {
            builderWeight = 30.0;
            suicideWeight = 5.0;
            rusherWeight = 5.0;
            nurseWeight = 10.0;
            enderWeight = 10.0;
            combatWeight = 14.0;
        }

        if (playerUnderground || playerInBunker) {
            builderWeight *= 1.5;
            suicideWeight *= 2.0;
            rusherWeight *= 2.0;
        } else if (playerAir) {
            builderWeight *= 3.0;
        }

        double others = builderWeight + suicideWeight + rusherWeight + nurseWeight + enderWeight + combatWeight;
        double normalWeight;
        if (others > 95.0) {
            double scale = 95.0 / others;
            builderWeight *= scale;
            suicideWeight *= scale;
            rusherWeight *= scale;
            nurseWeight *= scale;
            enderWeight *= scale;
            combatWeight *= scale;
            normalWeight = 5.0;
        } else {
            normalWeight = 100.0 - others;
        }

        double totalWeight = builderWeight + suicideWeight + rusherWeight + nurseWeight + enderWeight + combatWeight + normalWeight;
        double rVal = RANDOM.nextDouble() * totalWeight;

        double cursor = 0;
        ZombieRole role;
        if (rVal < (cursor += builderWeight)) role = ZombieRole.BUILDER;
        else if (rVal < (cursor += suicideWeight)) role = ZombieRole.SUICIDE;
        else if (rVal < (cursor += rusherWeight)) role = ZombieRole.RUSHER;
        else if (rVal < (cursor += nurseWeight)) role = ZombieRole.NURSE;
        else if (rVal < (cursor += enderWeight)) role = ZombieRole.ENDER;
        else if (rVal < (cursor += combatWeight)) role = ZombieRole.COMBAT;
        else role = ZombieRole.NORMAL;

        if (role == ZombieRole.BUILDER && RANDOM.nextBoolean()) {
            role = ZombieRole.MINER;
        }

        return role;
    }

    private static void equipZombie(Zombie zombie, ZombieRole role, int level) {
        zombie.getEquipment().clear();
        setEquipmentDropChances(zombie, 0.0f);
        switch (role) {
            case MINER:
                Material[] pickaxes = {
                    Material.WOODEN_PICKAXE,
                    Material.STONE_PICKAXE,
                    Material.IRON_PICKAXE,
                    Material.GOLDEN_PICKAXE,
                    Material.DIAMOND_PICKAXE
                };
                zombie.getEquipment().setItemInMainHand(new ItemStack(pickaxes[RANDOM.nextInt(pickaxes.length)]));
                break;
            case BUILDER:
                List<Material> blocks = getPlaceableBlocks();
                Material block = blocks.get(RANDOM.nextInt(blocks.size()));
                zombie.getEquipment().setItemInMainHand(new ItemStack(block));
                break;
            case ARCHER:
                zombie.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                break;
            case ENDER:
                zombie.getEquipment().setItemInMainHand(new ItemStack(Material.ENDER_PEARL));
                break;
            case NURSE:
                zombie.getEquipment().setItemInMainHand(new ItemStack(Material.SPLASH_POTION));
                break;
            case RUSHER:
                zombie.getEquipment().setItemInMainHand(new ItemStack(Material.TNT));
                break;
            case SUICIDE:
                zombie.getEquipment().setHelmet(new ItemStack(Material.TNT));
                break;
            case COMBAT:
                Material armorMat = pickCombatChestplate(zombie.getWorld(), level);
                Material legs = toLeggings(armorMat);
                Material boots = toBoots(armorMat);
                Material helm = toHelmet(armorMat);

                zombie.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                zombie.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD));
                zombie.getEquipment().setChestplate(new ItemStack(armorMat));
                zombie.getEquipment().setLeggings(new ItemStack(legs));
                zombie.getEquipment().setBoots(new ItemStack(boots));
                zombie.getEquipment().setHelmet(new ItemStack(helm));
                break;
            default:
                break;
        }
    }

    private static void setEquipmentDropChances(Zombie zombie, float chance) {
        if (zombie.getEquipment() == null) {
            return;
        }
        zombie.getEquipment().setItemInMainHandDropChance(chance);
        zombie.getEquipment().setItemInOffHandDropChance(chance);
        zombie.getEquipment().setHelmetDropChance(chance);
        zombie.getEquipment().setChestplateDropChance(chance);
        zombie.getEquipment().setLeggingsDropChance(chance);
        zombie.getEquipment().setBootsDropChance(chance);
    }

    private static Material pickCombatChestplate(org.bukkit.World world, int level) {
        org.bukkit.World.Environment env = world.getEnvironment();
        double r = RANDOM.nextDouble();

        if (env == org.bukkit.World.Environment.NORMAL || env == org.bukkit.World.Environment.CUSTOM) {
            if (level <= 4) {
                return r < 0.78 ? Material.IRON_CHESTPLATE : Material.GOLDEN_CHESTPLATE;
            }
            if (level <= 8) {
                if (r < 0.58) return Material.IRON_CHESTPLATE;
                if (r < 0.88) return Material.GOLDEN_CHESTPLATE;
                return Material.DIAMOND_CHESTPLATE;
            }
            if (level <= 11) {
                if (r < 0.46) return Material.IRON_CHESTPLATE;
                if (r < 0.74) return Material.GOLDEN_CHESTPLATE;
                if (r < 0.985) return Material.DIAMOND_CHESTPLATE;
                return Material.NETHERITE_CHESTPLATE;
            }
            if (r < 0.38) return Material.IRON_CHESTPLATE;
            if (r < 0.64) return Material.GOLDEN_CHESTPLATE;
            if (r < 0.975) return Material.DIAMOND_CHESTPLATE;
            return Material.NETHERITE_CHESTPLATE;
        }

        if (r < 0.52) return Material.IRON_CHESTPLATE;
        if (r < 0.76) return Material.GOLDEN_CHESTPLATE;
        if (r < 0.97) return Material.DIAMOND_CHESTPLATE;
        return Material.NETHERITE_CHESTPLATE;
    }

    private static Material toLeggings(Material chest) {
        return switch (chest) {
            case GOLDEN_CHESTPLATE -> Material.GOLDEN_LEGGINGS;
            case DIAMOND_CHESTPLATE -> Material.DIAMOND_LEGGINGS;
            case NETHERITE_CHESTPLATE -> Material.NETHERITE_LEGGINGS;
            default -> Material.IRON_LEGGINGS;
        };
    }

    private static Material toBoots(Material chest) {
        return switch (chest) {
            case GOLDEN_CHESTPLATE -> Material.GOLDEN_BOOTS;
            case DIAMOND_CHESTPLATE -> Material.DIAMOND_BOOTS;
            case NETHERITE_CHESTPLATE -> Material.NETHERITE_BOOTS;
            default -> Material.IRON_BOOTS;
        };
    }

    private static Material toHelmet(Material chest) {
        return switch (chest) {
            case GOLDEN_CHESTPLATE -> Material.GOLDEN_HELMET;
            case DIAMOND_CHESTPLATE -> Material.DIAMOND_HELMET;
            case NETHERITE_CHESTPLATE -> Material.NETHERITE_HELMET;
            default -> Material.IRON_HELMET;
        };
    }

    public static void applyLevelAttributes(Zombie zombie, int level) {
        int maxLevel = ConfigManager.getInstance().getLevelMax();
        int lv = Math.max(1, Math.min(maxLevel, level));
        double t = (lv - 1.0) / Math.max(1.0, maxLevel - 1.0);

        double health = (12.0 + Math.pow(t, 1.06) * 48.0) * 0.38;
        if (lv <= 4) {
            health *= (0.90 + RANDOM.nextDouble() * 0.20);
        }

        if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(Math.min(256.0, health));
            zombie.setHealth(Math.min(zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue(), Math.min(256.0, health)));
        }

        if (zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
            double bloodMoonBonus = BloodMoonManager.getInstance().isBloodMoon(zombie.getWorld()) ? 1.15 : 1.0;
            zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue((4.5 + t * 12.0) * bloodMoonBonus);
        }
        if (zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.19 + t * 0.05);
        }
        if (zombie.getAttribute(Attribute.GENERIC_ARMOR) != null) {
            double armor = 0.0 + t * 2.2;
            if (BloodMoonManager.getInstance().isBloodMoon(zombie.getWorld())) armor += 0.8;
            zombie.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(armor);
        }
        if (zombie.getAttribute(Attribute.GENERIC_ARMOR_TOUGHNESS) != null) {
            zombie.getAttribute(Attribute.GENERIC_ARMOR_TOUGHNESS).setBaseValue(t * 0.8);
        }
        if (zombie.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            zombie.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(Math.min(0.18, t * 0.18));
        }
        if (zombie.getAttribute(Attribute.GENERIC_FOLLOW_RANGE) != null) {
            zombie.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(40.0 + t * 80.0);
        }

        if (BloodMoonManager.getInstance().isBloodMoon(zombie.getWorld()) && zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double bonusHealth = zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue() * ConfigManager.getInstance().getBloodMoonHealthMultiplier();
            zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(Math.min(256.0, bonusHealth));
            zombie.setHealth(Math.min(zombie.getHealth() * ConfigManager.getInstance().getBloodMoonHealthMultiplier(), zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
        }

        applyLevelSkills(zombie, lv);
        zombie.setGlowing(lv >= Math.max(9, maxLevel - 3));
    }

    private static void applyLevelSkills(Zombie zombie, int level) {
        clearScalingEffects(zombie);
        if (level >= 4) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0, true, false, false));
        }
        if (level >= 10 && BloodMoonManager.getInstance().isBloodMoon(zombie.getWorld())) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, true, false, false));
        }
        if (level >= 10) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 0, true, false, false));
        }
        if (level >= 11) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, true, false, false));
        }
        if (level >= 12 && BloodMoonManager.getInstance().isBloodMoon(zombie.getWorld())) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, Integer.MAX_VALUE, 1, true, false, false));
        }
    }

    private static void clearScalingEffects(Zombie zombie) {
        zombie.removePotionEffect(PotionEffectType.STRENGTH);
        zombie.removePotionEffect(PotionEffectType.SPEED);
        zombie.removePotionEffect(PotionEffectType.RESISTANCE);
        zombie.removePotionEffect(PotionEffectType.REGENERATION);
        zombie.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
    }

    private static void storeZombieLevel(Zombie zombie, int level) {
        NamespacedKey key = new NamespacedKey(TooMuchZombies.getInstance(), "zombie_level");
        zombie.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, level);
    }

    public static boolean isManagedZombie(Zombie zombie) {
        return zombie != null
            && zombie.getPersistentDataContainer().has(getManagedMarkerKey(), PersistentDataType.BYTE);
    }

    private static void markManagedZombie(Zombie zombie) {
        zombie.getPersistentDataContainer().set(getManagedMarkerKey(), PersistentDataType.BYTE, (byte) 1);
    }

    private static NamespacedKey getManagedMarkerKey() {
        return new NamespacedKey(TooMuchZombies.getInstance(), MANAGED_MARKER_KEY);
    }

    private static int countZombiesInChunk(Chunk chunk) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Zombie) {
                count++;
            }
        }
        return count;
    }
}
