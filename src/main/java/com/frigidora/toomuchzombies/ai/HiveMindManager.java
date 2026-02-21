package com.frigidora.toomuchzombies.ai;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;

import com.frigidora.toomuchzombies.config.ConfigManager;

import java.util.Collection;

public class HiveMindManager {

    public void alertNearbyZombies(Location targetLocation, Zombie sourceZombie) {
        int range = ConfigManager.getInstance().getHiveMindSensorRange();
        
        Collection<Entity> nearby = sourceZombie.getWorld().getNearbyEntities(sourceZombie.getLocation(), range, range, range);
        for (Entity entity : nearby) {
            if (entity instanceof Zombie && !entity.equals(sourceZombie)) {
                ZombieAgent agent = ZombieAIManager.getInstance().getAgent(entity.getUniqueId());
                if (agent != null) {
                    agent.setLastKnownTargetLocation(targetLocation);
                    // 触发“加入狩猎”
                }
            }
        }
    }

    public void broadcastTarget(Player player, Zombie sourceZombie) {
        alertNearbyZombies(player.getLocation(), sourceZombie);
    }
}
