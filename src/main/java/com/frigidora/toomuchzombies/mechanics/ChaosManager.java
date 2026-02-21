package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.LanguageManager;
import com.frigidora.toomuchzombies.nms.PaperNMSHandler;

public class ChaosManager {

    private static ChaosManager instance;
    private boolean isChaosNight = false;
    private long lastCheckedDay = -1;

    public static void initialize() {
        instance = new ChaosManager();
        // 开始检查日落时间
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    if (world.getEnvironment() == World.Environment.NORMAL) {
                        checkTime(world);
                    }
                }
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 100L, 100L); // 每 5 秒检查一次
    }

    public static ChaosManager getInstance() {
        return instance;
    }
    
    private static void checkTime(World world) {
        long time = world.getTime();
        long day = world.getFullTime() / 24000;
        
        // 日落大约在 12000-13000
        if (time >= 12000 && time < 13000) {
            if (getInstance().lastCheckedDay != day) {
                getInstance().lastCheckedDay = day;
                getInstance().checkChaos(world);
            }
        }
        // 日出大约在 23000
        if (time >= 23000 && time < 23100) {
             getInstance().stopChaos(world);
        }
    }

    public void checkChaos(World world) {
        if (isChaosNight) return;
        if (BloodMoonManager.getInstance().isBloodMoon()) return; // 不要叠加事件（可选）

        // 5% 概率，独立于血月
        if (Math.random() < 0.05) {
            startChaos(world);
        }
    }

    public void startChaos(World world) {
        if (isChaosNight) return;
        isChaosNight = true;
        Bukkit.broadcastMessage(LanguageManager.getInstance().getMessage("chaos-descends"));
        
        // 将 AI 注入现有的僵尸
        for (Entity e : world.getEntities()) {
            if (e instanceof Zombie) {
                applyChaosAI((Zombie) e);
            }
        }
    }

    public void stopChaos(World world) {
        if (!isChaosNight) return;
        isChaosNight = false;
        Bukkit.broadcastMessage(LanguageManager.getInstance().getMessage("chaos-fades"));
        
        // 移除 AI？或者就让它们这样吧。
        // 如果不存储它们，很难移除特定的目标。
        // 但既然它们会死亡/消失，这也没关系。
        // 新生成的僵尸不会获得 AI。
    }

    public void applyChaosAI(Zombie zombie) {
        if (!isChaosNight) return;
        // 使用 NMS/Paper 处理器添加锁定目标目标
        if (TooMuchZombies.getNMSHandler() instanceof PaperNMSHandler) {
            ((PaperNMSHandler) TooMuchZombies.getNMSHandler()).injectChaosTarget(zombie);
        }
    }

    public boolean isChaosNight() {
        return isChaosNight;
    }
}
