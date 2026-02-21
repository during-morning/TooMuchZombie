package com.frigidora.toomuchzombies.ai.behavior.structs;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

public enum Structure {

    NORTH(BlockFace.NORTH, Height.NONE), SOUTH(BlockFace.SOUTH, Height.NONE),
    WEST(BlockFace.WEST, Height.NONE), EAST(BlockFace.EAST, Height.NONE),
    NORTH_UP(BlockFace.NORTH, Height.UP), SOUTH_UP(BlockFace.SOUTH, Height.UP),
    WEST_UP(BlockFace.WEST, Height.UP), EAST_UP(BlockFace.EAST, Height.UP),

    NORTH_DOWN(BlockFace.NORTH, Height.DOWN), SOUTH_DOWN(BlockFace.SOUTH, Height.DOWN),
    WEST_DOWN(BlockFace.WEST, Height.DOWN), EAST_DOWN(BlockFace.EAST, Height.DOWN),

    NORTH_VERTICAL(BlockFace.NORTH, Height.VERTICAL), SOUTH_VERTICAL(BlockFace.SOUTH, Height.VERTICAL),
    WEST_VERTICAL(BlockFace.WEST, Height.VERTICAL), EAST_VERTICAL(BlockFace.EAST, Height.VERTICAL),
    ;

    private final BlockFace direction;
    private final Height height;

    Structure(BlockFace direction, Height height) {
        this.direction = direction;
        this.height = height;
    }

    public static Structure change(BlockFace direction, Height height) {
        if (height == Height.VERTICAL) {
            return switch (direction) {
                case SOUTH -> Structure.SOUTH_VERTICAL;
                case EAST -> Structure.EAST_VERTICAL;
                case WEST -> Structure.WEST_VERTICAL;
                default -> Structure.NORTH_VERTICAL;
            };
        }
        if (direction == BlockFace.SOUTH) {
            if (height == Height.UP) {
                return Structure.SOUTH_UP;
            } else if (height == Height.DOWN) {
                return Structure.SOUTH_DOWN;
            } else {
                return Structure.SOUTH;
            }
        } else if (direction == BlockFace.EAST) {
            if (height == Height.UP) {
                return Structure.EAST_UP;
            } else if (height == Height.DOWN) {
                return Structure.EAST_DOWN;
            } else {
                return Structure.EAST;
            }
        } else if (direction == BlockFace.WEST) {
            if (height == Height.UP) {
                return Structure.WEST_UP;
            } else if (height == Height.DOWN) {
                return Structure.WEST_DOWN;
            } else {
                return Structure.WEST;
            }
        } else {
            if (height == Height.UP) {
                return Structure.NORTH_UP;
            } else if (height == Height.DOWN) {
                return Structure.NORTH_DOWN;
            } else {
                return Structure.NORTH;
            }
        }
    }

    private Block addNum(Block block, int forward, int up) {
        // relative(direction, forward) isn't directly available in Bukkit Block, so we loop
        Block current = block;
        for (int i = 0; i < forward; i++) {
            current = current.getRelative(this.direction);
        }
        // Then apply vertical
        if (up > 0) {
            for (int i = 0; i < up; i++) current = current.getRelative(BlockFace.UP);
        } else if (up < 0) {
            for (int i = 0; i < -up; i++) current = current.getRelative(BlockFace.DOWN);
        }
        return current;
    }

    public Block getBlock(int i, Block stand) {
        if (this.height == Height.NONE) {
            return switch (i) {
                case 1 -> this.addNum(stand, 0, -1); // 保证脚下有方块
                case 2 -> this.addNum(stand, 1, -1); // 前方脚下有方块
                case 3 -> this.addNum(stand, 0, 0);  // 身体位置空气
                case 4 -> this.addNum(stand, 0, 1);  // 头部位置空气
                case 5 -> this.addNum(stand, 1, 0);  // 前方身体空气
                case 6 -> this.addNum(stand, 1, 1);  // 前方头部空气
                default -> stand;
            };
        } else if (this.height == Height.DOWN) {
            return switch (i) {
                case 1 -> this.addNum(stand, 0, -1); // 脚下
                case 2 -> this.addNum(stand, 1, -1); // 前方脚下 (台阶第一层)
                case 3 -> this.addNum(stand, 1, -2); // 前方更低处 (台阶第二层)
                case 4 -> this.addNum(stand, 0, 0);  // 身体空气
                case 5 -> this.addNum(stand, 0, 1);  // 头部空气
                case 6 -> this.addNum(stand, 1, 0);  // 前方身体空气
                case 7 -> this.addNum(stand, 1, 1);  // 前方头部空气
                case 8 -> this.addNum(stand, 1, -1); // 前方脚下空气 (清理干扰)
                default -> stand;
            };
        } else if (this.height == Height.UP) {
            return switch (i) {
                case 1 -> this.addNum(stand, 0, -1); // 脚下
                case 2 -> this.addNum(stand, 1, 0);  // 前方 (台阶层)
                case 3 -> this.addNum(stand, 0, 0);  // 身体空气
                case 4 -> this.addNum(stand, 0, 1);  // 头部空气
                case 5 -> this.addNum(stand, 1, 1);  // 前方身体空气
                case 6 -> this.addNum(stand, 1, 2);  // 前方头部空气
                case 7 -> this.addNum(stand, 0, 2);  // 头部上方空气 (防止撞头)
                case 8 -> this.addNum(stand, 1, 3);  // 目标上方空气
                default -> stand;
            };
        } else if (this.height == Height.VERTICAL) {
            return switch (i) {
                case 1 -> this.addNum(stand, 0, 0);  // 当前身体位置放置方块 (作为下一层的地板)
                case 2 -> this.addNum(stand, 0, 1);  // 头部位置变空气
                case 3 -> this.addNum(stand, 0, 2);  // 头部上方变空气
                case 4 -> this.addNum(stand, 0, 3);  // 头部上方第2格变空气
                default -> stand;
            };
        } else {
            return stand;
        }
    }

    public BlockKind getNextBlockKind(int i) {
        if (this.height == Height.NONE) {
            return switch (i) {
                case 1, 2 -> BlockKind.BLOCK; // 地板必须是方块
                case 3, 4, 5, 6 -> BlockKind.AIR; // 身体空间必须是空气
                default -> throw new IllegalArgumentException("Index " + i + " is out of bounds for NONE");
            };
        } else if (this.height == Height.DOWN) {
            return switch (i) {
                case 1, 2, 3 -> BlockKind.BLOCK;
                case 4, 5, 6, 7, 8 -> BlockKind.AIR;
                default -> throw new IllegalArgumentException("Index " + i + " is out of bounds for DOWN");
            };
        } else if (this.height == Height.UP) {
            return switch (i) {
                case 1, 2 -> BlockKind.BLOCK;
                case 3, 4, 5, 6, 7, 8 -> BlockKind.AIR;
                default -> throw new IllegalArgumentException("Index " + i + " is out of bounds for UP");
            };
        } else if (this.height == Height.VERTICAL) {
            return switch (i) {
                case 1 -> BlockKind.BLOCK; // 在当前位置放置地板
                case 2, 3, 4 -> BlockKind.AIR; // 上方空间全部清理
                default -> throw new IllegalArgumentException("Index " + i + " is out of bounds for VERTICAL");
            };
        } else {
            return null;
        }
    }

    public BlockFace getDirection() {
        return direction;
    }

    public Height getHeight() {
        return height;
    }

    public int getTotalBlockNum() {
        return this.height.limit;
    }
}
