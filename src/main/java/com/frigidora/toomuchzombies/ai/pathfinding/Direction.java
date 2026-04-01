package com.frigidora.toomuchzombies.ai.pathfinding;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * 方向枚举 - 从 ZombieGame 移植并适配 Bukkit
 * 用于路径规划中的方向判断
 */
public enum Direction {
    NORTH(0, 0, -1),   // Z-
    SOUTH(0, 0, 1),    // Z+
    WEST(-1, 0, 0),    // X-
    EAST(1, 0, 0);     // X+
    
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;
    
    Direction(int offsetX, int offsetY, int offsetZ) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }
    
    public int getOffsetX() {
        return offsetX;
    }
    
    public int getOffsetY() {
        return offsetY;
    }
    
    public int getOffsetZ() {
        return offsetZ;
    }
    
    /**
     * 将位置沿此方向移动指定距离
     */
    public Location relative(Location loc, int distance) {
        return loc.clone().add(offsetX * distance, offsetY * distance, offsetZ * distance);
    }
    
    /**
     * 随机选择一个水平方向（不包括上下）
     */
    public static Direction randomHorizontal() {
        Random random = new Random();
        int i = random.nextInt(4);
        return switch (i) {
            case 0 -> NORTH;
            case 1 -> SOUTH;
            case 2 -> WEST;
            default -> EAST;
        };
    }
    
    /**
     * 根据两个位置计算方向
     */
    public static Direction fromLocations(Location from, Location to) {
        int dx = to.getBlockX() - from.getBlockX();
        int dz = to.getBlockZ() - from.getBlockZ();
        
        // 使用 ZombieGame 的算法
        if (dz <= dx && dz < -dx) {
            return SOUTH;
        } else if (dz > dx && dz <= -dx) {
            return EAST;
        } else if (dz >= dx && dz > -dx) {
            return NORTH;
        } else if (dz < dx && dz >= -dx) {
            return WEST;
        } else {
            return randomHorizontal();
        }
    }
}
