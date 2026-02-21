package com.frigidora.toomuchzombies.mechanics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.ConfigManager;

public class TemporaryBlockManager implements Listener {

    private static TemporaryBlockManager instance;
    private final Map<Location, TempBlockEntry> temporaryBlocks = new ConcurrentHashMap<>();
    private final String METADATA_KEY = "ZombieBlock";
    private long tickCounter = 0;

    public static void initialize() {
        if (instance == null) {
            instance = new TemporaryBlockManager();
            Bukkit.getPluginManager().registerEvents(instance, TooMuchZombies.getInstance());
            instance.startCleanupTask();
        }
    }

    public static TemporaryBlockManager getInstance() {
        return instance;
    }

    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tickCounter++;
                long now = System.currentTimeMillis();
                long decayWindow = ConfigManager.getInstance().getBuilderTemporaryBlockDecayWindowMs();
                int particleIntervalTicks = ConfigManager.getInstance().getBuilderTemporaryBlockDecayParticleIntervalTicks();

                temporaryBlocks.forEach((loc, entry) -> {
                    if (now >= entry.expiryAt) {
                        removeExpired(loc, entry);
                        return;
                    }

                    Material currentType = loc.getBlock().getType();
                    if (currentType != entry.expectedType) {
                        unregisterIfTracked(loc);
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
                });
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 20L, 10L);
    }

    public void registerBlock(Location loc, long durationMs) {
        Location key = normalize(loc);
        Material expectedType = key.getBlock().getType();
        unregisterIfTracked(key);
        key.getBlock().setMetadata(METADATA_KEY, new FixedMetadataValue(TooMuchZombies.getInstance(), true));

        long now = System.currentTimeMillis();
        int effectId = key.hashCode() ^ 0x5F3759DF;
        temporaryBlocks.put(key, new TempBlockEntry(expectedType, now + durationMs, effectId));
    }

    public int getTemporaryBlockCount() {
        return temporaryBlocks.size();
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

    private void unregisterIfTracked(Location loc) {
        Location key = normalize(loc);
        TempBlockEntry entry = temporaryBlocks.remove(key);
        if (entry != null) {
            key.getBlock().removeMetadata(METADATA_KEY, TooMuchZombies.getInstance());
            if (TooMuchZombies.getNMSHandler() != null) {
                TooMuchZombies.getNMSHandler().breakBlockAnimation(entry.effectId, key, -1);
            }
        }
    }

    private void removeExpired(Location loc, TempBlockEntry entry) {
        temporaryBlocks.remove(loc);
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

    private Location normalize(Location loc) {
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
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
}
