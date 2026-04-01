package com.frigidora.toomuchzombies.mechanics;

import java.util.Collection;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;

/**
 * 声源吸引管理器 - 从 ZombieGame 移植
 * 
 * 监听各种声音事件并吸引附近的僵尸：
 * 1. 玩家受伤 - 高优先级
 * 2. 玩家攻击 - 高优先级
 * 3. 方块破坏 - 中优先级
 * 4. 方块放置 - 低优先级
 * 5. 玩家移动（奔跑/跳跃）- 低优先级
 * 6. 低血量玩家 - 持续吸引
 */
public class SoundAttractionManager implements Listener {
    
    private static SoundAttractionManager instance;
    
    private SoundAttractionManager() {
        TooMuchZombies.getInstance().getServer().getPluginManager()
            .registerEvents(this, TooMuchZombies.getInstance());
    }
    
    public static void initialize() {
        if (instance == null) {
            instance = new SoundAttractionManager();
        }
    }
    
    public static SoundAttractionManager getInstance() {
        return instance;
    }

    
    /**
     * 玩家受伤事件 - 高优先级吸引
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getEntity();
        if (!canBeAttracted(player)) {
            return;
        }
        
        // 受伤声音 - 75格范围，强度 1.5
        attractZombies(player.getLocation(), 75.0, 1.5, player);
    }
    
    /**
     * 玩家攻击事件 - 高优先级吸引
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getDamager();
        if (!canBeAttracted(player)) {
            return;
        }
        
        // 攻击声音 - 75格范围，强度 1.5
        attractZombies(player.getLocation(), 75.0, 1.5, player);
        
        // 如果受害者也是可攻击目标，也吸引僵尸
        if (event.getEntity() instanceof LivingEntity) {
            LivingEntity victim = (LivingEntity) event.getEntity();
            if (canBeAttracted(victim)) {
                attractZombies(victim.getLocation(), 75.0, 1.2, victim);
            }
        }
    }
    
    /**
     * 方块破坏事件 - 中优先级吸引
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!canBeAttracted(player)) {
            return;
        }
        
        // 破坏方块声音 - 50格范围，强度 1.0
        attractZombies(event.getBlock().getLocation(), 50.0, 1.0, player);
    }
    
    /**
     * 方块放置事件 - 低优先级吸引
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!canBeAttracted(player)) {
            return;
        }
        
        // 放置方块声音 - 30格范围，强度 0.5
        attractZombies(event.getBlock().getLocation(), 30.0, 0.5, player);
    }

    
    /**
     * 玩家移动事件 - 低优先级吸引（奔跑/跳跃）
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!canBeAttracted(player)) {
            return;
        }
        
        // 只在奔跑或跳跃时吸引
        if (player.isSprinting() || !player.isOnGround()) {
            // 每秒最多触发一次
            if (player.getTicksLived() % 20 == 0) {
                // 移动声音 - 20格范围，强度 0.3
                attractZombies(player.getLocation(), 20.0, 0.3, player);
            }
        }
        
        // 低血量玩家持续吸引
        if (player.getHealth() <= 5.0) {
            // 每2秒触发一次
            if (player.getTicksLived() % 40 == 0) {
                // 低血量气味 - 40格范围，强度 0.8
                attractZombies(player.getLocation(), 40.0, 0.8, player);
            }
        }
    }
    
    /**
     * 检查实体是否可以被吸引僵尸
     */
    private boolean canBeAttracted(LivingEntity entity) {
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return false;
        }
        
        if (entity instanceof Player) {
            Player player = (Player) entity;
            GameMode mode = player.getGameMode();
            return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
        }
        
        return true;
    }

    
    /**
     * 吸引附近的僵尸 - 核心方法
     * 
     * @param location 声源位置
     * @param range 吸引范围（格）
     * @param strength 声音强度（0.0-2.0）
     * @param source 声源实体（可选）
     */
    public void attractZombies(Location location, double range, double strength, LivingEntity source) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        
        // 获取范围内的所有僵尸
        Collection<Entity> nearbyEntities = location.getWorld().getNearbyEntities(
            location, range, range, range,
            entity -> entity instanceof Zombie && entity.isValid()
        );
        
        int attracted = 0;
        for (Entity entity : nearbyEntities) {
            if (!(entity instanceof Zombie)) {
                continue;
            }
            
            Zombie zombie = (Zombie) entity;
            ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
            
            if (agent == null) {
                continue;
            }
            
            // 如果僵尸没有目标，直接设置目标
            if (zombie.getTarget() == null && source != null) {
                zombie.setTarget(source);
                attracted++;
            }
            
            // 设置噪音提示
            long ttl = (long) (5000 * strength); // 强度越高，持续时间越长
            agent.setNoiseHint(location, ttl, strength);
            
            // 如果声源是实体，设置为调查目标
            if (source != null && agent.getInvestigationTarget() == null) {
                agent.setInvestigationTarget(source.getLocation(), ttl);
            }
        }
        
        // 调试日志
        if (attracted > 0 && TooMuchZombies.getInstance().getConfig().getBoolean("debug.sound-attraction", false)) {
            TooMuchZombies.getInstance().getLogger().info(
                String.format("Sound at %s attracted %d zombies (range: %.1f, strength: %.1f)",
                    formatLocation(location), attracted, range, strength)
            );
        }
    }
    
    /**
     * 手动触发声音吸引（供其他系统调用）
     */
    public void triggerSound(Location location, double range, double strength) {
        attractZombies(location, range, strength, null);
    }
    
    /**
     * 格式化位置信息
     */
    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
