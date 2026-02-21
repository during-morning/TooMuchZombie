package com.frigidora.toomuchzombies.nms;

import org.bukkit.Location;
import org.bukkit.entity.Zombie;

public interface NMSHandler {
    void injectCustomAI(Zombie zombie);
    void moveTo(Zombie zombie, Location location, double speed);
    void breakBlockAnimation(int entityId, Location blockLocation, int stage);
    void setAggressive(Zombie zombie, boolean aggressive);
}
