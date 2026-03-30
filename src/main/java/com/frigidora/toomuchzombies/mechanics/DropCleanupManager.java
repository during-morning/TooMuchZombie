package com.frigidora.toomuchzombies.mechanics;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.ConfigManager;

/**
 * Cleans zombie drops only when world item pressure is high.
 */
public class DropCleanupManager {

    private static final int DROP_AGE_TICKS = 12 * 20;
    private static final double PLAYER_SAFE_RADIUS_SQ = 56.0 * 56.0;

    private static DropCleanupManager instance;

    private final NamespacedKey zombieDropKey;
    private final Map<UUID, Integer> overloadedWorldItems = new ConcurrentHashMap<>();
    private int taskId = -1;
    private int worldCursor = 0;

    private DropCleanupManager() {
        this.zombieDropKey = new NamespacedKey(TooMuchZombies.getInstance(), "tmz_zdrop");
        startTask();
    }

    public static void initialize() {
        if (instance == null) {
            instance = new DropCleanupManager();
        }
    }

    public static DropCleanupManager getInstance() {
        return instance;
    }

    public static void shutdown() {
        if (instance != null) {
            instance.stopTask();
            instance.overloadedWorldItems.clear();
            instance = null;
        }
    }

    public void tagZombieDrops(EntityDeathEvent event) {
        if (event == null || event.getEntity() == null || event.getEntity().getWorld() == null) {
            return;
        }

        Location deathLoc = event.getEntity().getLocation().clone();
        World world = deathLoc.getWorld();
        if (world == null || event.getDrops().isEmpty()) {
            return;
        }

        Map<org.bukkit.Material, Integer> expected = new HashMap<>();
        for (ItemStack drop : event.getDrops()) {
            if (drop == null || drop.getType().isAir()) {
                continue;
            }
            expected.merge(drop.getType(), Math.max(1, drop.getAmount()), Integer::sum);
        }
        if (expected.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(TooMuchZombies.getInstance(), () -> {
            if (world.getUID() == null || !world.isChunkLoaded(deathLoc.getBlockX() >> 4, deathLoc.getBlockZ() >> 4)) {
                return;
            }

            for (Entity nearby : world.getNearbyEntities(deathLoc, 4.0, 3.0, 4.0)) {
                if (!(nearby instanceof Item item) || !item.isValid()) {
                    continue;
                }
                if (item.getTicksLived() > 30) {
                    continue;
                }
                ItemStack stack = item.getItemStack();
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }

                Integer remain = expected.get(stack.getType());
                if (remain == null || remain <= 0) {
                    continue;
                }

                item.getPersistentDataContainer().set(zombieDropKey, PersistentDataType.BYTE, (byte) 1);
                int consume = Math.min(remain, Math.max(1, stack.getAmount()));
                expected.put(stack.getType(), remain - consume);
            }
        }, 1L);
    }

    private void startTask() {
        this.taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(TooMuchZombies.getInstance(), this::tick, 20L, 1L);
    }

    private void stopTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void tick() {
        long currentTick = Bukkit.getCurrentTick();
        if (currentTick % 20L == 0L) {
            refreshWorldPressure();
        }

        if (overloadedWorldItems.isEmpty()) {
            return;
        }

        java.util.List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return;
        }

        worldCursor = (worldCursor + 1) % worlds.size();
        World world = worlds.get(worldCursor);
        Integer count = overloadedWorldItems.get(world.getUID());
        if (count == null || count <= ConfigManager.getInstance().getZombieDropCleanupTriggerWorldItemCount()) {
            return;
        }

        int removed = cleanupWorldBatch(world, ConfigManager.getInstance().getZombieDropCleanupBatchPerTick());
        if (removed > 0 && currentTick % 20L == 0L) {
            int latestCount = world.getEntitiesByClass(Item.class).size();
            overloadedWorldItems.put(world.getUID(), latestCount);
        }
    }

    private void refreshWorldPressure() {
        int threshold = ConfigManager.getInstance().getZombieDropCleanupTriggerWorldItemCount();
        for (World world : Bukkit.getWorlds()) {
            int itemCount = world.getEntitiesByClass(Item.class).size();
            if (itemCount > threshold) {
                overloadedWorldItems.put(world.getUID(), itemCount);
            } else {
                overloadedWorldItems.remove(world.getUID());
            }
        }
    }

    private int cleanupWorldBatch(World world, int maxBatch) {
        int removed = 0;
        for (Item item : world.getEntitiesByClass(Item.class)) {
            if (removed >= maxBatch) {
                break;
            }
            if (!isZombieDrop(item)) {
                continue;
            }
            if (item.getTicksLived() < DROP_AGE_TICKS) {
                continue;
            }
            if (hasNearbyPlayer(item.getLocation(), PLAYER_SAFE_RADIUS_SQ)) {
                continue;
            }
            item.remove();
            removed++;
        }
        return removed;
    }

    private boolean hasNearbyPlayer(Location loc, double distSq) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        for (org.bukkit.entity.Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(loc) <= distSq) {
                return true;
            }
        }
        return false;
    }

    private boolean isZombieDrop(Item item) {
        if (item == null || !item.isValid()) {
            return false;
        }
        Byte marked = item.getPersistentDataContainer().get(zombieDropKey, PersistentDataType.BYTE);
        return marked != null && marked == (byte) 1;
    }
}
