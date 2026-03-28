package com.frigidora.toomuchzombies.ai.behavior;

import org.bukkit.block.BlockFace;

import com.frigidora.toomuchzombies.ai.behavior.structs.Height;

public class BuilderPathPlanner {
    private BlockFace lastDirection = null;
    private int sameDirectionTicks = 0;
    private int lateralBiasTicks = 0;
    private int stagnantTicks = 0;
    private int lastManhattanDistance = Integer.MAX_VALUE;
    private BlockFace committedDirection = null;
    private int commitTicks = 0;
    private int lastSelfX = Integer.MIN_VALUE;
    private int lastSelfZ = Integer.MIN_VALUE;
    private int secondLastSelfX = Integer.MIN_VALUE;
    private int secondLastSelfZ = Integer.MIN_VALUE;
    private int oscillationTicks = 0;

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
        int manhattanDistance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        if (manhattanDistance + 1 < lastManhattanDistance) {
            stagnantTicks = 0;
        } else {
            stagnantTicks++;
        }
        lastManhattanDistance = manhattanDistance;

        boolean bouncedBack = selfX == secondLastSelfX && selfZ == secondLastSelfZ
            && lastSelfX != Integer.MIN_VALUE && lastSelfZ != Integer.MIN_VALUE;
        if (bouncedBack) {
            oscillationTicks++;
        } else {
            oscillationTicks = 0;
        }

        BlockFace dir;
        if (commitTicks > 0 && committedDirection != null) {
            dir = committedDirection;
            commitTicks--;
        } else {
            dir = primaryDir;
        }

        if (lastDirection == primaryDir) {
            sameDirectionTicks++;
        } else {
            sameDirectionTicks = 0;
        }

        // 避免在开放区域立即反向，优先沿次轴绕一下。
        if (isOpposite(dir, lastDirection) && Math.abs(dx) + Math.abs(dz) > 2) {
            dir = secondaryDir;
        }

        // 对齐 ZombieGame 的“连续推进”思路：短时承诺同一绕行方向，减少横跳。
        if (stagnantTicks >= 3 && Math.abs(dx) > 1 && Math.abs(dz) > 1) {
            dir = secondaryDir;
            committedDirection = secondaryDir;
            commitTicks = 2;
            stagnantTicks = 0;
            sameDirectionTicks = 0;
            lateralBiasTicks = 1;
        }

        // ABAB 往返摆动：扩大同向承诺，避免在障碍边缘反复回头。
        if (oscillationTicks >= 1 && Math.abs(dx) > 1 && Math.abs(dz) > 1) {
            dir = secondaryDir;
            committedDirection = secondaryDir;
            commitTicks = Math.max(commitTicks, 3);
            lateralBiasTicks = Math.max(lateralBiasTicks, 2);
            stagnantTicks = 0;
            sameDirectionTicks = 0;
        }

        // 长时间同向推进时，允许短暂偏航，减少被单一轴障碍持续阻塞的概率
        if (Math.abs(dx) > 2 && Math.abs(dz) > 2 && sameDirectionTicks > 8) {
            dir = secondaryDir;
            lateralBiasTicks = 2;
            committedDirection = secondaryDir;
            commitTicks = 1;
            sameDirectionTicks = 0;
        } else if (lateralBiasTicks > 0) {
            dir = secondaryDir;
            lateralBiasTicks--;
        }
        lastDirection = dir;

        secondLastSelfX = lastSelfX;
        secondLastSelfZ = lastSelfZ;
        lastSelfX = selfX;
        lastSelfZ = selfZ;

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
        stagnantTicks = 0;
        lastManhattanDistance = Integer.MAX_VALUE;
        committedDirection = null;
        commitTicks = 0;
        lastSelfX = Integer.MIN_VALUE;
        lastSelfZ = Integer.MIN_VALUE;
        secondLastSelfX = Integer.MIN_VALUE;
        secondLastSelfZ = Integer.MIN_VALUE;
        oscillationTicks = 0;
    }

    private boolean isOpposite(BlockFace a, BlockFace b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getModX() == -b.getModX() && a.getModZ() == -b.getModZ();
    }
}
