package com.frigidora.toomuchzombies.ai.pathfinding;

import org.bukkit.Location;
import org.bukkit.block.Block;

/**
 * 方块数据包 - 从 ZombieGame 移植
 * 包含方块类型和位置信息
 */
public class BlockPacket {
    public static final BlockPacket EMPTY = new BlockPacket(BlockKind.AIR, null).setEmpty();
    
    public final BlockKind blockKind;
    public final Location location;
    private boolean isEmpty;
    
    public BlockPacket(BlockKind blockKind, Location location) {
        this.blockKind = blockKind;
        this.location = location;
        this.isEmpty = false;
    }
    
    private BlockPacket setEmpty() {
        this.isEmpty = true;
        return this;
    }
    
    public boolean isEmpty() {
        return isEmpty;
    }
    
    public Block getBlock() {
        return location != null ? location.getBlock() : null;
    }
}
