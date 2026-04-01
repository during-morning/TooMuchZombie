package com.frigidora.toomuchzombies.ai.pathfinding;

/**
 * 高度类型枚举 - 从 ZombieGame 移植
 * 用于路径规划中处理高度变化
 */
public enum Height {
    DOWN(8),   // 向下（下坡、下楼梯）
    UP(8),     // 向上（上坡、上楼梯）
    NONE(6);   // 平地（无高度变化）
    
    public final int limit; // 该结构需要的方块数量
    
    Height(int limit) {
        this.limit = limit;
    }
}
