package com.frigidora.toomuchzombies.mechanics;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.ConfigManager;

public class TemporaryBlockManager implements Listener {

    private static TemporaryBlockManager instance;
    private final Map<BlockKey, TempBlockEntry> temporaryBlocks = new ConcurrentHashMap<>();
    private final String METADATA_KEY = "ZombieBlock";
    private final File persistenceFile = new File(TooMuchZombies.getInstance().getDataFolder(), "temporary_blocks.yml");
    private long tickCounter = 0;
    private BukkitTask cleanupTask;
    private BukkitTask saveTask;
    private volatile boolean dirty = false;

    public static void initialize() {
        if (instance == null) {
            instance = new TemporaryBlockManager();
            Bukkit.getPluginManager().registerEvents(instance, TooMuchZombies.getInstance());
            instance.startCleanupTask();
            instance.startSaveTask();
            instance.loadState();
        }
    }

    public static TemporaryBlockManager getInstance() {
        return instance;
    }

    public static void shutdown() {
        if (instance != null) {
            if (instance.cleanupTask != null) {
                instance.cleanupTask.cancel();
            }
            if (instance.saveTask != null) {
                instance.saveTask.cancel();
            }
            instance.saveStateSync();
            instance.clearVisualsOnly();
            instance = null;
        }
    }

    private void startCleanupTask() {
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickCounter++;
                long now = System.currentTimeMillis();
                long decayWindow = ConfigManager.getInstance().getBuilderTemporaryBlockDecayWindowMs();
                int particleIntervalTicks = ConfigManager.getInstance().getBuilderTemporaryBlockDecayParticleIntervalTicks();

                Iterator<Map.Entry<BlockKey, TempBlockEntry>> it = temporaryBlocks.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<BlockKey, TempBlockEntry> mapEntry = it.next();
                    processEntry(mapEntry.getKey(), mapEntry.getValue(), now, decayWindow, particleIntervalTicks);
                }
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 20L, 10L);
    }

    private void startSaveTask() {
        if (!ConfigManager.getInstance().isBuilderTemporaryBlockPersistenceEnabled()) {
            return;
        }
        int interval = ConfigManager.getInstance().getBuilderTemporaryBlockSaveIntervalTicks();
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveStateSync();
            }
        }.runTaskTimerAsynchronously(TooMuchZombies.getInstance(), interval, interval);
    }

    public void registerBlock(Location loc, long durationMs) {
        BlockKey key = normalize(loc);
        Location blockLoc = toLocation(key);
        if (blockLoc == null) return;

        Material expectedType = blockLoc.getBlock().getType();
        unregisterIfTracked(key);
        blockLoc.getBlock().setMetadata(METADATA_KEY, new FixedMetadataValue(TooMuchZombies.getInstance(), true));

        long now = System.currentTimeMillis();
        int effectId = key.hashCode() ^ 0x5F3759DF;
        temporaryBlocks.put(key, new TempBlockEntry(expectedType, now + durationMs, effectId));
        dirty = true;
    }

    public int getTemporaryBlockCount() {
        return temporaryBlocks.size();
    }

    public int getExpiringSoonCount(long withinMs) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (TempBlockEntry e : temporaryBlocks.values()) {
            if (e.expiryAt - now <= withinMs) {
                count++;
            }
        }
        return count;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        unregisterIfTracked(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        unregisterIfTracked(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (org.bukkit.block.Block block : event.blockList()) {
            unregisterIfTracked(block.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        long now = System.currentTimeMillis();
        long decayWindow = ConfigManager.getInstance().getBuilderTemporaryBlockDecayWindowMs();
        int particleIntervalTicks = ConfigManager.getInstance().getBuilderTemporaryBlockDecayParticleIntervalTicks();
        for (Map.Entry<BlockKey, TempBlockEntry> e : temporaryBlocks.entrySet()) {
            BlockKey key = e.getKey();
            if (!key.worldId.equals(event.getWorld().getUID())) continue;
            if ((key.x >> 4) != event.getChunk().getX() || (key.z >> 4) != event.getChunk().getZ()) continue;
            processEntry(key, e.getValue(), now, decayWindow, particleIntervalTicks);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        temporaryBlocks.entrySet().removeIf(e -> e.getKey().worldId.equals(event.getWorld().getUID()));
    }

    private void unregisterIfTracked(Location loc) {
        BlockKey key = normalize(loc);
        unregisterIfTracked(key);
    }

    private void unregisterIfTracked(BlockKey key) {
        TempBlockEntry entry = temporaryBlocks.remove(key);
        if (entry != null) {
            dirty = true;
            Location blockLoc = toLocation(key);
            if (blockLoc != null) {
                blockLoc.getBlock().removeMetadata(METADATA_KEY, TooMuchZombies.getInstance());
            }
            if (TooMuchZombies.getNMSHandler() != null) {
                Location fxLoc = toLocation(key);
                if (fxLoc != null) {
                    TooMuchZombies.getNMSHandler().breakBlockAnimation(entry.effectId, fxLoc, -1);
                }
            }
        }
    }

    private void removeExpired(BlockKey key, TempBlockEntry entry) {
        temporaryBlocks.remove(key, entry);
        dirty = true;
        Location loc = toLocation(key);
        if (loc == null) return;
        if (loc.getBlock().getType() == entry.expectedType) {
            loc.getBlock().setType(Material.AIR);
            loc.getWorld().spawnParticle(Particle.BLOCK, loc.clone().add(0.5, 0.5, 0.5), 20, 0.25, 0.25, 0.25, entry.expectedType.createBlockData());
            loc.getWorld().playSound(loc, Sound.BLOCK_STONE_BREAK, 0.7f, 1.2f);
        }
        loc.getBlock().removeMetadata(METADATA_KEY, TooMuchZombies.getInstance());
        if (TooMuchZombies.getNMSHandler() != null) {
            TooMuchZombies.getNMSHandler().breakBlockAnimation(entry.effectId, loc, -1);
        }
    }

    private void processEntry(BlockKey key, TempBlockEntry entry, long now, long decayWindow, int particleIntervalTicks) {
        Location loc = toLocation(key);
        if (loc == null || loc.getWorld() == null) {
            temporaryBlocks.remove(key, entry);
            return;
        }

        int chunkX = key.x >> 4;
        int chunkZ = key.z >> 4;
        if (!loc.getWorld().isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        if (now >= entry.expiryAt) {
            removeExpired(key, entry);
            return;
        }

        Material currentType = loc.getBlock().getType();
        if (currentType != entry.expectedType) {
            unregisterIfTracked(key);
            return;
        }

        long decayStart = entry.expiryAt - decayWindow;
        if (now < decayStart) {
            return;
        }

        double linearProgress = (double) (now - decayStart) / (double) decayWindow;
        linearProgress = Math.max(0.0, Math.min(1.0, linearProgress));
        int stage = Math.min(9, (int) Math.floor(Math.pow(linearProgress, 0.75) * 10.0));

        if (stage != entry.lastStage) {
            entry.lastStage = stage;
            if (TooMuchZombies.getNMSHandler() != null) {
                TooMuchZombies.getNMSHandler().breakBlockAnimation(entry.effectId, loc, stage);
            }
        }

        if ((tickCounter - entry.lastParticleTick) >= particleIntervalTicks) {
            entry.lastParticleTick = tickCounter;
            loc.getWorld().spawnParticle(
                Particle.BLOCK,
                loc.clone().add(0.5, 0.5, 0.5),
                4,
                0.2, 0.2, 0.2,
                currentType.createBlockData()
            );
        }
    }

    private void clearAllTemporaryBlocks() {
        for (Map.Entry<BlockKey, TempBlockEntry> e : temporaryBlocks.entrySet()) {
            removeExpired(e.getKey(), e.getValue());
        }
        temporaryBlocks.clear();
        dirty = true;
    }

    private void clearVisualsOnly() {
        for (Map.Entry<BlockKey, TempBlockEntry> e : temporaryBlocks.entrySet()) {
            Location loc = toLocation(e.getKey());
            if (loc != null) {
                loc.getBlock().removeMetadata(METADATA_KEY, TooMuchZombies.getInstance());
                if (TooMuchZombies.getNMSHandler() != null) {
                    TooMuchZombies.getNMSHandler().breakBlockAnimation(e.getValue().effectId, loc, -1);
                }
            }
        }
    }

    private void saveStateSync() {
        if (!ConfigManager.getInstance().isBuilderTemporaryBlockPersistenceEnabled()) {
            return;
        }
        if (!dirty) {
            return;
        }

        YamlConfiguration cfg = new YamlConfiguration();
        int idx = 0;
        for (Map.Entry<BlockKey, TempBlockEntry> e : temporaryBlocks.entrySet()) {
            BlockKey key = e.getKey();
            TempBlockEntry entry = e.getValue();
            String base = "blocks." + idx++;
            cfg.set(base + ".world", key.worldId.toString());
            cfg.set(base + ".x", key.x);
            cfg.set(base + ".y", key.y);
            cfg.set(base + ".z", key.z);
            cfg.set(base + ".material", entry.expectedType.name());
            cfg.set(base + ".expiryAt", entry.expiryAt);
        }

        try {
            if (!persistenceFile.getParentFile().exists() && !persistenceFile.getParentFile().mkdirs()) {
                TooMuchZombies.getInstance().getLogger().warning("Failed to create data folder for temporary blocks persistence.");
                return;
            }
            cfg.save(persistenceFile);
            dirty = false;
        } catch (IOException ex) {
            TooMuchZombies.getInstance().getLogger().warning("Failed to save temporary_blocks.yml: " + ex.getMessage());
        }
    }

    private void loadState() {
        if (!ConfigManager.getInstance().isBuilderTemporaryBlockPersistenceEnabled()) {
            return;
        }
        if (!persistenceFile.exists()) {
            return;
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(persistenceFile);
        org.bukkit.configuration.ConfigurationSection sec = cfg.getConfigurationSection("blocks");
        if (sec == null) {
            return;
        }

        long now = System.currentTimeMillis();
        for (String keyName : sec.getKeys(false)) {
            String base = "blocks." + keyName;
            try {
                UUID worldId = UUID.fromString(cfg.getString(base + ".world", ""));
                int x = cfg.getInt(base + ".x");
                int y = cfg.getInt(base + ".y");
                int z = cfg.getInt(base + ".z");
                Material expected = Material.matchMaterial(cfg.getString(base + ".material", ""));
                long expiryAt = cfg.getLong(base + ".expiryAt", now - 1);
                if (expected == null) continue;

                BlockKey blockKey = new BlockKey(worldId, x, y, z);
                Location loc = toLocation(blockKey);
                if (loc == null || loc.getWorld() == null) continue;

                if (expiryAt <= now) {
                    if (loc.getWorld().isChunkLoaded(x >> 4, z >> 4) && loc.getBlock().getType() == expected) {
                        loc.getBlock().setType(Material.AIR);
                    }
                    continue;
                }

                if (loc.getWorld().isChunkLoaded(x >> 4, z >> 4) && loc.getBlock().getType() == expected) {
                    loc.getBlock().setMetadata(METADATA_KEY, new FixedMetadataValue(TooMuchZombies.getInstance(), true));
                }
                int effectId = blockKey.hashCode() ^ 0x5F3759DF;
                temporaryBlocks.put(blockKey, new TempBlockEntry(expected, expiryAt, effectId));
            } catch (Exception ignored) {
            }
        }
        dirty = true;
    }

    private BlockKey normalize(Location loc) {
        return new BlockKey(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private Location toLocation(BlockKey key) {
        org.bukkit.World world = Bukkit.getWorld(key.worldId);
        if (world == null) return null;
        return new Location(world, key.x, key.y, key.z);
    }

    private static final class TempBlockEntry {
        private final Material expectedType;
        private final long expiryAt;
        private final int effectId;
        private int lastStage = -1;
        private long lastParticleTick = 0;

        private TempBlockEntry(Material expectedType, long expiryAt, int effectId) {
            this.expectedType = expectedType;
            this.expiryAt = expiryAt;
            this.effectId = effectId;
        }
    }

    private static final class BlockKey {
        private final UUID worldId;
        private final int x;
        private final int y;
        private final int z;

        private BlockKey(UUID worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockKey)) return false;
            BlockKey that = (BlockKey) o;
            return x == that.x && y == that.y && z == that.z && worldId.equals(that.worldId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(worldId, x, y, z);
        }
    }
}
