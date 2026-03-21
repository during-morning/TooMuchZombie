package com.frigidora.toomuchzombies.mechanics;

import org.bukkit.World;

public class BloodMoonManager {

    private static BloodMoonManager instance;

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

    public void checkBloodMoon(World world) {
        // 血月事件已移除，保留空实现以兼容旧调用。
    }

    public void startBloodMoonSequence(World world) {
        // 血月事件已移除，保留空实现以兼容旧调用。
    }

    public void startBrightMoonSequence(World world) {
        // 明月事件已移除，保留空实现以兼容旧调用。
    }

    public void stopBloodMoon(World world) {
        // 血月事件已移除，保留空实现以兼容旧调用。
    }

    public boolean isBloodMoon() {
        return false;
    }

    public boolean isBrightMoon() {
        return false;
    }
}
