package com.frigidora.toomuchzombies.ai.pathfinding;

/**
 * 方块类型枚举 - 从 ZombieGame 移植
 * 用于路径规划中区分需要放置方块还是清除方块
 */
public enum BlockKind {
    AIR,    // 需要清除的方块（破坏）
    BLOCK   // 需要放置的方块（建造）
}
