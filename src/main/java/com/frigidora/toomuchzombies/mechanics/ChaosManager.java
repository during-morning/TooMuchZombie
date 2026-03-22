package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.World;
import org.bukkit.entity.Zombie;

/**
 * 旧事件系统兼容壳。当前版本仅保留血月事件，混沌事件已停用。
 */
@Deprecated
public class ChaosManager {

    private static final ChaosManager INSTANCE = new ChaosManager();

    public static void initialize() {
        // no-op
    }

    public static ChaosManager getInstance() {
        return INSTANCE;
    }

    public void checkChaos(World world) {
        // no-op
    }

    public void startChaos(World world) {
        // no-op
    }

    public void stopChaos(World world) {
        // no-op
    }

    public void applyChaosAI(Zombie zombie) {
        // no-op
    }

    public boolean isChaosNight() {
        return false;
    }
}
