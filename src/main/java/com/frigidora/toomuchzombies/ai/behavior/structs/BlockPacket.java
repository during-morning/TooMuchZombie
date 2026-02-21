package com.frigidora.toomuchzombies.ai.behavior.structs;

import org.bukkit.block.Block;

public class BlockPacket {
    public static final BlockPacket EMPTY = new BlockPacket(null, null);
    public final BlockKind blockKind;
    public final Block block;

    public BlockPacket(BlockKind blockKind, Block block) {
        this.blockKind = blockKind;
        this.block = block;
    }

    public boolean isEmpty() {
        return this.block == null;
    }
}
