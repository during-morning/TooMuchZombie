package com.frigidora.toomuchzombies.ai.behavior;

import org.bukkit.block.BlockFace;

import com.frigidora.toomuchzombies.ai.behavior.structs.Height;

public class BuilderPathPlanner {
    private BlockFace lastDirection = null;
    private int sameDirectionTicks = 0;
    private int lateralBiasTicks = 0;

    public static final class PlanStep {
        private final BlockFace direction;
        private final Height height;

        public PlanStep(BlockFace direction, Height height) {
            this.direction = direction;
            this.height = height;
        }

        public BlockFace direction() {
            return direction;
        }

        public Height height() {
            return height;
        }
    }

    public PlanStep next(int selfX, int selfY, int selfZ, int targetX, int targetY, int targetZ) {
        int dx = targetX - selfX;
        int dy = targetY - selfY;
        int dz = targetZ - selfZ;

        BlockFace primaryDir = Math.abs(dx) > Math.abs(dz)
            ? (dx > 0 ? BlockFace.EAST : BlockFace.WEST)
            : (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
        BlockFace secondaryDir = Math.abs(dx) > Math.abs(dz)
            ? (dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH)
            : (dx > 0 ? BlockFace.EAST : BlockFace.WEST);
        BlockFace dir = primaryDir;

        if (lastDirection == primaryDir) {
            sameDirectionTicks++;
        } else {
            sameDirectionTicks = 0;
        }

        // 长时间同向推进时，允许短暂偏航，减少被单一轴障碍持续阻塞的概率
        if (Math.abs(dx) > 2 && Math.abs(dz) > 2 && sameDirectionTicks > 8) {
            dir = secondaryDir;
            lateralBiasTicks = 3;
            sameDirectionTicks = 0;
        } else if (lateralBiasTicks > 0) {
            dir = secondaryDir;
            lateralBiasTicks--;
        }
        lastDirection = dir;

        Height height;
        if (dy > 1) {
            height = (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) ? Height.VERTICAL : Height.UP;
        } else if (dy < -1) {
            height = Height.DOWN;
        } else {
            height = Height.NONE;
        }

        return new PlanStep(dir, height);
    }

    public void stop() {
        lastDirection = null;
        sameDirectionTicks = 0;
        lateralBiasTicks = 0;
    }
}
