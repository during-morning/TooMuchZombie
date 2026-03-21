package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.World;
import org.bukkit.entity.Zombie;

public class ChaosManager {

    private static ChaosManager instance;

    public static void initialize() {
        if (instance == null) {
            instance = new ChaosManager();
        }
    }

    public static ChaosManager getInstance() {
        if (instance == null) {
            initialize();
        }
        return instance;
    }

    public void checkChaos(World world) {
        // 混沌事件已移除，保留空实现以兼容旧调用。
    }

    public void startChaos(World world) {
        // 混沌事件已移除，保留空实现以兼容旧调用。
    }

    public void stopChaos(World world) {
        // 混沌事件已移除，保留空实现以兼容旧调用。
    }

    public void applyChaosAI(Zombie zombie) {
        // 混沌事件已移除，保留空实现以兼容旧调用。
    }

    public boolean isChaosNight() {
        return false;
    }
}
