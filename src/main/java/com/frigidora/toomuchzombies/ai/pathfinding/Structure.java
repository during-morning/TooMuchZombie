package com.frigidora.toomuchzombies.ai.pathfinding;

import org.bukkit.Location;

/**
 * 结构枚举 - 从 ZombieGame 完整移植
 * 定义了僵尸建造路径的各种结构模式
 * 
 * 每种结构定义了一系列方块的放置/清除顺序，用于：
 * - 平地前进（NORTH, SOUTH, WEST, EAST）
 * - 上坡/爬墙（NORTH_UP, SOUTH_UP, WEST_UP, EAST_UP）
 * - 下坡/下楼（NORTH_DOWN, SOUTH_DOWN, WEST_DOWN, EAST_DOWN）
 */
public enum Structure {
    NORTH(Direction.NORTH, Height.NONE),
    SOUTH(Direction.SOUTH, Height.NONE),
    WEST(Direction.WEST, Height.NONE),
    EAST(Direction.EAST, Height.NONE),
    
    NORTH_UP(Direction.NORTH, Height.UP),
    SOUTH_UP(Direction.SOUTH, Height.UP),
    WEST_UP(Direction.WEST, Height.UP),
    EAST_UP(Direction.EAST, Height.UP),
    
    NORTH_DOWN(Direction.NORTH, Height.DOWN),
    SOUTH_DOWN(Direction.SOUTH, Height.DOWN),
    WEST_DOWN(Direction.WEST, Height.DOWN),
    EAST_DOWN(Direction.EAST, Height.DOWN);
    
    private final Direction direction;
    private final Height height;
    
    Structure(Direction direction, Height height) {
        this.direction = direction;
        this.height = height;
    }
    
    /**
     * 根据方向和高度创建结构
     */
    public static Structure create(Direction direction, Height height) {
        if (direction == Direction.SOUTH) {
            return switch (height) {
                case UP -> SOUTH_UP;
                case DOWN -> SOUTH_DOWN;
                default -> SOUTH;
            };
        } else if (direction == Direction.EAST) {
            return switch (height) {
                case UP -> EAST_UP;
                case DOWN -> EAST_DOWN;
                default -> EAST;
            };
        } else if (direction == Direction.WEST) {
            return switch (height) {
                case UP -> WEST_UP;
                case DOWN -> WEST_DOWN;
                default -> WEST;
            };
        } else {
            return switch (height) {
                case UP -> NORTH_UP;
                case DOWN -> NORTH_DOWN;
                default -> NORTH;
            };
        }
    }
    
    /**
     * 沿方向移动并调整高度
     */
    private Location addOffset(Location loc, int forward, int up) {
        Location result = direction.relative(loc, forward);
        result.add(0, up, 0);
        return result;
    }
    
    /**
     * 获取结构中第 i 个方块的位置
     * 这是 ZombieGame 的核心算法，定义了建造顺序
     */
    public Location getBlockLocation(int i, Location stand) {
        if (height == Height.NONE) {
            // 平地前进：6个方块
            return switch (i) {
                case 1 -> addOffset(stand, 0, -1);  // 脚下
                case 2 -> addOffset(stand, 0, 1);   // 头上（清除）
                case 3 -> addOffset(stand, 0, 0);   // 身体（清除）
                case 4 -> addOffset(stand, 1, -1);  // 前方脚下
                case 5 -> addOffset(stand, 1, 1);   // 前方头上（清除）
                case 6 -> addOffset(stand, 1, 0);   // 前方身体（清除）
                default -> stand;
            };
        } else if (height == Height.DOWN) {
            // 下坡：8个方块
            return switch (i) {
                case 1 -> addOffset(stand, 0, -1);  // 脚下
                case 2 -> addOffset(stand, 0, 1);   // 头上（清除）
                case 3 -> addOffset(stand, 0, 0);   // 身体（清除）
                case 4 -> addOffset(stand, 0, -2);  // 脚下-2（支撑）
                case 5 -> addOffset(stand, 1, -2);  // 前方脚下-2（支撑）
                case 6 -> addOffset(stand, 1, 1);   // 前方头上（清除）
                case 7 -> addOffset(stand, 1, 0);   // 前方身体（清除）
                case 8 -> addOffset(stand, 1, -1);  // 前方脚下（清除，让僵尸掉下去）
                default -> stand;
            };
        } else if (height == Height.UP) {
            // 上坡：8个方块
            return switch (i) {
                case 1 -> addOffset(stand, 0, -1);  // 脚下
                case 2 -> addOffset(stand, 0, 1);   // 头上（清除）
                case 3 -> addOffset(stand, 0, 0);   // 身体（清除）
                case 4 -> addOffset(stand, 0, 2);   // 头上+2（清除）
                case 5 -> addOffset(stand, 1, -1);  // 前方脚下（台阶）
                case 6 -> addOffset(stand, 1, 0);   // 前方身体（台阶）
                case 7 -> addOffset(stand, 1, 2);   // 前方头上+2（清除）
                case 8 -> addOffset(stand, 1, 1);   // 前方头上（清除）
                default -> stand;
            };
        } else {
            return stand;
        }
    }
    
    /**
     * 获取第 i 个方块应该是什么类型
     */
    public BlockKind getBlockKind(int i) {
        if (height == Height.NONE) {
            return switch (i) {
                case 1, 4 -> BlockKind.BLOCK;           // 需要放置方块
                case 2, 3, 5, 6 -> BlockKind.AIR;       // 需要清除方块
                default -> throw new IllegalArgumentException("Invalid block index: " + i);
            };
        } else if (height == Height.DOWN) {
            return switch (i) {
                case 1, 4, 5 -> BlockKind.BLOCK;        // 需要放置方块
                case 2, 3, 6, 7, 8 -> BlockKind.AIR;    // 需要清除方块
                default -> throw new IllegalArgumentException("Invalid block index: " + i);
            };
        } else if (height == Height.UP) {
            return switch (i) {
                case 1, 5, 6 -> BlockKind.BLOCK;        // 需要放置方块
                case 2, 3, 4, 7, 8 -> BlockKind.AIR;    // 需要清除方块
                default -> throw new IllegalArgumentException("Invalid block index: " + i);
            };
        } else {
            return null;
        }
    }
    
    public Direction getDirection() {
        return direction;
    }
    
    public Height getHeight() {
        return height;
    }
    
    public int getTotalBlockNum() {
        return height.limit;
    }
}
