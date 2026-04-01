package com.frigidora.toomuchzombies.ai.pathfinding;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * 路径查找器 - 从 ZombieGame 完整移植
 * 
 * 这是 ZombieGame 的核心算法，负责：
 * 1. 根据当前位置和目标位置计算需要建造的结构
 * 2. 管理建造进度
 * 3. 动态调整路径（根据高度变化选择上坡/下坡/平地结构）
 * 
 * 算法特点：
 * - 自动识别方向（东南西北）
 * - 自动识别高度变化（上坡/下坡/平地）
 * - 分步执行（每个结构6-8个方块）
 * - 支持中断和恢复
 */
public class PathFinder {
    
    private final BiConsumer<Location, Location> onSelfPosChanged;
    private final Predicate<HashMap<Integer, BlockPacket>> checkIfStructureDone;
    
    private boolean firstStructure = true;
    private Structure currentStructure = Structure.NORTH;
    private int progress = -1;
    private Location selfPos;
    private Location targetPos;
    private boolean isDone = true;
    
    /**
     * 构造函数
     * @param onSelfPosChanged 当僵尸位置需要更新时的回调
     * @param checkIfStructureDone 检查当前结构是否完成的回调
     */
    public PathFinder(BiConsumer<Location, Location> onSelfPosChanged,
                      Predicate<HashMap<Integer, BlockPacket>> checkIfStructureDone) {
        this.onSelfPosChanged = onSelfPosChanged;
        this.checkIfStructureDone = checkIfStructureDone;
    }

    
    /**
     * 获取结构的结束位置
     */
    private static Location getEndLocation(Location pos, Structure structure) {
        return structure.getHeight() == Height.NONE 
            ? structure.getBlockLocation(6, pos) 
            : structure.getBlockLocation(8, pos);
    }
    
    /**
     * 获取当前需要处理的方块
     */
    public BlockPacket getBlock() {
        if (isDone) {
            return BlockPacket.EMPTY;
        }
        return new BlockPacket(
            currentStructure.getBlockKind(progress),
            currentStructure.getBlockLocation(progress, selfPos)
        );
    }
    
    /**
     * 开始路径规划
     */
    public void start(Location selfPos, Location targetPos) {
        if (this.isDone) {
            this.selfPos = selfPos.clone();
            this.targetPos = targetPos.clone();
            this.isDone = false;
            this.firstStructure = true;
            this.newStructure();
            this.firstStructure = false;
        }
    }
    
    /**
     * 停止路径规划
     */
    public void stop() {
        if (!this.isDone) {
            this.isDone = true;
            this.progress = -1;
        }
    }
    
    public boolean isDone() {
        return isDone;
    }

    
    /**
     * 前进到下一个方块
     * 这是核心方法，控制建造进度
     */
    public void next() {
        if (isDone) {
            return;
        }
        
        if (progress < currentStructure.getTotalBlockNum()) {
            // 继续当前结构
            progress++;
        } else {
            // 当前结构完成，检查是否真的完成了
            HashMap<Integer, BlockPacket> blocks = new HashMap<>();
            for (int i = 1; i <= currentStructure.getTotalBlockNum(); i++) {
                blocks.put(i, new BlockPacket(
                    currentStructure.getBlockKind(i),
                    currentStructure.getBlockLocation(i, selfPos)
                ));
            }
            
            if (checkIfStructureDone.test(blocks)) {
                // 结构完成，创建新结构
                newStructure();
            } else {
                // 结构未完成，重新开始
                progress = 1;
            }
        }
    }

    
    /**
     * 创建新结构 - ZombieGame 的核心算法
     * 根据当前位置和目标位置自动选择合适的结构
     */
    private void newStructure() {
        if (isDone) {
            return;
        }
        
        progress = 1;
        
        // 如果不是第一个结构，更新僵尸位置
        if (!firstStructure) {
            Location oldPos = selfPos.clone();
            selfPos = getEndLocation(selfPos, currentStructure);
            onSelfPosChanged.accept(oldPos, selfPos);
        }
        
        // 计算相对位置
        int dx = selfPos.getBlockX() - targetPos.getBlockX();
        int dy = selfPos.getBlockY() - targetPos.getBlockY();
        int dz = selfPos.getBlockZ() - targetPos.getBlockZ();
        
        Direction direction;
        Height height;
        
        // 判断方向 - 使用 ZombieGame 的算法
        if (-1 <= dx && dx <= 1 && -1 <= dz && dz <= 1 && !(-1 <= dy && dy <= 1)) {
            // 垂直情况的特殊处理
            if ((dx == 0 && dz == -1) || (dx == -1 && dz == -1)) {
                direction = Direction.EAST;
            } else if (dx == -1) {
                direction = Direction.NORTH;
            } else if ((dx == 1 && dz == 1) || (dx == 0 && dz == 1)) {
                direction = Direction.WEST;
            } else if (dx == 1) {
                direction = Direction.SOUTH;
            } else {
                direction = Direction.randomHorizontal();
            }
        } else if (dx == 0 && -1 <= dy && dy <= 1 && dz == 0) {
            // 已经到达目标
            isDone = true;
            return;
        } else {
            // 正常情况：根据相对位置判断方向
            direction = Direction.fromLocations(selfPos, targetPos);
        }
        
        // 判断高度
        if (dy > 0) {
            height = Height.DOWN;  // 目标在下方
        } else if (dy < 0) {
            height = Height.UP;    // 目标在上方
        } else {
            height = Height.NONE;  // 同一高度
        }
        
        currentStructure = Structure.create(direction, height);
    }
    
    public Location getSelfPos() {
        return selfPos != null ? selfPos.clone() : null;
    }
    
    public Location getTargetPos() {
        return targetPos != null ? targetPos.clone() : null;
    }
    
    public Structure getCurrentStructure() {
        return currentStructure;
    }
    
    public int getProgress() {
        return progress;
    }
}
