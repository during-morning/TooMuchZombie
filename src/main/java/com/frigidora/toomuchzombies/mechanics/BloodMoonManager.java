package com.frigidora.toomuchzombies.mechanics;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.config.LanguageManager;

public class BloodMoonManager {

    private static BloodMoonManager instance;

    private final Set<UUID> activeWorlds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastCheckedDay = new ConcurrentHashMap<>();

    public static void initialize() {
        if (instance == null) {
            instance = new BloodMoonManager();
        }
    }

    public static BloodMoonManager getInstance() {
        if (instance == null) {
            instance = new BloodMoonManager();
        }
        return instance;
    }

    private BloodMoonManager() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    if (world.getEnvironment() == World.Environment.NORMAL) {
                        checkBloodMoon(world);
                    }
                }
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 100L, 100L);
    }

    public void checkBloodMoon(World world) {
        long time = world.getTime();
        long day = world.getFullTime() / 24000L;
        UUID worldId = world.getUID();

        if (time >= 12000L && time < 13000L) {
            Long checked = lastCheckedDay.get(worldId);
            if (checked == null || checked.longValue() != day) {
                lastCheckedDay.put(worldId, day);
                if (Math.random() < ConfigManager.getInstance().getBloodMoonChance()) {
                    startBloodMoonSequence(world);
                } else {
                    stopBloodMoon(world);
                }
            }
            return;
        }

        if (time >= 23000L || time < 12000L) {
            stopBloodMoon(world);
        }
    }

    public void startBloodMoonSequence(World world) {
        if (!activeWorlds.add(world.getUID())) {
            return;
        }
        Bukkit.broadcastMessage(LanguageManager.getInstance().getMessage("blood-moon-descends"));
    }

    public void startBrightMoonSequence(World world) {
        // 旧事件系统已移除，仅保留空实现以兼容旧调用。
    }

    public void stopBloodMoon(World world) {
        activeWorlds.remove(world.getUID());
    }

    public boolean isBloodMoon() {
        return !activeWorlds.isEmpty();
    }

    public boolean isBloodMoon(World world) {
        return world != null && activeWorlds.contains(world.getUID());
    }

    public boolean isBrightMoon() {
        return false;
    }
}
