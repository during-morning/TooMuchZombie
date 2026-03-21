package com.frigidora.toomuchzombies.mechanics;

import java.util.Random;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;

public class NightHordeManager {

    private static final Random RANDOM = new Random();
    private static NightHordeManager instance;

    public static void initialize() {
        if (instance == null) {
            instance = new NightHordeManager();
        }
    }

    public static NightHordeManager getInstance() {
        return instance;
    }

    private NightHordeManager() {
        startTask();
    }

    private void startTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 100L, 200L);
    }

    private void tick() {
        for (Player player : TooMuchZombies.getInstance().getServer().getOnlinePlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) {
                continue;
            }
            World world = player.getWorld();
            long time = world.getTime();
            if (time < 13000 || time > 23000) {
                continue;
            }
            if (BeaconManager.getInstance().isNearActiveBeacon(player.getLocation(), 42.0)) {
                continue;
            }
            double chance = BloodMoonManager.getInstance().isBloodMoon() ? 0.42 : 0.18;
            if (RANDOM.nextDouble() > chance) {
                continue;
            }
            spawnNightPack(player);
        }
    }

    private void spawnNightPack(Player player) {
        int packSize = 2 + RANDOM.nextInt(BloodMoonManager.getInstance().isBloodMoon() ? 5 : 3);
        for (int i = 0; i < packSize; i++) {
            Location spawnLoc = findSpawnLocation(player);
            if (spawnLoc == null) {
                continue;
            }
            if (!ZombieFactory.evaluateSpawnPipeline(spawnLoc, EntityType.ZOMBIE)) {
                continue;
            }
            Zombie zombie = (Zombie) spawnLoc.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
            ZombieFactory.assignRole(zombie);
            int level = ZombieFactory.calculateEncounterLevelNearby(spawnLoc);
            ZombieFactory.applyLevelAttributes(zombie, level);
            AwarenessManager.getInstance().alertBloodTrail(player, 20.0);
            AwarenessManager.getInstance().alertNoise(spawnLoc, 18.0, player);
        }
    }

    private Location findSpawnLocation(Player player) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double radius = 18.0 + RANDOM.nextDouble() * 18.0;
            Location base = player.getLocation().clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            int y = base.getWorld().getHighestBlockYAt(base);
            Location spawn = new Location(base.getWorld(), base.getX() + 0.5, y + 1.0, base.getZ() + 0.5);
            if (!spawn.getChunk().isLoaded()) {
                continue;
            }
            if (!spawn.getBlock().isPassable() || !spawn.clone().add(0, 1, 0).getBlock().isPassable()) {
                continue;
            }
            if (!spawn.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                continue;
            }
            if (spawn.distanceSquared(player.getLocation()) < 14 * 14) {
                continue;
            }
            return spawn;
        }
        return null;
    }
}
