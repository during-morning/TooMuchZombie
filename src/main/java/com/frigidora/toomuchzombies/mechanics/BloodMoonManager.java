package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.ConfigManager;

public class BloodMoonManager {

    private static BloodMoonManager instance;
    private boolean isBloodMoon = false;
    private boolean isBrightMoon = false; // 淡蓝明月
    private long lastCheckedDay = -1;
    
    // 常量
    private static final long BLOOD_MOON_DURATION_TICKS = 12000; // 10分钟 = 600秒 = 12000 tick
    private static final long TRANSITION_DURATION_TICKS = 200; // 10秒 = 200 tick
    private static final long MIDNIGHT = 18000;
    private static final long SUNRISE = 23000; // 或 24000/0

    public static void initialize() {
        instance = new BloodMoonManager();
        instance.startScheduler();
    }

    public static BloodMoonManager getInstance() {
        return instance;
    }
    
    private void startScheduler() {
        // 每 100 tick (5 秒) 检查一次
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    if (world.getEnvironment() == World.Environment.NORMAL) {
                        checkTime(world);
                    }
                }
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 100L, 100L);
    }
    
    private void checkTime(World world) {
        long time = world.getTime();
        long day = world.getFullTime() / 24000;
        
        if (isBloodMoon) {
            // 如果已经是血月，检查是否到达白天 (0 - 12000)
            // 通常 23000 是日出，0 是早上，12000 是日落
            // 我们在 23000 到 24000 之间或 0 到 1000 之间结束它
            if (time >= 23000 && time < 23500) {
                endBloodMoonSequence(world);
            }
            return;
        }
        
        // 在日落附近检查 (12000)
        if (time >= 12000 && time < 13000) {
            if (lastCheckedDay != day) {
                lastCheckedDay = day;
                checkBloodMoon(world);
            }
        }
    }

    public void checkBloodMoon(World world) {
        if (isBloodMoon || isBrightMoon) return;
        
        double randomVal = Math.random();
        double bloodMoonChance = ConfigManager.getInstance().getBloodMoonChance(); // 5% (0.05)
        double brightMoonChance = 0.25; // 25%

        // 优先判断血月
        if (randomVal < bloodMoonChance) { 
            startBloodMoonSequence(world);
        } 
        // 其次判断明月 (概率区间 [bloodMoonChance, bloodMoonChance + brightMoonChance))
        else if (randomVal < bloodMoonChance + brightMoonChance) {
            startBrightMoonSequence(world);
        }
    }

    public void startBloodMoonSequence(World world) {
        if (isBloodMoon) return;
        isBloodMoon = true;
        Bukkit.broadcastMessage(ChatColor.RED + "血月升起了...");
    }

    public void startBrightMoonSequence(World world) {
        if (isBrightMoon) return;
        isBrightMoon = true;
        Bukkit.broadcastMessage(ChatColor.AQUA + "明月升起了...");
    }
    
    // 移除 startBloodMoonDuration，因为现在依靠 checkTime 自动结束
    
    private void endBloodMoonSequence(World world) {
        if (isBloodMoon) {
            Bukkit.broadcastMessage(ChatColor.RED + "血月落下了...");
            stopBloodMoon(world);
        } else if (isBrightMoon) {
            // 明月结束可能不需要特别提示，或者提示“夜晚结束”
            stopBloodMoon(world);
        }
    }

    public void stopBloodMoon(World world) {
        isBloodMoon = false;
        isBrightMoon = false;
        // 不需要恢复 DO_DAYLIGHT_CYCLE，因为我们没有禁用它
    }

    public boolean isBloodMoon() {
        return isBloodMoon;
    }
    
    public boolean isBrightMoon() {
        return isBrightMoon;
    }
}
