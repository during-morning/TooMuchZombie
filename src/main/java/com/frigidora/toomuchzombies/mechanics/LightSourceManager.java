package com.frigidora.toomuchzombies.mechanics;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;

public class LightSourceManager {

    private static LightSourceManager instance;
    private final Set<Location> lightSources = Collections.synchronizedSet(new HashSet<>());

    private LightSourceManager() {
        startTask();
    }

    public static void initialize() {
        if (instance == null) {
            instance = new LightSourceManager();
        }
    }

    public static LightSourceManager getInstance() {
        return instance;
    }

    private void startTask() {
        // Every 10 ticks (0.5s) scan players
        new BukkitRunnable() {
            @Override
            public void run() {
                scanPlayers();
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 10L, 10L);
    }

    private void scanPlayers() {
        Set<Location> newSources = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isHoldingLight(p)) {
                Location source = p.getLocation();
                newSources.add(source);
                AwarenessManager.getInstance().alertLightAttraction(source, 48.0);
            }
        }
        
        synchronized (lightSources) {
            lightSources.clear();
            lightSources.addAll(newSources);
        }
    }

    public Location getNearestLightSource(Location loc, double maxDistance) {
        Location ambientLight = getAmbientLightLocation(loc, maxDistance);
        double bestDistSq = ambientLight != null ? ambientLight.distanceSquared(loc) : Double.MAX_VALUE;
        Location nearest = ambientLight;

        synchronized (lightSources) {
            for (Location lightLoc : lightSources) {
                if (!lightLoc.getWorld().equals(loc.getWorld())) continue;

                double dstSq = lightLoc.distanceSquared(loc);
                if (dstSq < maxDistance * maxDistance && dstSq < bestDistSq) {
                    bestDistSq = dstSq;
                    nearest = lightLoc;
                }
            }
        }
        return nearest;
    }

    public boolean isExposedToStrongLight(Location loc) {
        return getAmbientLightLocation(loc, 8.0) != null;
    }

    private Location getAmbientLightLocation(Location loc, double maxDistance) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }

        Block block = loc.getBlock();
        int blockLight = block.getLightFromBlocks();
        int skyLight = block.getLightFromSky();
        boolean day = loc.getWorld().getTime() >= 0 && loc.getWorld().getTime() < 12000;

        if (blockLight >= 10 || (day && skyLight >= 10 && loc.getWorld().getHighestBlockYAt(loc) <= loc.getY() + 1.0)) {
            return block.getLocation().add(0.5, 0.5, 0.5);
        }
        return null;
    }

    private boolean isHoldingLight(Player p) {
        return isLightBlock(p.getInventory().getItemInMainHand()) || 
               isLightBlock(p.getInventory().getItemInOffHand());
    }

    public static boolean isAttractingLight(Material type) {
        if (type == null) {
            return false;
        }

        if (type == Material.LIGHT || type == Material.TORCH || type == Material.SOUL_TORCH
            || type == Material.LANTERN || type == Material.SOUL_LANTERN
            || type == Material.GLOWSTONE || type == Material.SEA_LANTERN
            || type == Material.JACK_O_LANTERN || type == Material.SHROOMLIGHT) {
            return true;
        }

        String name = type.name();
        return name.contains("TORCH") || name.contains("LANTERN") || name.contains("CANDLE")
            || name.contains("GLOWSTONE") || name.contains("SEA_LANTERN") || name.contains("SHROOMLIGHT");
    }

    private boolean isLightBlock(ItemStack item) {
        return item != null && isAttractingLight(item.getType());
    }
}
