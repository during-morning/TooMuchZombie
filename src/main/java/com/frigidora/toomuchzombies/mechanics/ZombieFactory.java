package com.frigidora.toomuchzombies.mechanics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.enums.ZombieRole;

public class ZombieFactory {

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

    public static boolean evaluateSpawnPipeline(Location loc, EntityType entityType) {
        ConfigManager cfg = ConfigManager.getInstance();
        if (!cfg.isSpawnAlgorithmEnabled()) return true;

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

        if (cfg.isSpawnEnforceNightOnly()) {
            long time = loc.getWorld().getTime();
            if (time >= 0 && time < 12000) {
                reject("daytime");
                return false;
            }
        }

        if (ZombieAIManager.getInstance().getZombieCount() >= cfg.getSpawnMaxGlobalZombies()) {
            reject("global_cap");
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

        if (nearest == null || nearestDistSq > 64 * 64) {
            reject("no_player_near");
            return false;
        }

        int nearby = 0;
        for (Entity e : nearest.getNearbyEntities(64, 64, 64)) {
            if (e instanceof Zombie) nearby++;
        }
        if (nearby >= cfg.getSpawnMaxNearPlayer()) {
            reject("near_player_cap");
            return false;
        }

        if (nearby >= cfg.getSpawnBudgetPerPlayer() * Math.max(1, loc.getWorld().getPlayers().size())) {
            reject("budget");
            return false;
        }

        String key = chunkKey(loc.getChunk());
        long now = System.currentTimeMillis();
        Long last = chunkCooldowns.get(key);
        if (last != null && now - last < cfg.getSpawnChunkCooldownMs()) {
            reject("chunk_cooldown");
            return false;
        }

        if (RANDOM.nextDouble() > cfg.getSpawnAcceptChance()) {
            reject("accept_rate");
            return false;
        }

        chunkCooldowns.put(key, now);
        return true;
    }

    public static int calculateEncounterLevelNearby(Location loc) {
        int maxLevel = 1;
        double radius = ConfigManager.getInstance().getEncounterNearbyRadius();

        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= radius * radius) {
                int level = PlayerLevelManager.getInstance().getEncounterLevel(p);
                if (level > maxLevel) maxLevel = level;
            }
        }
        return Math.max(1, Math.min(8, maxLevel));
    }

    public static int calculateMaxLevelNearby(Zombie zombie) {
        return calculateEncounterLevelNearby(zombie.getLocation());
    }

    public static void assignRole(Zombie zombie) {
        int maxLevel = calculateMaxLevelNearby(zombie);
        applyLevelAttributes(zombie, maxLevel);

        if (zombie.getAttribute(Attribute.GENERIC_FOLLOW_RANGE) != null) {
            zombie.getAttribute(Attribute.GENERIC_FOLLOW_RANGE).setBaseValue(100.0);
        }

        ZombieRole role = pickRoleByContext(zombie, maxLevel);
        assignRole(zombie, role, maxLevel);
    }

    public static void assignRole(Zombie zombie, ZombieRole role) {
        int level = calculateMaxLevelNearby(zombie);
        applyLevelAttributes(zombie, level);
        assignRole(zombie, role, level);
    }

    public static void assignRole(Zombie zombie, ZombieRole role, int level) {
        ZombieAIManager.getInstance().registerZombie(zombie, role, level);
        equipZombie(zombie, role);
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

    private static void equipZombie(Zombie zombie, ZombieRole role) {
        zombie.getEquipment().clear();
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
                Material armorMat = pickCombatChestplate(zombie.getWorld());
                Material legs = Material.IRON_LEGGINGS;
                Material boots = Material.IRON_BOOTS;
                Material helm = Material.IRON_HELMET;

                if (armorMat == Material.DIAMOND_CHESTPLATE) {
                    legs = Material.DIAMOND_LEGGINGS;
                    boots = Material.DIAMOND_BOOTS;
                    helm = Material.DIAMOND_HELMET;
                } else if (armorMat == Material.NETHERITE_CHESTPLATE) {
                    legs = Material.NETHERITE_LEGGINGS;
                    boots = Material.NETHERITE_BOOTS;
                    helm = Material.NETHERITE_HELMET;
                }

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

    private static Material pickCombatChestplate(org.bukkit.World world) {
        org.bukkit.World.Environment env = world.getEnvironment();
        double r = RANDOM.nextDouble();

        if (env == org.bukkit.World.Environment.NORMAL) {
            return r < 0.97 ? Material.IRON_CHESTPLATE : Material.DIAMOND_CHESTPLATE;
        }

        if (r < 0.65) return Material.IRON_CHESTPLATE;
        if (r < 0.90) return Material.DIAMOND_CHESTPLATE;
        return Material.NETHERITE_CHESTPLATE;
    }

    public static void applyLevelAttributes(Zombie zombie, int level) {
        double health = 20.0;
        switch (level) {
            case 1:
                health = 20.0;
                break;
            case 2:
                health = 30.0;
                break;
            case 3:
                health = 40.0;
                break;
            case 4:
                health = 50.0;
                break;
            case 5:
                health = 70.0;
                break;
            case 6:
                health = 90.0;
                break;
            case 7:
                health = 200.0;
                break;
            case 8:
                health = 300.0;
                break;
            case 9:
                health = 500.0 + RANDOM.nextDouble() * 524.0;
                break;
            case 10:
                health = 700.0 + RANDOM.nextDouble() * 324.0;
                zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION, Integer.MAX_VALUE, 0));
                break;
            case 11:
                health = 700.0 + RANDOM.nextDouble() * 324.0;
                zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION, Integer.MAX_VALUE, 2));
                zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0));
                break;
            case 12:
                health = 700.0 + RANDOM.nextDouble() * 324.0;
                zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION, Integer.MAX_VALUE, 5));
                zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 1));
                break;
            default:
                break;
        }

        if (level >= 9) {
            zombie.setGlowing(true);
        }

        if (level <= 8) {
            health = health * (0.8 + RANDOM.nextDouble() * 0.4);
        }

        if (health > 1024.0) {
            health = 1024.0;
        }

        if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
            zombie.setHealth(health);
        }
    }
}
