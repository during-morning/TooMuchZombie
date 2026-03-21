package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.config.LanguageManager;

public class BloodMoonManager {

    private static BloodMoonManager instance;
    private boolean bloodMoon = false;
    private long lastCheckedDay = Long.MIN_VALUE;

    public static void initialize() {
        if (instance == null) {
            instance = new BloodMoonManager();
            instance.startScheduler();
        }
    }

    public static BloodMoonManager getInstance() {
        if (instance == null) {
            initialize();
        }
        return instance;
    }

    private void startScheduler() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    if (world.getEnvironment() == World.Environment.NORMAL) {
                        tickWorld(world);
                    }
                }
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 100L, 100L);
    }

    private void tickWorld(World world) {
        long time = world.getTime();
        long day = world.getFullTime() / 24000L;

        if (bloodMoon) {
            if (time >= 23000 && time < 23500) {
                stopBloodMoon(world);
            }
            return;
        }

        if (time >= 12000 && time < 13000 && lastCheckedDay != day) {
            lastCheckedDay = day;
            checkBloodMoon(world);
        }
    }

    public void checkBloodMoon(World world) {
        if (bloodMoon) {
            return;
        }
        if (Math.random() <= ConfigManager.getInstance().getBloodMoonChance()) {
            startBloodMoonSequence(world);
        }
    }

    public void startBloodMoonSequence(World world) {
        if (bloodMoon) {
            return;
        }
        bloodMoon = true;
        Bukkit.broadcastMessage(LanguageManager.getInstance().getMessage("blood-moon-triggered"));
    }

    public void startBrightMoonSequence(World world) {
        // 明月事件已移除，仅保留兼容空实现。
    }

    public void stopBloodMoon(World world) {
        bloodMoon = false;
    }

    public boolean isBloodMoon() {
        return bloodMoon;
    }

    public boolean isBrightMoon() {
        return false;
    }
}
