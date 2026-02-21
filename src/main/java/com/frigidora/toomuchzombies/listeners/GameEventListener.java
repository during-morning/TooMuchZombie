package com.frigidora.toomuchzombies.listeners;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Witch;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.ai.HiveMindManager;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.enums.ZombieRole;
import com.frigidora.toomuchzombies.mechanics.ChaosManager;
import com.frigidora.toomuchzombies.mechanics.PlayerLevelManager;
import com.frigidora.toomuchzombies.mechanics.ZombieFactory;

public class GameEventListener implements Listener {

    private final HiveMindManager hiveMindManager = new HiveMindManager();
    private final Random random = new Random();

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.getEntity() instanceof Zombie && event.getTarget() instanceof Zombie) {
            // 如果混乱模式处于活动状态，允许锁定目标
            if (ChaosManager.getInstance().isChaosNight()) return;
            
            // 否则取消
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;

        LivingEntity entity = event.getEntity();
        Location loc = entity.getLocation();

        if (!ZombieFactory.evaluateSpawnPipeline(loc, entity.getType())) {
            event.setCancelled(true);
            return;
        }

        // 信标保护区检查：50 格范围内禁止生成僵尸和幻翼
        if (entity.getType() == EntityType.ZOMBIE || entity.getType() == EntityType.PHANTOM) {
            if (com.frigidora.toomuchzombies.mechanics.BeaconManager.getInstance().isNearActiveBeacon(loc, 50.0)) {
                event.setCancelled(true);
                return;
            }
        }
        
        // 血月与明月机制
        boolean isBloodMoon = com.frigidora.toomuchzombies.mechanics.BloodMoonManager.getInstance().isBloodMoon();
        boolean isBrightMoon = com.frigidora.toomuchzombies.mechanics.BloodMoonManager.getInstance().isBrightMoon();
        
        // 明月升起：夜晚僵尸不会生成
        if (isBrightMoon && (entity instanceof Zombie || entity instanceof AbstractSkeleton || entity instanceof Creeper || entity instanceof Spider || entity instanceof Enderman || entity instanceof Witch)) {
            event.setCancelled(true);
            return;
        }

        // 检查硬上限 (全局: 2000)
        // 如果是血月，上限翻倍 (4000)
        int globalLimit = isBloodMoon ? 4000 : 2000;
        if (ZombieAIManager.getInstance().getZombieCount() >= globalLimit) {
            if (entity instanceof Zombie || 
                entity instanceof AbstractSkeleton || 
                entity instanceof Creeper || 
                entity instanceof Spider ||
                entity instanceof Enderman ||
                entity instanceof Witch) {
                    
                event.setCancelled(true);
                return;
            }
        }
        
        // 限制单玩家周围僵尸数量 (100)
        // 血月翻倍 (200)
        int perPlayerLimit = isBloodMoon ? 200 : 100;
        
        // 查找最近的玩家
        Player nearest = null;
        double minDistSq = Double.MAX_VALUE;
        for (Player p : loc.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(loc);
            if (d < minDistSq) {
                minDistSq = d;
                nearest = p;
            }
        }
        
        if (nearest != null && minDistSq < 64 * 64) { // 仅检查 64 格内的生成
            int nearbyZombies = 0;
            for (Entity e : nearest.getNearbyEntities(64, 64, 64)) {
                if (e instanceof Zombie) {
                    nearbyZombies++;
                }
            }
            
            if (nearbyZombies >= perPlayerLimit) {
                event.setCancelled(true);
                return;
            }
        }
        
        // 海洋生物转化
        if (entity instanceof org.bukkit.entity.Drowned) {
            ZombieFactory.assignRole((Zombie) entity);
            calculateAndApplyStats(event, (Zombie) entity, loc);
            return;
        }
        
        // 地狱生物转化 (僵尸猪灵)
        if (entity.getType() == EntityType.ZOMBIFIED_PIGLIN) {
            Zombie z = (Zombie) entity;
            // 注入 AI，但保持中立
            ZombieFactory.assignRole(z);
            // 修正猪人建筑工只能用特定方块
            if (ZombieAIManager.getInstance().getAgent(z.getUniqueId()) != null && 
                ZombieAIManager.getInstance().getAgent(z.getUniqueId()).getRole() == ZombieRole.BUILDER) {
                
                Material[] netherBlocks = {Material.NETHERRACK, Material.COBBLESTONE, Material.BONE_BLOCK};
                z.getEquipment().setItemInMainHand(new ItemStack(netherBlocks[random.nextInt(netherBlocks.length)]));
            }
            calculateAndApplyStats(event, z, loc);
            return;
        }

        if (entity instanceof Zombie) {
            ZombieFactory.assignRole((Zombie) entity);
            calculateAndApplyStats(event, (Zombie) entity, loc);
        } else if (entity instanceof Enderman) {
            event.setCancelled(true);
            spawnZombie(loc, ZombieRole.ENDER);
        } else if (entity instanceof AbstractSkeleton) {
            event.setCancelled(true);
            // 骷髅转化为随机僵尸 (原先是 ARCHER，现在改为随机)
            Zombie z = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
            ZombieFactory.assignRole(z);
        } else if (entity instanceof Creeper) {
            event.setCancelled(true);
             // 苦力怕转化为随机僵尸 (原先是 RUSHER/SUICIDE，现在改为随机)
            Zombie z = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
            ZombieFactory.assignRole(z);
        } else if (entity instanceof Spider) {
            event.setCancelled(true);
            Zombie z = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
            ZombieFactory.assignRole(z); // 随机角色
        } else if (entity instanceof Witch) {
            event.setCancelled(true);
            // 女巫转化为随机僵尸 (原先是 NURSE，现在改为随机)
            Zombie z = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
            ZombieFactory.assignRole(z);
        }
    }

    private void spawnZombie(Location loc, ZombieRole role) {
        Zombie zombie = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
        ZombieFactory.assignRole(zombie, role);
        calculateAndApplyStats(null, zombie, loc);
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Zombie) {
            // 如果不是由实体或方块引起的燃烧，通常是阳光
            if (!(event instanceof org.bukkit.event.entity.EntityCombustByEntityEvent) && 
                !(event instanceof org.bukkit.event.entity.EntityCombustByBlockEvent)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Zombie) {
            Zombie zombie = (Zombie) event.getEntity();
            DamageCause cause = event.getCause();
            
            // 获取等级
            int level = 1;
            NamespacedKey key = new NamespacedKey(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), "zombie_level");
            if (zombie.getPersistentDataContainer().has(key, PersistentDataType.INTEGER)) {
                level = zombie.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
            }

            if (level >= 4) {
                // 4级之后：减免50%衰落/近程伤害
                if (cause == DamageCause.FALL || cause == DamageCause.ENTITY_ATTACK) {
                    event.setDamage(event.getDamage() * 0.5);
                }
                
                // 4级之后：获得2倍远程/火焰等伤害
                if (cause == DamageCause.PROJECTILE || 
                    cause == DamageCause.FIRE || 
                    cause == DamageCause.FIRE_TICK || 
                    cause == DamageCause.LAVA || 
                    cause == DamageCause.HOT_FLOOR ||
                    cause == DamageCause.MAGIC) { // "等"可能包括魔法
                    event.setDamage(event.getDamage() * 2.0);
                }
            } else {
                 // 之前我已经加了无条件 50% 减伤，现在应该由 Level 控制，所以这里不再额外处理
                 // 或者，如果用户想要所有僵尸都减免，就不会特意说 "4级之后"。
                 // 所以这里不做处理，恢复原版伤害机制。
            }
            
            // 移除原本的火焰/爆炸免疫逻辑
        }
    }

    @EventHandler
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.EnderPearl) {
            org.bukkit.entity.EnderPearl pearl = (org.bukkit.entity.EnderPearl) event.getEntity();
            if (pearl.getShooter() instanceof Zombie) {
                Zombie z = (Zombie) pearl.getShooter();
                com.frigidora.toomuchzombies.ai.ZombieAgent agent = ZombieAIManager.getInstance().getAgent(z.getUniqueId());
                if (agent != null && agent.getRole() == ZombieRole.ENDER) {
                    // 末影珍珠僵尸扔出末影珍珠会受到5点摔落伤害
                    z.damage(5.0); 
                    // 播放末影人瞬移声音
                    z.getWorld().playSound(z.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerKill(EntityDeathEvent event) {
        if (event.getEntity().getKiller() != null) {
            Player killer = event.getEntity().getKiller();
            // 仅在击杀僵尸时记录击杀数用于奖励
            if (event.getEntity() instanceof Zombie) {
                PlayerLevelManager.getInstance().recordKill(killer);
            } else {
                // 其他实体的击杀仅用于等级计算（如果需要）
                // 目前 PlayerLevelManager.recordKill 会触发血量更新，
                // 所以我们在这里区分开来。
                // 实际上 getPlayerLevel 使用的是 stats[1]，
                // 所以我们需要一个新的方法或者在 recordKill 中判断。
            }
        }
        
        if (event.getEntity() instanceof Zombie) {
            Zombie zombie = (Zombie) event.getEntity();
            int level = getZombieLevel(zombie);
            addZombieDrops(event, zombie, level);

            ZombieAIManager.getInstance().unregisterZombie(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        PlayerLevelManager.getInstance().recordDeath(player);
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        // 玩家加入时应用血量属性
        PlayerLevelManager.getInstance().applyHealthStats(event.getPlayer());
    }

    // 噪音事件

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting()) {
            notifyNoise(event.getPlayer().getLocation(), 10.0);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        notifyNoise(event.getBlock().getLocation(), 15.0);
        
        // 如果方块是僵尸放置的，移除元数据
        if (event.getBlock().hasMetadata("ZombieBlock")) {
            event.getBlock().removeMetadata("ZombieBlock", com.frigidora.toomuchzombies.TooMuchZombies.getInstance());
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        notifyNoise(event.getBlock().getLocation(), 15.0);
    }

    @EventHandler
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || 
            event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            
            ItemStack item = event.getItem();
            if (item != null && item.getType() == Material.LIGHT) {
                // 检查是否是特定的“光源”方块（通过名称识别，防止冲突）
                org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                boolean isLightBlock = false;
                if (meta != null && meta.hasDisplayName()) {
                    String displayName = meta.getDisplayName();
                    if (displayName.contains("光源") || displayName.contains("Light")) {
                        isLightBlock = true;
                    }
                }
                
                if (!isLightBlock) return; // 如果不是特定的光源方块，不执行逻辑
                
                Player player = event.getPlayer();
                
                // 消耗光源
                if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                    item.setAmount(item.getAmount() - 1);
                }
                
                // 播放爆发效果音效和粒子
                player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 2.0F, 1.5F);
                player.getWorld().spawnParticle(org.bukkit.Particle.FLASH, player.getLocation().add(0, 1, 0), 50, 0.5, 0.5, 0.5, 0.1);
                
                // 影响 50 米范围内的僵尸
                for (Entity e : player.getNearbyEntities(50, 50, 50)) {
                    if (e instanceof Zombie) {
                        Zombie z = (Zombie) e;
                        
                        // 1. 30 点虚空伤害
                        z.damage(30.0);
                        
                        // 2. 虚弱 255 持续 20 秒 (400 ticks)
                        z.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 400, 254));
                        
                        // 3. 锁定 AI 15 秒 (300 ticks)，实现“禁止使用技能”和“逃离”
                        com.frigidora.toomuchzombies.ai.ZombieAgent agent = ZombieAIManager.getInstance().getAgent(z.getUniqueId());
                        if (agent != null) {
                            agent.setAiPaused(true);
                            // 15秒后恢复 AI (这里用异步任务或者简单的 tick 检查，但简单起见直接用 scheduler)
                            new org.bukkit.scheduler.BukkitRunnable() {
                                @Override
                                public void run() {
                                    if (agent.getZombie().isValid()) {
                                        agent.setAiPaused(false);
                                    }
                                }
                            }.runTaskLater(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), 300L);
                            
                            // 强制计算逃离方向
                            Vector fleeDir = z.getLocation().toVector().subtract(player.getLocation().toVector()).normalize();
                            Location fleeTarget = z.getLocation().add(fleeDir.multiply(30));
                            
                            agent.moveTo(fleeTarget, 2.0);
                        }
                    }
                }
                
                // 取消事件，防止方块放置
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamageEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            PlayerLevelManager.getInstance().recordDamage(player, event.getFinalDamage());
        }
    }

    @EventHandler
    public void onCombat(EntityDamageByEntityEvent event) {
        notifyNoise(event.getEntity().getLocation(), 25.0);
        
        // 1. 玩家攻击僵尸：检查伤害提升
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Zombie) {
            Player player = (Player) event.getDamager();
            Zombie zombie = (Zombie) event.getEntity();
            com.frigidora.toomuchzombies.ai.ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());

            // COMBAT 盾牌防御窗口：正面来袭减伤并轻微反制
            if (agent != null
                && agent.getRole() == ZombieRole.COMBAT
                && agent.isShieldGuardActive()
                && isFrontAttack(zombie, player)) {
                event.setDamage(event.getDamage() * 0.35);
                player.damage(1.0, zombie);
                player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 0.8f, 1.0f);
            }

            if (PlayerLevelManager.getInstance().shouldTriggerDamageBoost(player)) {
                double originalDamage = event.getDamage();
                event.setDamage(originalDamage * 1.2); // 提升 20%
                
                // 播放效果音和粒子提醒玩家
                player.getWorld().spawnParticle(org.bukkit.Particle.CRIT, event.getEntity().getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1);
                player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.5f, 1.5f);
            }
        }

        // 2. 伤害缓冲 (Damage Dampening)
        if (event.getEntity() instanceof Player && event.getDamager() instanceof Zombie) {
            Player player = (Player) event.getEntity();
            int level = PlayerLevelManager.getInstance().getPlayerLevel(player);
            
            double reduction = 0.0;
            
            // 新手保护 (< Lv3)
            if (level < 3) {
                reduction += 0.15; // 15% 减伤
            }
            
            // 绝境缓冲 (< 4.0 HP)
            if (player.getHealth() < 4.0) {
                reduction += 0.25; // 额外 25% 减伤
            }
            
            if (reduction > 0) {
                double original = event.getDamage();
                event.setDamage(original * (1.0 - reduction));
            }
        }
        
        // 3. 混乱模式减益效果
        if (event.getDamager() instanceof Zombie && event.getEntity() instanceof Player) {
             if (ChaosManager.getInstance().isChaosNight() && random.nextDouble() < 0.3) { // 仅在混乱之夜
                 Player player = (Player) event.getEntity();
                 PotionEffectType[] debuffs = {
                     PotionEffectType.BLINDNESS,
                     PotionEffectType.NAUSEA,
                     PotionEffectType.HUNGER,
                     PotionEffectType.SLOWNESS
                 };
                 PotionEffectType type = debuffs[random.nextInt(debuffs.length)];
                 player.addPotionEffect(new PotionEffect(type, 100, 0)); // 5 秒
             }
        }

        // 4. 僵尸近战减伤 (Zombie Melee Resistance)
        // 已移至 onEntityDamage 处理 (Level >= 4)
    }

    private boolean isFrontAttack(Zombie zombie, Player attacker) {
        Vector forward = zombie.getLocation().getDirection().setY(0);
        Vector toAttacker = attacker.getLocation().toVector().subtract(zombie.getLocation().toVector()).setY(0);
        if (forward.lengthSquared() < 0.001 || toAttacker.lengthSquared() < 0.001) {
            return false;
        }
        return forward.normalize().dot(toAttacker.normalize()) > 0.25;
    }

    private void calculateAndApplyStats(CreatureSpawnEvent event, Zombie zombie, Location loc) {
        int encounterLevel = ZombieFactory.calculateEncounterLevelNearby(loc);
        applyZombieStats(event, zombie, encounterLevel);
        if (event != null && event.isCancelled()) {
            ZombieAIManager.getInstance().unregisterZombie(zombie.getUniqueId());
        }
    }

    private void applyZombieStats(CreatureSpawnEvent event, Zombie zombie, int level) {
        int maxLevel = ConfigManager.getInstance().getLevelMax();
        int lv = Math.max(1, Math.min(maxLevel, level));
        NamespacedKey key = new NamespacedKey(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), "zombie_level");
        zombie.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, lv);
        
        // 生成概率
        double spawnChance;
        if (lv <= 1) spawnChance = 0.10;
        else if (lv >= maxLevel) spawnChance = 1.00;
        else spawnChance = 0.10 + (lv - 1) * (0.90 / Math.max(1, maxLevel - 1));

        if (random.nextDouble() > spawnChance) {
            if (event != null) event.setCancelled(true);
            else {
                zombie.remove();
                ZombieAIManager.getInstance().unregisterZombie(zombie.getUniqueId());
            }
            return;
        }
        ZombieFactory.applyLevelAttributes(zombie, lv);
    }

    private int getZombieLevel(Zombie zombie) {
        com.frigidora.toomuchzombies.ai.ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
        if (agent != null) return agent.getLevel();

        NamespacedKey key = new NamespacedKey(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), "zombie_level");
        if (zombie.getPersistentDataContainer().has(key, PersistentDataType.INTEGER)) {
            Integer stored = zombie.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
            if (stored != null) return stored;
        }
        return 1;
    }

    private void addZombieDrops(EntityDeathEvent event, Zombie zombie, int level) {
        double ironChance = Math.min(0.30, 0.08 + 0.02 * level);
        double goldChance = Math.min(0.22, 0.05 + 0.015 * level);
        double gunpowderChance = Math.min(0.18, 0.03 + 0.01 * level);
        double boneMealChance = Math.min(0.35, 0.10 + 0.02 * level);
        double spiderEyeChance = Math.min(0.15, 0.03 + 0.01 * level);
        double bookChance = Math.min(0.12, 0.02 + 0.01 * level);
        double gearChance = Math.min(0.18, 0.02 + 0.02 * level);

        if (random.nextDouble() < ironChance) event.getDrops().add(new ItemStack(Material.IRON_INGOT, 1 + random.nextInt(2)));
        if (random.nextDouble() < goldChance) event.getDrops().add(new ItemStack(Material.GOLD_INGOT, 1));
        if (random.nextDouble() < gunpowderChance) event.getDrops().add(new ItemStack(Material.GUNPOWDER, 1));
        if (random.nextDouble() < boneMealChance) event.getDrops().add(new ItemStack(Material.BONE_MEAL, 1 + random.nextInt(3)));
        if (random.nextDouble() < spiderEyeChance) event.getDrops().add(new ItemStack(Material.SPIDER_EYE, 1));

        if (random.nextDouble() < bookChance) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta meta = book.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta) {
                EnchantmentStorageMeta es = (EnchantmentStorageMeta) meta;
                Enchantment ench = pickRandomBookEnchant();
                int enchLevel = Math.max(1, Math.min(5, 1 + level / 2 + random.nextInt(2)));
                es.addStoredEnchant(ench, Math.min(enchLevel, ench.getMaxLevel()), true);
                book.setItemMeta(es);
            }
            event.getDrops().add(book);
        }

        if (random.nextDouble() < gearChance) {
            ItemStack gear = pickRandomEnchantedGear(level);
            if (gear != null) event.getDrops().add(gear);
        }
    }

    private Enchantment pickRandomBookEnchant() {
        Enchantment[] pool = new Enchantment[] {
            Enchantment.SHARPNESS,
            Enchantment.PROTECTION,
            Enchantment.POWER,
            Enchantment.UNBREAKING,
            Enchantment.MENDING
        };
        return pool[random.nextInt(pool.length)];
    }

    private ItemStack pickRandomEnchantedGear(int level) {
        Material[] iron = new Material[] {
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
            Material.IRON_SWORD
        };
        Material[] diamond = new Material[] {
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.DIAMOND_SWORD
        };
        Material[] pool = level >= 5 ? diamond : iron;
        Material mat = pool[random.nextInt(pool.length)];

        ItemStack item = new ItemStack(mat);
        int enchCount = 1 + (level >= 6 ? 1 : 0) + (level >= 8 ? 1 : 0);

        for (int i = 0; i < enchCount; i++) {
            Enchantment ench = pickRandomGearEnchant(mat);
            int enchLevel = Math.max(1, Math.min(4, 1 + level / 2 + random.nextInt(2)));
            item.addUnsafeEnchantment(ench, Math.min(enchLevel, ench.getMaxLevel()));
        }

        if (random.nextDouble() < Math.min(0.30, 0.05 + 0.03 * level)) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, Math.min(3, 1 + level / 3));
        }

        return item;
    }

    private Enchantment pickRandomGearEnchant(Material mat) {
        String name = mat.name();
        if (name.endsWith("_SWORD")) {
            Enchantment[] pool = new Enchantment[] {Enchantment.SHARPNESS, Enchantment.FIRE_ASPECT, Enchantment.KNOCKBACK};
            return pool[random.nextInt(pool.length)];
        }

        Enchantment[] pool = new Enchantment[] {
            Enchantment.PROTECTION,
            Enchantment.PROJECTILE_PROTECTION,
            Enchantment.FIRE_PROTECTION,
            Enchantment.THORNS
        };
        return pool[random.nextInt(pool.length)];
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent event) {
        notifyNoise(event.getLocation(), 40.0);
        
        // 防止僵尸 TNT 破坏方块
        if (event.getEntity() instanceof org.bukkit.entity.TNTPrimed) {
            org.bukkit.entity.TNTPrimed tnt = (org.bukkit.entity.TNTPrimed) event.getEntity();
            if (tnt.hasMetadata("ZombieTNT")) {
                event.blockList().clear();
            }
        }
    }

    // --- 性能优化：噪音冷却 ---
    // 记录每个区块的最后噪音时间，避免高频触发
    private final java.util.Map<Long, Long> chunkNoiseCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    private void notifyNoise(Location location, double range) {
        // 性能优化：直接移除 getNearbyEntities 的全量搜索
        // 改为只提醒 ZombieAIManager，让其自行决定是否处理
        // 或者简单地，如果配置允许，我们只处理极小范围，或者使用 SpatialPartition
        
        if (ConfigManager.getInstance().getNoiseThreshold() <= 0) return;

        long chunkKey = location.getChunk().getChunkKey();
        long now = System.currentTimeMillis();
        
        // 冷却检查：同一区块 1 秒内只处理一次噪音
        if (chunkNoiseCooldowns.containsKey(chunkKey)) {
            if (now - chunkNoiseCooldowns.get(chunkKey) < 1000) {
                return;
            }
        }
        chunkNoiseCooldowns.put(chunkKey, now);

        // 使用 ZombieAIManager 的 SpatialPartition 进行优化查询
        // 而不是使用 Bukkit 的 getNearbyEntities (这会遍历区块内所有实体)
        // ZombieAIManager 已经维护了所有僵尸的位置
        // 我们只需遍历附近的僵尸 Agent
        
        java.util.Collection<com.frigidora.toomuchzombies.ai.ZombieAgent> agents = ZombieAIManager.getInstance().getNearbyAgents(location, range);
        for (com.frigidora.toomuchzombies.ai.ZombieAgent agent : agents) {
            agent.setTargetLocation(location);
        }
    }
}
