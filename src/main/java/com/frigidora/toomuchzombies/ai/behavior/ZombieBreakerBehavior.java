package com.frigidora.toomuchzombies.ai.behavior;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;
import com.frigidora.toomuchzombies.config.ConfigManager;

public class ZombieBreakerBehavior {

    private final ZombieAgent agent;
    private final Zombie zombie;
    
    private Block currentTarget;
    private float breakProgress;
    private long lastBreakSoundTime;
    private int lastSentStage = -2;
    private long startBreakTime; // 记录开始时间，用于判定是否需要请求自爆支援
    private java.util.Set<Material> breakerBlacklist = java.util.Collections.emptySet();
    private java.util.Set<Material> breakerWhitelist = java.util.Collections.emptySet();
    private long lastRuleRefreshTime = 0;
    private final java.util.Map<String, Integer> rejectCounters = new java.util.concurrent.ConcurrentHashMap<>();

    public ZombieBreakerBehavior(ZombieAgent agent) {
        this.agent = agent;
        this.zombie = agent.getZombie();
    }

    public void startBreaking(Block block) {
        if (currentTarget != null && currentTarget.equals(block)) return;
        refreshBreakRulesIfNeeded();
        if (!isBreakAllowed(block)) {
            hitReject("policy_blocked");
            return;
        }
        
        // 如果之前在挖别的，先注销
        if (currentTarget != null) {
            ZombieAIManager.getInstance().unregisterMiningOperation(currentTarget.getLocation());
        }

        this.currentTarget = block;
        this.breakProgress = 0;
        this.lastBreakSoundTime = 0;
        this.lastSentStage = -2;
        this.startBreakTime = System.currentTimeMillis();
        
        // 注册挖掘任务
        ZombieAIManager.getInstance().registerMiningOperation(block.getLocation());
        
        // Initial swing
        zombie.swingMainHand();
    }

    public void stopBreaking() {
        if (currentTarget != null) {
            sendBreakPacket(currentTarget, -1); // Reset visual
            ZombieAIManager.getInstance().unregisterMiningOperation(currentTarget.getLocation());
            currentTarget = null;
            breakProgress = 0;
            lastSentStage = -2;
        }
    }

    public boolean isBreaking() {
        return currentTarget != null;
    }

    public boolean canBreak(Block block) {
        if (block == null) return false;
        refreshBreakRulesIfNeeded();
        return isBreakAllowed(block);
    }

    public void tick() {
        if (currentTarget == null) return;
        refreshBreakRulesIfNeeded();

        if (agent.getRole() != com.frigidora.toomuchzombies.enums.ZombieRole.BUILDER &&
            agent.getRole() != com.frigidora.toomuchzombies.enums.ZombieRole.MINER) {
            hitReject("role_forbidden");
            stopBreaking();
            return;
        }
        
        // Validation
        if (currentTarget.getType() == Material.AIR || currentTarget.getType() == Material.BEDROCK) {
            hitReject("invalid_target");
            stopBreaking();
            return;
        }

        if (!isBreakAllowed(currentTarget)) {
            hitReject("policy_blocked");
            stopBreaking();
            return;
        }

        // 信标保护检查：禁止在活跃信标 50 格内破坏方块
        if (com.frigidora.toomuchzombies.mechanics.BeaconManager.getInstance().isNearActiveBeacon(currentTarget.getLocation(), 50.0)) {
            hitReject("beacon_protected");
            stopBreaking();
            return;
        }
        
        // 距离检查：缩小到 2.5 格 (距离平方 6.25)
        if (!zombie.getWorld().equals(currentTarget.getWorld()) || zombie.getLocation().distanceSquared(currentTarget.getLocation().add(0.5, 0.5, 0.5)) > 6.25) { 
            hitReject("out_of_range");
            stopBreaking();
            return;
        }

        // Calculate speed
        float speed = getBreakSpeed(currentTarget);
        
        // 协作加成：检查有多少其他僵尸也在挖这个方块
        int miners = ZombieAIManager.getInstance().getMinersOnBlock(currentTarget.getLocation());
        if (miners > 1) {
            speed *= (1.0f + (miners - 1) * 0.5f); // 每多一个僵尸，速度增加 50%
        }
        
        breakProgress += speed;
        
        // 检查是否卡住太久 (超过 3 秒)，如果是，请求爆破支援
        if (System.currentTimeMillis() - startBreakTime > ConfigManager.getInstance().getBuilderBreachRequestAfterMs()) {
             ZombieAIManager.getInstance().requestBreach(currentTarget.getLocation());
        }

        // Visuals
        long hitEffectIntervalMs = ConfigManager.getInstance().getBreakerHitEffectIntervalMs();
        if (System.currentTimeMillis() - lastBreakSoundTime > hitEffectIntervalMs) {
            int hitParticleCount = ConfigManager.getInstance().getBreakerHitParticleCount();
            zombie.getWorld().playSound(currentTarget.getLocation(), currentTarget.getType().createBlockData().getSoundGroup().getHitSound(), 1.0f, 0.8f);
            if (hitParticleCount > 0) {
                zombie.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, currentTarget.getLocation().add(0.5, 0.5, 0.5), hitParticleCount, 0.2, 0.2, 0.2, currentTarget.getBlockData());
            }
            zombie.swingMainHand();
            lastBreakSoundTime = System.currentTimeMillis();
        }
        
        // Send packet update (0-9)
        int stage = Math.max(0, Math.min(9, (int) (breakProgress * 10) - 1));
        sendBreakPacket(currentTarget, stage);

        // Finish
        if (breakProgress >= 1.0f) {
            breakBlock(currentTarget);
            stopBreaking();
        }
    }

    private float getBreakSpeed(Block block) {
        float hardness = block.getType().getHardness();
        if (hardness < 0) return 0; // Unbreakable
        if (hardness == 0) return 1.0f; // Instabreak

        ItemStack tool = zombie.getEquipment().getItemInMainHand();
        float speed = 1.0f;

        // Tool efficiency (Simplified vanilla logic)
        if (isPreferredTool(block, tool)) {
            if (tool.getType().name().contains("NETHERITE")) speed = 9.0f;
            else if (tool.getType().name().contains("DIAMOND")) speed = 8.0f;
            else if (tool.getType().name().contains("IRON")) speed = 6.0f;
            else if (tool.getType().name().contains("STONE")) speed = 4.0f;
            else if (tool.getType().name().contains("WOODEN") || tool.getType().name().contains("GOLDEN")) speed = 2.0f;
            
            // Efficiency enchantment
            if (tool.getEnchantments().containsKey(Enchantment.EFFICIENCY)) {
                int level = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
                speed += (level * level + 1);
            }
        }

        // Haste/Fatigue
        if (zombie.hasPotionEffect(PotionEffectType.HASTE)) {
            speed *= 1.2f; // Simplified
        }
        if (zombie.hasPotionEffect(PotionEffectType.MINING_FATIGUE)) {
            speed *= 0.3f;
        }

        // Water check
        if (zombie.isInWater() && !hasAquaAffinity(zombie)) {
            speed /= 5.0f;
        }
        
        // Air check
        if (!zombie.isOnGround()) {
            speed /= 5.0f;
        }

        // Damage calculation: (Speed / Hardness) / 30 (if correct tool) or 100 (if not)
        boolean correctTool = isPreferredTool(block, tool); 
        
        float damage = speed / hardness / (correctTool ? 30f : 100f);
        
        // Configurable multiplier from TooMuchZombies config
        double configMultiplier = TooMuchZombies.getInstance().getConfig().getDouble("zombie-ai.break-speed-multiplier", 1.5);
        
        // 等级速度加成
        float levelMultiplier = 1.0f;
        int level = agent.getLevel();
        if (level >= 12) {
            levelMultiplier = 4.5f;
        } else if (level >= 10) {
            levelMultiplier = 4.0f;
        } else if (level >= 8) {
            levelMultiplier = 3.2f;
        } else if (level >= 6) {
            levelMultiplier = 2.4f;
        } else if (level >= 4) {
            levelMultiplier = 1.7f;
        }

        float finalSpeed = damage * (float) configMultiplier * levelMultiplier;
        
        // 白天减速：速度减小 70%
        long time = zombie.getWorld().getTime();
        if (time >= 0 && time < 12000) {
            finalSpeed *= 0.3f;
        }
        
        return finalSpeed;
    }

    private boolean isPreferredTool(Block block, ItemStack tool) {
        // Simplified check. Real check requires NMS or extensive switch case
        String type = block.getType().name();
        String toolType = tool.getType().name();
        
        if (type.contains("STONE") || type.contains("ORE")) return toolType.contains("PICKAXE");
        if (type.contains("DIRT") || type.contains("SAND") || type.contains("GRAVEL")) return toolType.contains("SHOVEL");
        if (type.contains("LOG") || type.contains("WOOD") || type.contains("PLANKS")) return toolType.contains("AXE");
        return false;
    }

    private boolean hasAquaAffinity(Zombie zombie) {
        ItemStack helm = zombie.getEquipment().getHelmet();
        return helm != null && helm.containsEnchantment(Enchantment.AQUA_AFFINITY);
    }

    private void sendBreakPacket(Block block, int stage) {
        // Clamp stage to [0, 9] or -1 to reset
        stage = Math.max(-1, Math.min(9, stage));
        if (stage == lastSentStage) {
            return;
        }
        lastSentStage = stage;

        if (TooMuchZombies.getNMSHandler() != null) {
            TooMuchZombies.getNMSHandler().breakBlockAnimation(zombie.getEntityId(), block.getLocation(), stage);
            return;
        }

        float progress = stage < 0 ? 0 : stage / 9.0f;
        for (Player p : block.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(block.getLocation()) < 64 * 64) {
                p.sendBlockDamage(block.getLocation(), progress);
            }
        }
    }

    private void breakBlock(Block block) {
        // Protection check
        Player fakePlayer = TooMuchZombies.getFakePlayer(block.getWorld());
        if (fakePlayer != null) {
            BlockBreakEvent event = new BlockBreakEvent(block, fakePlayer);
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return;
        }

        org.bukkit.block.data.BlockData blockData = block.getBlockData();
        org.bukkit.Sound breakSound = blockData.getSoundGroup().getBreakSound();
        block.breakNaturally(zombie.getEquipment().getItemInMainHand());
        // Sound
        block.getWorld().playSound(block.getLocation(), breakSound, 1.0f, 1.0f);
        int finishParticleCount = ConfigManager.getInstance().getBreakerFinishParticleCount();
        if (finishParticleCount > 0) {
            block.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), finishParticleCount, 0.35, 0.35, 0.35, blockData);
        }
    }

    private void refreshBreakRulesIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRuleRefreshTime < 3000) {
            return;
        }
        lastRuleRefreshTime = now;
        breakerBlacklist = ConfigManager.getInstance().getBreakerBlacklist();
        breakerWhitelist = ConfigManager.getInstance().getBreakerWhitelist();
    }

    private boolean isBreakAllowed(Block block) {
        Material type = block.getType();
        if (type == Material.BEDROCK || type.name().contains("PORTAL")) {
            return false;
        }
        if (!breakerWhitelist.isEmpty() && !breakerWhitelist.contains(type)) {
            return false;
        }
        if (breakerBlacklist.contains(type)) {
            return false;
        }
        return true;
    }

    public java.util.Map<String, Integer> getRejectCountersSnapshot() {
        return java.util.Collections.unmodifiableMap(new java.util.HashMap<>(rejectCounters));
    }

    public void resetRejectCounters() {
        rejectCounters.clear();
    }

    private void hitReject(String reason) {
        rejectCounters.merge(reason, 1, Integer::sum);
    }
}
