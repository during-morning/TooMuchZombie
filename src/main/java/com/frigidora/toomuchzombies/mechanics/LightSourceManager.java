package com.frigidora.toomuchzombies.mechanics;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
                newSources.add(p.getLocation());
            }
        }
        
        synchronized (lightSources) {
            lightSources.clear();
            lightSources.addAll(newSources);
        }
    }

    public Location getNearestLightSource(Location loc, double maxDistance) {
        if (lightSources.isEmpty()) return null;
        
        Location nearest = null;
        double minDstSq = maxDistance * maxDistance;

        synchronized (lightSources) {
            for (Location lightLoc : lightSources) {
                if (!lightLoc.getWorld().equals(loc.getWorld())) continue;
                
                double dstSq = lightLoc.distanceSquared(loc);
                if (dstSq < minDstSq) {
                    minDstSq = dstSq;
                    nearest = lightLoc;
                }
            }
        }
        return nearest;
    }

    private boolean isHoldingLight(Player p) {
        return isLightBlock(p.getInventory().getItemInMainHand()) || 
               isLightBlock(p.getInventory().getItemInOffHand());
    }

    private boolean isLightBlock(ItemStack item) {
        if (item == null || item.getType() != Material.LIGHT) return false;
        
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String displayName = meta.getDisplayName();
        }
        return false;
    }
}
