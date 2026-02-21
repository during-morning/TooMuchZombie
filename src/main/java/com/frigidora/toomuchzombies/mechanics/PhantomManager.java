package com.frigidora.toomuchzombies.mechanics;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Phantom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;

public class PhantomManager implements Listener {

    private static PhantomManager instance;
    private final Map<UUID, Location> diveTargets = new ConcurrentHashMap<>();
    
    // --- 性能优化：幻翼缓存 ---
    // 避免每 tick 遍历全图实体
    private final java.util.Set<Phantom> phantomCache = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void initialize() {
        if (instance == null) {
            instance = new PhantomManager();
            Bukkit.getPluginManager().registerEvents(instance, TooMuchZombies.getInstance());
            instance.startTask();
        }
    }

    public static PhantomManager getInstance() {
        return instance;
    }

    private final Map<UUID, Long> flightStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> altitudeLockCooldowns = new ConcurrentHashMap<>();

    private void startTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 维护飞行时间并处理锁定
                long now = System.currentTimeMillis();
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                    if (p.isGliding()) {
                        if (!flightStartTimes.containsKey(p.getUniqueId())) {
                            flightStartTimes.put(p.getUniqueId(), now);
                        } else {
                            long duration = now - flightStartTimes.get(p.getUniqueId());
                            // 每 30s (30000ms) 检查一次
                            if (duration >= 30000) {
                                // 重置计时器（每 30s 一轮）
                                flightStartTimes.put(p.getUniqueId(), now);
                                
                                // 50% 概率被锁定
                                if (Math.random() < 0.5) {
                                    triggerFlightLock(p);
                                }
                            }
                        }
                    } else {
                        flightStartTimes.remove(p.getUniqueId());
                    }
                    
                    // 高空锁定机制 (Y > 100)
                    if (!p.isGliding() && p.getLocation().getY() > 100) {
                        if (p.getTicksLived() % 200 == 0) {
                            Long last = altitudeLockCooldowns.get(p.getUniqueId());
                            if (last == null || now - last > 60000) {
                                if (Math.random() < 0.25) {
                                    altitudeLockCooldowns.put(p.getUniqueId(), now);
                                    triggerHighAltitudeLock(p);
                                }
                            }
                        }
                    }
                }
            
                // 使用缓存遍历幻翼，移除无效实体
                phantomCache.removeIf(phantom -> !phantom.isValid());
                
                for (Phantom phantom : phantomCache) {
                    if (handleBeaconRepel(phantom)) {
                        continue; // 如果被信标驱赶，跳过自爆逻辑
                    }
                    handleKamikaze(phantom);
                }
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 1L, 1L);
    }

    /**
     * 处理信标驱赶逻辑
     * @return 如果幻翼被驱赶返回 true
     */
    private boolean handleBeaconRepel(Phantom phantom) {
        // 检查 40 格范围内的活跃信标
        Location beaconLoc = BeaconManager.getInstance().getNearestActiveBeacon(phantom.getLocation(), 40.0);
        if (beaconLoc == null) return false;

        // 如果正在自爆，取消自爆
        if (diveTargets.containsKey(phantom.getUniqueId())) {
            diveTargets.remove(phantom.getUniqueId());
            phantom.getWorld().playSound(phantom.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_HURT, 1.0f, 0.5f);
        }

        // 计算排斥向量：从信标指向幻翼
        org.bukkit.util.Vector repelDir = phantom.getLocation().toVector().subtract(beaconLoc.toVector());
        double dist = repelDir.length();
        
        if (dist < 0.1) {
            repelDir = new org.bukkit.util.Vector(0, 1, 0); // 防止除以零
        } else {
            repelDir.normalize();
        }

        // 越靠近推力越大，并带有上升分量
        double strength = Math.max(0.5, (40.0 - dist) / 20.0);
        repelDir.multiply(strength).add(new org.bukkit.util.Vector(0, 0.2, 0));
        
        phantom.setVelocity(repelDir);

        // 粒子效果
        if (phantom.getTicksLived() % 2 == 0) {
            try {
                phantom.getWorld().spawnParticle(org.bukkit.Particle.valueOf("WITCH"), phantom.getLocation(), 3, 0.3, 0.3, 0.3, 0.02);
            } catch (Exception e) {
                phantom.getWorld().spawnParticle(org.bukkit.Particle.valueOf("SPELL_WITCH"), phantom.getLocation(), 3, 0.3, 0.3, 0.3, 0.02);
            }
            phantom.getWorld().spawnParticle(org.bukkit.Particle.valueOf("END_ROD"), phantom.getLocation(), 1, 0.1, 0.1, 0.1, 0.01);
        }

        return true;
    }

    private void triggerFlightLock(org.bukkit.entity.Player p) {
        // 锁定惩罚
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS, 100, 0)); // 黑暗
        
        org.bukkit.potion.PotionEffectType slowType = org.bukkit.potion.PotionEffectType.getByName("SLOW");
        if (slowType == null) slowType = org.bukkit.potion.PotionEffectType.getByName("SLOWNESS");
        if (slowType != null) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(slowType, 100, 3));
        }
        
        // 损耗鞘翅耐久 (x5)
        org.bukkit.inventory.ItemStack chest = p.getInventory().getChestplate();
        if (chest != null && chest.getType() == org.bukkit.Material.ELYTRA) {
            org.bukkit.inventory.meta.Damageable meta = (org.bukkit.inventory.meta.Damageable) chest.getItemMeta();
            if (meta != null) {
                meta.setDamage(meta.getDamage() + 5);
                chest.setItemMeta(meta);
                if (meta.getDamage() >= chest.getType().getMaxDurability()) {
                    chest.setAmount(0); // Break it
                    p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                }
            }
        }
        
        // 粒子效果
        try {
            p.getWorld().spawnParticle(org.bukkit.Particle.valueOf("SMOKE_LARGE"), p.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);
        } catch (Exception e) {
            try {
                p.getWorld().spawnParticle(org.bukkit.Particle.valueOf("LARGE_SMOKE"), p.getLocation(), 20, 0.5, 0.5, 0.5, 0.1);
            } catch (Exception ignored) {}
        }
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_BITE, 2.0f, 0.5f);

        // 召唤附近的幻翼围攻
        boolean foundPhantom = false;
        
        // 性能优化：搜索半径减半 (100 -> 50)，使用 spatial partition 或者更高效的方法
        // 由于这里没有 spatial partition，我们直接使用 getNearbyEntities 但范围缩小
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(50, 50, 50)) {
            if (e instanceof Phantom) {
                diveTargets.put(e.getUniqueId(), p.getLocation());
                ((Phantom) e).setVelocity(p.getLocation().toVector().subtract(e.getLocation().toVector()).normalize().multiply(3.0));
                foundPhantom = true;
            }
        }
        
        // 如果没有幻翼，生成一只
        if (!foundPhantom) {
            Location spawnLoc = findSafeAirAbove(p.getLocation(), 20);
            if (spawnLoc != null) {
                Phantom phantom = (Phantom) p.getWorld().spawnEntity(spawnLoc, org.bukkit.entity.EntityType.PHANTOM);
                diveTargets.put(phantom.getUniqueId(), p.getLocation());
                phantom.setVelocity(new org.bukkit.util.Vector(0, -3, 0));
            }
        }
    }

    private void triggerHighAltitudeLock(org.bukkit.entity.Player p) {
        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_SWOOP, 2.0f, 0.5f);
        
        // 在上方生成幻翼
        Location spawnLoc = findSafeAirAbove(p.getLocation(), 20);
        if (spawnLoc == null) return;

        Phantom phantom = (Phantom) p.getWorld().spawnEntity(spawnLoc, org.bukkit.entity.EntityType.PHANTOM);
        phantom.setSize(3 + (int)(Math.random() * 3)); // 较大的幻翼
        diveTargets.put(phantom.getUniqueId(), p.getLocation());
        phantom.setVelocity(p.getLocation().toVector().subtract(phantom.getLocation().toVector()).normalize().multiply(2.0));
        
        phantomCache.add(phantom);
    }

    private Location findSafeAirAbove(Location base, int up) {
        if (base == null || base.getWorld() == null) return null;

        int maxY = base.getWorld().getMaxHeight() - 2;
        Location loc = base.clone().add(0, Math.max(2, up), 0);
        if (loc.getY() > maxY) loc.setY(maxY);

        for (int i = 0; i < 12; i++) {
            if (!loc.getBlock().getType().isSolid() && !loc.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                return loc;
            }
            loc.add(0, -1, 0);
            if (loc.getY() <= base.getBlockY() + 2) break;
        }
        return null;
    }

    private void handleKamikaze(Phantom phantom) {
        Location target = diveTargets.get(phantom.getUniqueId());
        
        // 1. 如果没有目标，寻找最近的爆破请求或自主决定自爆
        if (target == null) {
            // 每 20 ticks (约 1 秒) 检查一次，避免性能开销
            if (phantom.getTicksLived() % 20 != 0) return;
            
            // 检查附近的高等级僵尸以决定自爆概率
            double autonomousChance = 0.05; // 基础自主概率 (5%)
            double breachChance = 1.0;     // 响应请求概率
            
            int maxNearbyLevel = 1;
            for (org.bukkit.entity.Entity e : phantom.getNearbyEntities(64, 64, 64)) {
                if (e instanceof org.bukkit.entity.Zombie) {
                    com.frigidora.toomuchzombies.ai.ZombieAgent agent = ZombieAIManager.getInstance().getAgent(e.getUniqueId());
                    if (agent != null && agent.getLevel() > maxNearbyLevel) {
                        maxNearbyLevel = agent.getLevel();
                    }
                }
            }
            
            if (maxNearbyLevel >= 9) {
                autonomousChance = 0.3; // 9+ 级 30% 概率
                if (maxNearbyLevel == 11) autonomousChance = 0.2; // 11 级 20% 概率 (按要求)
                // 12 级和 9+ 通用规则都是 30%
            }
            
            // 优先检查爆破请求
            Location request = ZombieAIManager.getInstance().getNearestBreachRequest(phantom.getLocation(), 60.0);
            if (request != null && Math.random() < breachChance) {
                // 接受任务
                diveTargets.put(phantom.getUniqueId(), request);
                ZombieAIManager.getInstance().fulfillBreachRequest(request);
                
                phantom.getWorld().playSound(phantom.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_SWOOP, 2.0f, 1.5f);
                phantom.setTarget(null);
                return;
            }
            
            // 自主决定向玩家自爆
            if (Math.random() < autonomousChance) {
                org.bukkit.entity.Player nearestPlayer = null;
                double minDistSq = Double.MAX_VALUE;
                for (org.bukkit.entity.Player p : phantom.getWorld().getPlayers()) {
                    double d = p.getLocation().distanceSquared(phantom.getLocation());
                    if (d < minDistSq && d < 60 * 60) {
                        minDistSq = d;
                        nearestPlayer = p;
                    }
                }
                
                if (nearestPlayer != null) {
                    // 玩家生存 1-2 天时，自爆概率 -50%
                    // 2 个游戏日 = 40 分钟 = 2400000 毫秒
                    if (System.currentTimeMillis() - nearestPlayer.getFirstPlayed() < 2400000L) {
                        if (Math.random() < 0.5) { // 50% 几率取消这次自爆尝试
                            return;
                        }
                    }

                    diveTargets.put(phantom.getUniqueId(), nearestPlayer.getLocation());
                    phantom.getWorld().playSound(phantom.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_SWOOP, 2.0f, 1.5f);
                    phantom.setTarget(null);
                }
            }
            return;
        }
        
        // 2. 如果有目标，执行俯冲逻辑
        
        // 检查世界是否一致
        if (!phantom.getWorld().equals(target.getWorld())) {
            diveTargets.remove(phantom.getUniqueId());
            return;
        }
        
        // 检查目标是否失效（太远或已过期）- 这里简单假设一旦锁定就直到撞击
        double distSq = phantom.getLocation().distanceSquared(target);
        if (distSq < 4.0) { // < 2 blocks
            // 撞击！引爆
            explode(phantom);
            return;
        }
        
        // --- 高空机制 & 卡住检测 ---
        boolean isHighAltitude = phantom.getLocation().getY() > 100; // 假设 > 100 为高空
        
        // 检查前方是否有方块阻挡
        org.bukkit.util.Vector velocity = phantom.getVelocity();
        Location ahead = phantom.getLocation().add(velocity.normalize().multiply(1.5));
        if (ahead.getBlock().getType().isSolid()) {
            // 被卡住/撞墙
            explode(phantom); // 直接自爆
            return;
        }
        
        // 高空冲锋破坏方块 (除了黑曜石等)
        if (isHighAltitude) {
            org.bukkit.block.Block b = phantom.getLocation().getBlock();
            if (b.getType() != org.bukkit.Material.AIR && b.getType() != org.bukkit.Material.OBSIDIAN && b.getType() != org.bukkit.Material.BEDROCK) {
                b.breakNaturally();
            }
            
            // 25% 概率冲锋后立即自爆 (仅限高空)
            if (Math.random() < 0.25 && phantom.getTicksLived() % 20 == 0) {
                 explode(phantom);
                 return;
            }
        }
        
        // 强制移动向量
        org.bukkit.util.Vector dir = target.toVector().subtract(phantom.getLocation().toVector()).normalize();
        phantom.setVelocity(dir.multiply(1.5)); // 1.5 倍速俯冲
        
        // 粒子拖尾
        phantom.getWorld().spawnParticle(org.bukkit.Particle.FLAME, phantom.getLocation(), 2, 0.1, 0.1, 0.1, 0.05);
    }
    
    private void explode(Phantom phantom) {
        diveTargets.remove(phantom.getUniqueId());
        
        // 地面保护：如果在地面附近，不产生破坏性爆炸 (或者直接取消爆炸？需求说“在地面禁止自爆”)
        // 检查下方 5 格是否有固体方块
        boolean isNearGround = false;
        for (int i = 0; i < 5; i++) {
            if (phantom.getLocation().subtract(0, i, 0).getBlock().getType().isSolid()) {
                isNearGround = true;
                break;
            }
        }
        
        if (isNearGround) {
             // 仅造成伤害，不破坏方块，或者完全哑火？
             // “在地面禁止自爆” -> 理解为不产生爆炸效果
             phantom.getWorld().createExplosion(phantom.getLocation(), 0.0f, false, false); // 视觉效果
             phantom.setHealth(0);
             phantom.remove();
             return;
        }

        phantom.getWorld().createExplosion(phantom.getLocation(), 3.0f, false, true);
        phantom.setHealth(0); // 确保死亡
        phantom.remove();
    }
    
    public void registerPhantom(Phantom phantom) {
        if (phantom != null && phantom.isValid()) {
            phantomCache.add(phantom);
        }
    }
    
    @EventHandler
    public void onEntitySpawn(org.bukkit.event.entity.EntitySpawnEvent event) {
        if (event.getEntity() instanceof Phantom) {
            phantomCache.add((Phantom) event.getEntity());
        }
    }

    @EventHandler
    public void onEntityCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        // 禁止幻翼在白天燃烧
        if (event.getEntity() instanceof Phantom) {
            event.setCancelled(true);
        }
    }
    
    // 清理失效的 UUID
    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity() instanceof Phantom) {
            diveTargets.remove(event.getEntity().getUniqueId());
        }
    }
}
