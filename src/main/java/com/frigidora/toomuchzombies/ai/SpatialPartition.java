package com.frigidora.toomuchzombies.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;

/**
 * Spatial partitioning helper to quickly find agents in nearby chunks.
 * Uses a simple grid based on Chunk coordinates.
 */
public class SpatialPartition {

    // Map<ChunkKey, Set<ZombieAgent>>
    private final Map<Long, Set<ZombieAgent>> grid = new ConcurrentHashMap<>();

    public void update(ZombieAgent agent) {
        if (agent.getZombie() == null || !agent.getZombie().isValid()) {
            remove(agent);
            return;
        }

        Location loc = agent.getZombie().getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        long key = getChunkKey(chunkX, chunkZ);

        long lastKey = agent.getLastSpatialKey();
        
        // Only update if changed chunk
        if (key != lastKey) {
            if (lastKey != Long.MIN_VALUE) {
                removeFromKey(lastKey, agent);
            }
            addToKey(key, agent);
            agent.setLastSpatialKey(key);
        }
    }

    public void remove(ZombieAgent agent) {
        long key = agent.getLastSpatialKey();
        if (key != Long.MIN_VALUE) {
            removeFromKey(key, agent);
            agent.setLastSpatialKey(Long.MIN_VALUE);
        }
    }

    public List<ZombieAgent> getNearbyAgents(Location loc, int radius) {
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        int chunkRadius = (radius >> 4) + 1;

        List<ZombieAgent> result = new ArrayList<>();

        for (int x = chunkX - chunkRadius; x <= chunkX + chunkRadius; x++) {
            for (int z = chunkZ - chunkRadius; z <= chunkZ + chunkRadius; z++) {
                long key = getChunkKey(x, z);
                Set<ZombieAgent> agents = grid.get(key);
                if (agents != null) {
                    // 我们需要安全地迭代。
                    // 既然我们对 grid 使用了 ConcurrentHashMap，但 Set 值可能是同步的。
                    // 复制或迭代同步集合。
                    synchronized (agents) {
                        for (ZombieAgent agent : agents) {
                            if (agent.getZombie().isValid() && agent.getZombie().getWorld().equals(loc.getWorld())) {
                                if (agent.getZombie().getLocation().distanceSquared(loc) <= radius * radius) {
                                    result.add(agent);
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private void addToKey(long key, ZombieAgent agent) {
        grid.computeIfAbsent(key, k -> Collections.synchronizedSet(new HashSet<>())).add(agent);
    }

    private void removeFromKey(long key, ZombieAgent agent) {
        Set<ZombieAgent> set = grid.get(key);
        if (set != null) {
            set.remove(agent);
            if (set.isEmpty()) {
                grid.remove(key);
            }
        }
    }

    private long getChunkKey(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }
}
