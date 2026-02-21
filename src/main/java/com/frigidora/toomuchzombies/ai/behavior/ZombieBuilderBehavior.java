package com.frigidora.toomuchzombies.ai.behavior;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;
import com.frigidora.toomuchzombies.ai.behavior.structs.BlockKind;
import com.frigidora.toomuchzombies.ai.behavior.structs.Height;
import com.frigidora.toomuchzombies.ai.behavior.structs.Structure;

public class ZombieBuilderBehavior {

    private final ZombieAgent agent;
    private final Zombie zombie;
    private final ZombieBreakerBehavior breaker;
    
    private boolean active = false;
    private Structure currentStructure;
    private int structureProgress = 1;
    private BlockPos selfPos; // The "center" of the current structure step
    private final BuilderPathPlanner planner = new BuilderPathPlanner();
    
    private long lastPlaceTime;
    
    // Optimization: Cache last move target to avoid excessive pathfinding
    private Location lastMoveTarget = null;
    private long lastMoveTime = 0;

    public ZombieBuilderBehavior(ZombieAgent agent, ZombieBreakerBehavior breaker) {
        this.agent = agent;
        this.zombie = agent.getZombie();
        this.breaker = breaker;
    }

    public void setActive(boolean active) {
        if (this.active != active) {
            this.active = active;
            if (active) {
                // Initialize
                Location target = agent.getLastKnownTargetLocation();
                if (target != null) {
                    Location sharedPos = ZombieAIManager.getInstance().getActiveBuildPathPos(target);
                    if (sharedPos != null && sharedPos.getWorld().equals(zombie.getWorld())) {
                        // 如果有共享路径，且距离不太远（30格内），则加入该路径
                        if (sharedPos.distanceSquared(zombie.getLocation()) < 900) {
                            this.selfPos = new BlockPos(sharedPos.getBlockX(), sharedPos.getBlockY(), sharedPos.getBlockZ());
                        } else {
                            this.selfPos = new BlockPos(zombie.getLocation().getBlockX(), zombie.getLocation().getBlockY(), zombie.getLocation().getBlockZ());
                        }
                    } else {
                        this.selfPos = new BlockPos(zombie.getLocation().getBlockX(), zombie.getLocation().getBlockY(), zombie.getLocation().getBlockZ());
                    }
                } else {
                    this.selfPos = new BlockPos(zombie.getLocation().getBlockX(), zombie.getLocation().getBlockY(), zombie.getLocation().getBlockZ());
                }
                
                this.currentStructure = null;
                this.zombie.getPathfinder().stopPathfinding();
            } else {
                breaker.stopBreaking();
                planner.stop();
            }
        }
    }

    public boolean isActive() {
        return active;
    }

    private int stuckTicks = 0;

    private enum PlacementResult {
        SUCCESS, TOO_FAR, NO_SUPPORT, COOLDOWN, INVALID_BLOCK
    }

    public void tick() {
        if (!active) return;
        
        // 抗击退属性：工程僵尸不可被击退
        // 这通常需要监听 EntityDamageEvent 或者设置属性。
        // 由于这里是 AI tick，我们只能尝试通过属性来设置，或者在生成时设置。
        // 为了确保生效，我们可以在这里持续检查并设置属性。
        if (zombie.getAttribute(org.bukkit.attribute.Attribute.GENERIC_KNOCKBACK_RESISTANCE) != null) {
            if (zombie.getAttribute(org.bukkit.attribute.Attribute.GENERIC_KNOCKBACK_RESISTANCE).getValue() < 1.0) {
                zombie.getAttribute(org.bukkit.attribute.Attribute.GENERIC_KNOCKBACK_RESISTANCE).setBaseValue(1.0);
            }
        }
        
        // TNT 僵尸不许尝试搭建，直接关闭建筑模式
        if (agent.getRole() == com.frigidora.toomuchzombies.enums.ZombieRole.SUICIDE) {
            setActive(false);
            return;
        }

        Location targetLoc = agent.getLastKnownTargetLocation();
        if (targetLoc == null) {
            setActive(false);
            return;
        }

        // 0. 物理位置校验：如果距离当前中心 selfPos 太远，先移动过去
        Location centerLoc = new Location(zombie.getWorld(), selfPos.x + 0.5, selfPos.y, selfPos.z + 0.5);
        double distSq = zombie.getLocation().distanceSquared(centerLoc);
        
        // 如果距离目标位置太远，或者根本够不着下一个方块，先移动
        if (distSq > 1.44) { 
            safeMoveTo(centerLoc, 1.2);
            stuckTicks++;
            if (stuckTicks > 60) {
                handleBuildFailure(zombie.getWorld().getBlockAt(selfPos.x, selfPos.y, selfPos.z));
                stuckTicks = 0;
            }
            return;
        }

        // If breaker is busy, wait
        if (breaker.isBreaking()) {
            breaker.tick();
            return;
        }

        // 1. Initialize or Update Structure
        if (currentStructure == null || structureProgress > currentStructure.getTotalBlockNum()) {
            updateStructure(targetLoc);
            
            // 如果 updateStructure 发现不需要建造 (currentStructure 为 null)，则直接返回
            if (currentStructure == null) {
                // 不需要建造，可能已经移动了位置，直接返回等待下一 tick
                return;
            }
            
            structureProgress = 1;
            stuckTicks = 0;
        }

        // 2. Check current progress block
        Block currentBlock = currentStructure.getBlock(structureProgress, zombie.getWorld().getBlockAt(selfPos.x, selfPos.y, selfPos.z));
        BlockKind requiredKind = currentStructure.getNextBlockKind(structureProgress);

        if (isBlockDone(currentBlock, requiredKind)) {
            structureProgress++;
            stuckTicks = 0;
            
            if (requiredKind == BlockKind.BLOCK) {
                safeMoveTo(currentBlock.getLocation().add(0.5, 1, 0.5), 1.0);
            }

            if (structureProgress > currentStructure.getTotalBlockNum()) {
                 moveZombieToNextStep();
            }
        } else {
            if (zombie.getPathfinder().hasPath() && requiredKind == BlockKind.BLOCK) {
                // 如果正在尝试放置方块，且距离稍远，保持移动而不是完全静止
                if (zombie.getLocation().distanceSquared(currentBlock.getLocation()) > 4) {
                    safeMoveTo(currentBlock.getLocation(), 1.0);
                } else {
                    zombie.getPathfinder().stopPathfinding();
                }
            } else if (requiredKind == BlockKind.AIR) {
                zombie.getPathfinder().stopPathfinding();
            }

            if (requiredKind == BlockKind.AIR) {
                breaker.startBreaking(currentBlock);
            } else {
                int oldProgress = structureProgress;
                PlacementResult result = placeBlock(currentBlock);
                
                if (result == PlacementResult.SUCCESS) {
                    stuckTicks = 0;
                } else if (result == PlacementResult.TOO_FAR) {
                    // 核心修复：如果够不着，计算一个能构着的位置并前往
                    Location reachLoc = currentBlock.getLocation().add(0.5, -1, 0.5); // 尝试站在方块下方/侧面
                    safeMoveTo(reachLoc, 1.2);
                    stuckTicks++;
                } else if (result == PlacementResult.NO_SUPPORT) {
                    // 如果没有支撑，尝试寻找支撑点（通常是 selfPos 的脚下）
                    stuckTicks++;
                } else {
                    stuckTicks++;
                }

                if (stuckTicks > com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getBuilderMaxBuildFailTicks()) {
                    handleBuildFailure(currentBlock);
                    stuckTicks = 0;
                }
            }
        }
    }

    private void safeMoveTo(Location target, double speed) {
        if (target == null) return;
        
        // 性能优化：避免每一 tick 都调用 moveTo
        // 只有当目标位置发生显著变化，或者距离上次调用超过 1 秒，或者当前没有路径时，才调用 NMS 寻路
        if (lastMoveTarget != null && lastMoveTarget.getWorld().equals(target.getWorld())) {
            double distSq = target.distanceSquared(lastMoveTarget);
            long timeDiff = System.currentTimeMillis() - lastMoveTime;
            
            if (distSq < 0.25 && timeDiff < 1000 && zombie.getPathfinder().hasPath()) {
                return;
            }
        }
        
        agent.moveTo(target, speed);
        lastMoveTarget = target.clone();
        lastMoveTime = System.currentTimeMillis();
    }

    private void handleBuildFailure(Block targetBlock) {
        // 意识到无法搭建，自动尝试“跳跃”或“瞬移”上去
        Location loc = targetBlock.getLocation().add(0.5, 0, 0.5);
        for (int i = 0; i < 4; i++) {
            Block b = loc.clone().add(0, i, 0).getBlock();
            if (!b.getType().isSolid() && !b.getRelative(BlockFace.UP).getType().isSolid() && b.getRelative(BlockFace.DOWN).getType().isSolid()) {
                safeMoveTo(b.getLocation(), 1.5);
                selfPos = new BlockPos(b.getX(), b.getY(), b.getZ());
                structureProgress = currentStructure != null ? currentStructure.getTotalBlockNum() + 1 : 1;
                return;
            }
        }
        setActive(false);
    }

    private void updateStructure(Location target) {
        // Calculate direction to target
        int dx = target.getBlockX() - selfPos.x;
        int dy = target.getBlockY() - selfPos.y;
        int dz = target.getBlockZ() - selfPos.z;

        BuilderPathPlanner.PlanStep plan = planner.next(selfPos.x, selfPos.y, selfPos.z, target.getBlockX(), target.getBlockY(), target.getBlockZ());
        BlockFace dir = plan.direction();

        // --- 地形探测逻辑 ---
        Block standBlock = zombie.getWorld().getBlockAt(selfPos.x, selfPos.y, selfPos.z);
        
        // 0. 修正 selfPos 的 Y 轴对齐
        // 很多时候僵尸在跳跃或半空，selfPos 可能高于实际地面
        // 如果脚下是空气，但再往下一格是地面，我们应该认为僵尸是在地面上，避免在半空造桥
        if (!standBlock.getRelative(BlockFace.DOWN).getType().isSolid()) {
             Block below2 = standBlock.getRelative(BlockFace.DOWN).getRelative(BlockFace.DOWN);
             if (below2.getType().isSolid()) {
                 // 修正自我位置到地面
                 selfPos.y -= 1;
                 standBlock = standBlock.getRelative(BlockFace.DOWN); // 更新参照方块
                 dy += 1; // 目标相对高度增加
             }
        }
        
        // 目标高度修正：如果目标高度与我们差不多，但我们需要“高空压制”，则虚拟抬高目标
        // 用户反馈：不要在地面搭桥。这意味着我们应该优先保持在上方，或者向上搭建。
        // 如果我们不是远高于目标（比如 < 5 格），且水平距离较远，我们应该倾向于向上搭建，而不是水平。
        int desiredY = target.getBlockY() + 8; // 理想高度：目标上方 8 格
        int dyToDesired = desiredY - selfPos.y; // > 0 意味着我们需要上升
        
        Block frontBlock = standBlock.getRelative(dir);
        Block frontDownBlock = frontBlock.getRelative(BlockFace.DOWN);
        Block frontUpBlock = frontBlock.getRelative(BlockFace.UP);

        Height height = plan.height();

        // 1. 探测障碍物 (Wall Detection)
        if (frontBlock.getType().isSolid() || frontUpBlock.getType().isSolid()) {
            // 前方有墙，且目标在上方或水平，则向上搭建
            // 只要我们没有远高于目标，遇到障碍物就向上
            if (dy > -5) { 
                height = Height.UP;
            } else {
                // 目标在下方，但前方有墙，可能需要先挖开或绕路，这里简单处理为 NONE (由 Breaker 挖开)
                height = Height.NONE;
            }
        } 
        // 2. 探测坑洞 (Gap Detection)
        else if (!frontDownBlock.getType().isSolid()) {
            // 前方脚下是空的
            // ... (侧面探测逻辑保持不变)
            BlockFace left = getLeftFace(dir);
            BlockFace right = getRightFace(dir);
            Block leftBlock = standBlock.getRelative(left);
            Block leftDown = leftBlock.getRelative(BlockFace.DOWN);
            Block rightBlock = standBlock.getRelative(right);
            Block rightDown = rightBlock.getRelative(BlockFace.DOWN);
            
            boolean foundPath = false;
            if (!leftBlock.getType().isSolid() && leftDown.getType().isSolid()) { dir = left; foundPath = true; }
            else if (!rightBlock.getType().isSolid() && rightDown.getType().isSolid()) { dir = right; foundPath = true; }
            
            if (foundPath) {
                // ... (行走逻辑保持不变)
                selfPos.x += dir.getModX();
                selfPos.z += dir.getModZ();
                Location nextLoc = new Location(zombie.getWorld(), selfPos.x + 0.5, selfPos.y, selfPos.z + 0.5);
                nextLoc.setYaw(getFaceYaw(dir));
                safeMoveTo(nextLoc, 1.2);
                if (target != null) ZombieAIManager.getInstance().registerBuildPath(target, nextLoc);
                this.currentStructure = null;
                return;
            }
            
            // 坑洞搭建逻辑：
            if (dyToDesired > 0) {
                // 如果我们低于理想高度（目标+8），优先向上搭建，而不是水平搭建
                // 这能让僵尸在跨越坑洞时顺便提升高度
                height = Height.UP;
            } else {
                // 我们已经足够高了
                // 如果目标在下方
                if (Math.abs(dx) > 8 || Math.abs(dz) > 8) {
                    height = Height.NONE; // 保持高度天桥
                } else {
                    height = Height.DOWN; // 接近目标，下降
                }
            }
        }
        // 3. 正常地形 (前方通畅，脚下有地)
        else {
            // 检查是否需要强制爬升以获取高度优势
            // 如果我们低于理想高度，且水平距离还远，不要走路，而是开始搭天梯
            if (dyToDesired > 0 && (Math.abs(dx) > 10 || Math.abs(dz) > 10)) {
                 // 强制爬升
                 height = Height.UP;
            } else {
                // 原有逻辑
                if (dy > 1) {
                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) height = Height.VERTICAL;
                    else height = Height.UP;
                } else if (dy < -1) {
                    height = Height.DOWN;
                } else {
                    // 平地行走
                    selfPos.x += dir.getModX();
                    selfPos.z += dir.getModZ();
                    Location nextLoc = new Location(zombie.getWorld(), selfPos.x + 0.5, selfPos.y, selfPos.z + 0.5);
                    nextLoc.setYaw(getFaceYaw(dir));
                    safeMoveTo(nextLoc, 1.2);
                    if (target != null) ZombieAIManager.getInstance().registerBuildPath(target, nextLoc);
                    this.currentStructure = null;
                    return;
                }
            }
        }

        // If current structure was valid, we calculate the NEXT one based on where we WILL be
        // But here we just calculate based on current selfPos
        this.currentStructure = Structure.change(dir, height);
    }
    
    private void moveZombieToNextStep() {
        // Calculate new selfPos based on direction and height
        // Structure logic implies we move 1 block forward
        BlockFace dir = currentStructure.getDirection();
        Height h = currentStructure.getHeight();
        
        int dx = dir.getModX();
        int dz = dir.getModZ();
        int dy = 0;
        if (h == Height.UP) dy = 1;
        else if (h == Height.DOWN) dy = -1;
        else if (h == Height.VERTICAL) {
            dy = 1;
            dx = 0; // 垂直移动不改变水平位置
            dz = 0;
        }
        
        selfPos = new BlockPos(selfPos.x + dx, selfPos.y + dy, selfPos.z + dz);
        
        // 注册到全局，以便其他僵尸协同
        Location nextLoc = new Location(zombie.getWorld(), selfPos.x + 0.5, selfPos.y, selfPos.z + 0.5);
        Location target = agent.getLastKnownTargetLocation();
        if (target != null) {
            ZombieAIManager.getInstance().registerBuildPath(target, nextLoc);
        }

        // Teleport/Move zombie
        nextLoc.setYaw(getFaceYaw(dir));
        
        safeMoveTo(nextLoc, 1.2);
    }
    
    private float getFaceYaw(BlockFace face) {
        switch (face) {
            case NORTH: return 180;
            case SOUTH: return 0;
            case EAST: return -90;
            case WEST: return 90;
            default: return 0;
        }
    }

    private BlockFace getLeftFace(BlockFace face) {
        switch (face) {
            case NORTH: return BlockFace.WEST;
            case SOUTH: return BlockFace.EAST;
            case EAST: return BlockFace.NORTH;
            case WEST: return BlockFace.SOUTH;
            default: return BlockFace.WEST;
        }
    }

    private BlockFace getRightFace(BlockFace face) {
        switch (face) {
            case NORTH: return BlockFace.EAST;
            case SOUTH: return BlockFace.WEST;
            case EAST: return BlockFace.SOUTH;
            case WEST: return BlockFace.NORTH;
            default: return BlockFace.EAST;
        }
    }

    private boolean isBlockDone(Block block, BlockKind required) {
        if (required == BlockKind.AIR) {
            return block.getType() == Material.AIR || block.getType() == Material.WATER || !block.getType().isSolid();
        } else {
            return block.getType().isSolid();
        }
    }

    private boolean hasSupport(Block block) {
        // 1. 检查六个相邻面是否有固体方块
        for (BlockFace face : new BlockFace[]{BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
            if (block.getRelative(face).getType().isSolid()) {
                return true;
            }
        }
        
        // 2. 特殊逻辑：如果是垂直搭建 (VERTICAL)，且我们正站在目标位置上方或下方
        // 虽然上面的 BlockFace.DOWN 已经覆盖了大部分情况，但这里显式加强
        return false;
    }

    private PlacementResult placeBlock(Block block) {
        // 0. 检查方块位置是否已经被预留，防止多只僵尸在同一位置重复搭建
        if (ZombieAIManager.getInstance().isBuildSpotReserved(block.getLocation())) {
            return PlacementResult.COOLDOWN; // 视为冷却中，稍后重试或跳过
        }

        // 1. 距离检查：不能超过 2.5 格 (距离平方 < 6.25)
        if (zombie.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > 6.25) {
            return PlacementResult.TOO_FAR;
        }

        // 2. 支撑检查
        // 优化：如果没有支撑，尝试搭脚手架
        if (!hasSupport(block)) {
            // 尝试在下方放置方块作为支撑
            Block support = block.getRelative(BlockFace.DOWN);
            if (support.getType() == Material.AIR) {
                // 如果下方也是空气，递归向下太复杂，这里只做简单的一层脚手架尝试
                // 但为了不陷入死循环，我们需要标记当前是否正在处理脚手架
                // 这里简单返回 NO_SUPPORT，让 tick 逻辑中的 handleBuildFailure 处理
                return PlacementResult.NO_SUPPORT;
            } else if (!support.getType().isSolid()) {
                 // 下方是液体或草，视为无支撑
                 return PlacementResult.NO_SUPPORT;
            }
            // 如果下方是固体但 hasSupport 返回 false (可能是因为垂直逻辑)，则强制视为有支撑
            // 这里不做修改，信任 hasSupport 的判断
            return PlacementResult.NO_SUPPORT;
        }

        // 3. 冷却检查
        long cooldown = com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getBuilderPlaceCooldownMs();
        int level = agent.getLevel();
        if (level >= 12) cooldown = com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getBuilderPlaceCooldownMsLv12();
        else if (level >= 9) cooldown = com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getBuilderPlaceCooldownMsLv9();

        // 协同加速：优化为使用 SpatialPartition 查询，避免 Bukkit 实体搜索
        // 查找半径 5 格内的同伴
        int nearbyZombies = ZombieAIManager.getInstance().getNearbyAgents(zombie.getLocation(), 5).size();
        double speedFactor = 1.0 - Math.min(0.5, (nearbyZombies * 0.1));
        
        // 白天减速：速度减小 75% (冷却时间变为原来的 1 / 0.25 = 4 倍)
        long time = zombie.getWorld().getTime();
        if (time >= 0 && time < 12000) {
            speedFactor *= 4.0;
        }
        
        cooldown = (long) (cooldown * speedFactor);

        if (System.currentTimeMillis() - lastPlaceTime < cooldown) return PlacementResult.COOLDOWN;
        
        // 4. 执行放置
        Material mat = Material.COBBLESTONE;
        ItemStack hand = zombie.getEquipment().getItemInMainHand();
        
        // 强制使用建筑材料：如果手中不是方块，尝试使用副手或者默认圆石
        if (hand.getType().isBlock() && !hand.getType().isAir()) {
            mat = hand.getType();
        } else {
            ItemStack offHand = zombie.getEquipment().getItemInOffHand();
            if (offHand.getType().isBlock() && !offHand.getType().isAir()) {
                mat = offHand.getType();
            } else {
                // 如果两只手都没有方块，强制给予圆石并拿在主手
                mat = Material.COBBLESTONE;
                zombie.getEquipment().setItemInMainHand(new ItemStack(Material.COBBLESTONE));
            }
        }

        // 禁止使用 TNT 搭建路径，如果手中是 TNT，强制使用圆石
        if (mat == Material.TNT) {
            mat = Material.COBBLESTONE;
            zombie.getEquipment().setItemInMainHand(new ItemStack(Material.COBBLESTONE));
        }
        
        block.setType(mat);
        // 预留该位置，防止其他僵尸重复操作
        ZombieAIManager.getInstance().reserveBuildSpot(block.getLocation());

        playPlaceEffects(block, mat);
        zombie.swingOffHand();

        com.frigidora.toomuchzombies.mechanics.TemporaryBlockManager.getInstance().registerBlock(
            block.getLocation(),
            com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getBuilderTemporaryBlockDurationMs()
        );
        lastPlaceTime = System.currentTimeMillis();
        
        return PlacementResult.SUCCESS;
    }

    private void playPlaceEffects(Block block, Material mat) {
        int particleCount = com.frigidora.toomuchzombies.config.ConfigManager.getInstance().getBuilderPlaceParticleCount();
        if (particleCount > 0) {
            zombie.getWorld().spawnParticle(
                org.bukkit.Particle.BLOCK,
                block.getLocation().add(0.5, 0.5, 0.5),
                particleCount,
                0.2, 0.2, 0.2,
                mat.createBlockData()
            );
        }
        zombie.getWorld().playSound(block.getLocation(), mat.createBlockData().getSoundGroup().getPlaceSound(), 1.0f, 0.95f);
        zombie.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_STONE_PLACE, 0.35f, 1.2f);
    }
    
    // Simple BlockPos helper class since we can't use NMS BlockPos easily without import issues
    private static class BlockPos {
        int x, y, z;
        BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }
}
