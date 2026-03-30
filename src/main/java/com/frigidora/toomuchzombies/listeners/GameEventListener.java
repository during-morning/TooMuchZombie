package com.frigidora.toomuchzombies.listeners;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
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
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.CreeperPowerEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.ai.HiveMindManager;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.enums.ZombieRole;
import com.frigidora.toomuchzombies.mechanics.AwarenessManager;
import com.frigidora.toomuchzombies.mechanics.BloodMoonManager;
import com.frigidora.toomuchzombies.mechanics.DropCleanupManager;
import com.frigidora.toomuchzombies.mechanics.LightSourceManager;
import com.frigidora.toomuchzombies.mechanics.PlayerLevelManager;
import com.frigidora.toomuchzombies.mechanics.ZombieFactory;

public class GameEventListener implements Listener {
    private static final String STEALTH_CREEPER_MARK = "tmz_stealth_creeper";
    private static final String STEALTH_CREEPER_PDC_KEY = "tmz_stealth_creeper";
    private static final String STEALTH_CREEPER_SPAWN_MS_KEY = "tmz_stealth_creeper_spawn_ms";
    private static final String STEALTH_CREEPER_REMOVE_SCHEDULED = "tmz_stealth_creeper_remove_scheduled";
    private static final long STEALTH_CREEPER_PROTECTION_MS = 5000L;
    private static final int END_WORLD_ZOMBIE_CAP = 220;
    private static final int END_NEARBY_ZOMBIE_CAP = 96;

    private final HiveMindManager hiveMindManager = new HiveMindManager();
    private final AwarenessManager awarenessManager = AwarenessManager.getInstance();
    private final Random random = new Random();

    public GameEventListener() {
        startGlobalStealthCreeperEnforcer();
        startLowHealthAggroPulse();
    }

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.getEntity() instanceof Zombie && event.getTarget() instanceof Zombie) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        Location loc = entity.getLocation();
        boolean convertible = isConvertibleHostile(entity);
        boolean customSpawn = event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM;

        // CUSTOM 生成来源：只对“可转换怪”强制转换；其它实体（尤其是插件自定义僵尸）保持原样。
        if (customSpawn && !convertible) {
            return;
        }

        if (!customSpawn && loc.getWorld().getEnvironment() == World.Environment.THE_END) {
            if (ZombieAIManager.getInstance().getZombieCountInWorld(loc.getWorld()) >= END_WORLD_ZOMBIE_CAP
                || countManagedZombiesNear(loc, 80.0) >= END_NEARBY_ZOMBIE_CAP) {
                event.setCancelled(true);
                return;
            }
        }

        if (!customSpawn && !ZombieFactory.evaluateSpawnPipeline(loc, entity.getType())) {
            event.setCancelled(true);
            return;
        }

        if (!customSpawn) {
            // 信标保护区检查：50 格范围内禁止生成僵尸和幻翼
            if (entity.getType() == EntityType.ZOMBIE || entity.getType() == EntityType.PHANTOM) {
                if (com.frigidora.toomuchzombies.mechanics.BeaconManager.getInstance().isNearActiveBeacon(loc, 50.0)) {
                    event.setCancelled(true);
                    return;
                }
            }

            int baseGlobal = ConfigManager.getInstance().getSpawnMaxGlobalZombies();
            int globalLimit = BloodMoonManager.getInstance().isBloodMoon(loc.getWorld())
                ? Math.max(baseGlobal, (int) Math.floor(baseGlobal * 1.30))
                : Math.max(900, (int) Math.floor(baseGlobal * 0.92));
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

            int basePerPlayer = ConfigManager.getInstance().getSpawnMaxNearPlayer();
            int perPlayerLimit = BloodMoonManager.getInstance().isBloodMoon(loc.getWorld())
                ? Math.max(basePerPlayer, (int) Math.floor(basePerPlayer * 1.20))
                : Math.max(96, (int) Math.floor(basePerPlayer * 0.88));

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

            if (nearest != null && minDistSq < 96 * 96) { // 放宽到 96 格范围内的生成
                int nearbyManaged = countManagedZombiesNear(loc, 96.0);
                if (nearbyManaged >= perPlayerLimit) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (entity instanceof Creeper creeper) {
            configureStealthCreeper(creeper, true);
            return;
        }

        if (convertible) {
            event.setCancelled(true);
            spawnConvertedZombie(loc);
            return;
        }

        if (entity instanceof Zombie) {
            ZombieFactory.assignRole((Zombie) entity);
            calculateAndApplyStats(event, (Zombie) entity, loc);
        }
    }

    @EventHandler
    public void onCreeperPower(CreeperPowerEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)) {
            return;
        }
        // 强制不进入充能态，避免电弧可见与状态抖动。
        event.setCancelled(true);
        // 闪电苦力怕同样不转换，统一走隐身+禁自主移动逻辑。
        configureStealthCreeper(creeper, true);
    }

    private void spawnConvertedZombie(Location loc) {
        if (!ZombieFactory.canSpawnManagedZombie(loc)) {
            return;
        }
        Zombie z = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
        ZombieFactory.assignRole(z);
        calculateAndApplyStats(null, z, loc);
    }

    private boolean isConvertibleHostile(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof Zombie) {
            return false;
        }
        return entity instanceof AbstractSkeleton
            || entity instanceof Spider
            || entity instanceof Enderman
            || entity instanceof Witch
            || entity.getType() == EntityType.ZOMBIFIED_PIGLIN
            || entity.getType() == EntityType.DROWNED;
    }

    private int countManagedZombiesNear(Location center, double radius) {
        int count = 0;
        for (com.frigidora.toomuchzombies.ai.ZombieAgent agent : ZombieAIManager.getInstance().getNearbyAgents(center, radius)) {
            Zombie z = agent.getZombie();
            if (z != null && z.isValid() && z.getWorld().equals(center.getWorld())) {
                count++;
            }
        }
        return count;
    }

    private void configureStealthCreeper(Creeper creeper, boolean scheduleReapply) {
        if (creeper == null || !creeper.isValid()) {
            return;
        }
        if (!creeper.hasMetadata(STEALTH_CREEPER_MARK)) {
            creeper.setMetadata(STEALTH_CREEPER_MARK, new FixedMetadataValue(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), true));
        }
        markStealthCreeper(creeper);
        scheduleStealthCreeperRemoval(creeper);

        // 保留苦力怕自爆逻辑，仅移除其自主移动能力。
        applyStealthCreeperState(creeper);

        if (scheduleReapply) {
            // 双保险：下一 tick 再应用一次，覆盖可能的后置初始化/属性回写。
            org.bukkit.Bukkit.getScheduler().runTaskLater(
                com.frigidora.toomuchzombies.TooMuchZombies.getInstance(),
                () -> applyStealthCreeperState(creeper),
                1L
            );
        }
    }

    private void applyStealthCreeperState(Creeper creeper) {
        if (creeper == null || !creeper.isValid() || !isStealthCreeper(creeper)) {
            return;
        }
        creeper.setInvisible(true);
        creeper.setGlowing(false);
        if (!creeper.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            creeper.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, true, false, false), true);
        }
        if (creeper.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
            double current = creeper.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
            if (Math.abs(current) > 1.0E-6) {
                creeper.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.0);
            }
        }
    }

    private void markStealthCreeper(Creeper creeper) {
        NamespacedKey key = new NamespacedKey(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), STEALTH_CREEPER_PDC_KEY);
        creeper.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        NamespacedKey spawnMsKey = new NamespacedKey(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), STEALTH_CREEPER_SPAWN_MS_KEY);
        if (!creeper.getPersistentDataContainer().has(spawnMsKey, PersistentDataType.LONG)) {
            creeper.getPersistentDataContainer().set(spawnMsKey, PersistentDataType.LONG, System.currentTimeMillis());
        }
    }

    private boolean isStealthCreeper(Creeper creeper) {
        NamespacedKey key = new NamespacedKey(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), STEALTH_CREEPER_PDC_KEY);
        return creeper.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean isWithinStealthProtectionWindow(Creeper creeper) {
        NamespacedKey spawnMsKey = new NamespacedKey(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), STEALTH_CREEPER_SPAWN_MS_KEY);
        Long spawnMs = creeper.getPersistentDataContainer().get(spawnMsKey, PersistentDataType.LONG);
        if (spawnMs == null) {
            return false;
        }
        return System.currentTimeMillis() - spawnMs < STEALTH_CREEPER_PROTECTION_MS;
    }

    private void scheduleStealthCreeperRemoval(Creeper creeper) {
        if (creeper.hasMetadata(STEALTH_CREEPER_REMOVE_SCHEDULED)) {
            return;
        }
        creeper.setMetadata(
            STEALTH_CREEPER_REMOVE_SCHEDULED,
            new FixedMetadataValue(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), true)
        );
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            com.frigidora.toomuchzombies.TooMuchZombies.getInstance(),
            () -> {
                if (creeper == null || !creeper.isValid() || !isStealthCreeper(creeper)) {
                    return;
                }
                if (isWithinStealthProtectionWindow(creeper)) {
                    return;
                }
                creeper.remove();
            },
            STEALTH_CREEPER_PROTECTION_MS / 50L
        );
    }

    private void startGlobalStealthCreeperEnforcer() {
        org.bukkit.Bukkit.getScheduler().runTaskTimer(
            com.frigidora.toomuchzombies.TooMuchZombies.getInstance(),
            () -> {
                for (World world : org.bukkit.Bukkit.getWorlds()) {
                    if (world.getPlayers().isEmpty()) {
                        continue;
                    }
                    for (Entity entity : world.getEntitiesByClass(Creeper.class)) {
                        Creeper creeper = (Creeper) entity;
                        if (!isStealthCreeper(creeper)) {
                            configureStealthCreeper(creeper, false);
                        }
                    }
                }
            },
            40L,
            400L
        );
    }

    private void startLowHealthAggroPulse() {
        org.bukkit.Bukkit.getScheduler().runTaskTimer(
            com.frigidora.toomuchzombies.TooMuchZombies.getInstance(),
            () -> {
                for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                        continue;
                    }
                    if (player.getHealth() <= 8.0) {
                        awarenessManager.alertNoise(player.getLocation(), 30.0, player);
                    }
                }
            },
            40L,
            20L
        );
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

            if (level >= 8 && (cause == DamageCause.FALL || cause == DamageCause.ENTITY_ATTACK)) {
                event.setDamage(event.getDamage() * 0.85);
            }

            if (cause == DamageCause.PROJECTILE ||
                cause == DamageCause.FIRE ||
                cause == DamageCause.FIRE_TICK ||
                cause == DamageCause.LAVA ||
                cause == DamageCause.HOT_FLOOR ||
                cause == DamageCause.MAGIC) {
                event.setDamage(event.getDamage() * 1.2);
            }
        } else if (event.getEntity() instanceof Player player) {
            double remaining = Math.max(0.0, player.getHealth() - event.getFinalDamage());
            if (remaining <= 8.0) {
                awarenessManager.alertNoise(player.getLocation(), 30.0, player);
            }
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
            if (DropCleanupManager.getInstance() != null) {
                DropCleanupManager.getInstance().tagZombieDrops(event);
            }

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
            notifyNoise(event.getPlayer().getLocation(), 10.0, event.getPlayer());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        notifyNoise(event.getBlock().getLocation(), 15.0, event.getPlayer());

        // 如果方块是僵尸放置的，移除元数据
        if (event.getBlock().hasMetadata("ZombieBlock")) {
            event.getBlock().removeMetadata("ZombieBlock", com.frigidora.toomuchzombies.TooMuchZombies.getInstance());
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        notifyNoise(event.getBlock().getLocation(), 15.0, event.getPlayer());
        if (LightSourceManager.isAttractingLight(event.getBlockPlaced().getType())) {
            awarenessManager.alertLightAttraction(event.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5), 42.0);
        }
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

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
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
        notifyNoise(event.getEntity().getLocation(), 25.0, event.getDamager() instanceof Player ? (Player) event.getDamager() : null);

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

        // 3. 僵尸近战减伤已进一步削弱，不再附带额外事件 debuff。
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
        if (lv <= 1) spawnChance = 0.50;
        else if (lv >= maxLevel) spawnChance = 1.00;
        else spawnChance = 0.50 + Math.pow((lv - 1.0) / Math.max(1.0, (maxLevel - 1.0)), 0.60) * 0.50;

        spawnChance *= BloodMoonManager.getInstance().isBloodMoon(zombie.getWorld())
            ? ConfigManager.getInstance().getBloodMoonMultiplier()
            : 0.46;
        spawnChance = Math.min(1.0, spawnChance);

        if (random.nextDouble() > spawnChance) {
            if (event != null) event.setCancelled(true);
            else {
                zombie.remove();
                ZombieAIManager.getInstance().unregisterZombie(zombie.getUniqueId());
            }
            return;
        }
        ZombieFactory.applyLevelAttributes(zombie, lv);
        if (random.nextDouble() < (0.18 + Math.min(0.22, lv * 0.02))) {
            int amplifier = random.nextDouble() < 0.2 ? 1 : 0;
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, amplifier, true, false, true));
        }
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
        double progression = Math.max(0.0, Math.min(1.0, (level - 1.0) / 11.0));
        int bonusRolls = level >= 10 ? 2 : (level >= 6 ? 1 : 0);
        int totalRolls = 1 + bonusRolls;

        double resourceChance = 0.16 + progression * 0.32;
        double utilityChance = 0.18 + progression * 0.24;
        double rareChance = progression * 0.22;
        double bookChance = 0.015 + progression * 0.10;
        double gearChance = 0.008 + progression * 0.075;

        for (int i = 0; i < totalRolls; i++) {
            if (random.nextDouble() < resourceChance) {
                event.getDrops().add(rollMetalDrop(level));
            }
            if (random.nextDouble() < utilityChance) {
                if (random.nextDouble() < 0.62) {
                    int boneAmount = 1 + random.nextInt(1 + Math.max(1, level / 5));
                    event.getDrops().add(new ItemStack(Material.BONE_MEAL, Math.min(5, boneAmount)));
                } else {
                    int powderAmount = 1 + (level >= 9 && random.nextDouble() < 0.35 ? 1 : 0);
                    event.getDrops().add(new ItemStack(Material.GUNPOWDER, powderAmount));
                }
            }
            if (random.nextDouble() < rareChance) {
                event.getDrops().add(new ItemStack(Material.SPIDER_EYE, 1 + (level >= 11 && random.nextDouble() < 0.30 ? 1 : 0)));
            }
        }

        if (level >= 9 && random.nextDouble() < 0.06 + progression * 0.12) {
            event.getDrops().add(new ItemStack(Material.EMERALD, 1 + (level >= 12 && random.nextDouble() < 0.35 ? 1 : 0)));
        }
        if (level >= 11 && random.nextDouble() < 0.015 + progression * 0.035) {
            event.getDrops().add(new ItemStack(Material.DIAMOND, 1));
        }

        if (random.nextDouble() < bookChance) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta meta = book.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta) {
                EnchantmentStorageMeta es = (EnchantmentStorageMeta) meta;
                Enchantment ench = pickRandomBookEnchant();
                int enchLevel = Math.max(1, Math.min(4, 1 + level / 4 + random.nextInt(2)));
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

    private ItemStack rollMetalDrop(int level) {
        double goldWeight = Math.min(0.55, 0.18 + level * 0.03);
        Material material = random.nextDouble() < goldWeight ? Material.GOLD_INGOT : Material.IRON_INGOT;
        int amount = 1 + (level >= 8 && random.nextDouble() < 0.35 ? 1 : 0);
        if (level >= 12 && random.nextDouble() < 0.20) {
            amount++;
        }
        return new ItemStack(material, Math.min(4, amount));
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
        Material[] golden = new Material[] {
            Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS,
            Material.GOLDEN_SWORD
        };
        Material[] diamond = new Material[] {
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.DIAMOND_SWORD
        };
        Material[] netherite = new Material[] {
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.NETHERITE_SWORD
        };

        double roll = random.nextDouble();
        Material[] pool;
        if (level <= 4) {
            pool = roll < 0.82 ? iron : golden;
        } else if (level <= 8) {
            if (roll < 0.66) pool = iron;
            else if (roll < 0.93) pool = golden;
            else pool = diamond;
        } else if (level <= 11) {
            if (roll < 0.52) pool = iron;
            else if (roll < 0.79) pool = golden;
            else if (roll < 0.985) pool = diamond;
            else pool = netherite;
        } else {
            if (roll < 0.42) pool = iron;
            else if (roll < 0.69) pool = golden;
            else if (roll < 0.97) pool = diamond;
            else pool = netherite;
        }
        Material mat = pool[random.nextInt(pool.length)];

        ItemStack item = new ItemStack(mat);
        int enchCount = 1 + (level >= 10 ? 1 : 0);
        if (level >= 12 && random.nextDouble() < 0.30) {
            enchCount++;
        }

        for (int i = 0; i < enchCount; i++) {
            Enchantment ench = pickRandomGearEnchant(mat);
            int enchLevel = Math.max(1, Math.min(3, 1 + level / 5 + random.nextInt(2)));
            item.addUnsafeEnchantment(ench, Math.min(enchLevel, ench.getMaxLevel()));
        }

        if (random.nextDouble() < Math.min(0.18, 0.03 + 0.01 * level)) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, Math.min(2, 1 + level / 6));
        }

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable) {
            int max = mat.getMaxDurability();
            if (max > 0) {
                int minDamage = (int) (max * 0.35);
                int maxDamage = (int) (max * 0.92);
                int wear = minDamage + random.nextInt(Math.max(1, maxDamage - minDamage + 1));
                damageable.setDamage(Math.min(max - 1, wear));
                item.setItemMeta((ItemMeta) damageable);
            }
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
        event.setYield(0.0f);
        notifyNoise(event.getLocation(), 40.0, null);
    }

    // --- 性能优化：噪音冷却 ---
    // 记录每个区块的最后噪音时间，避免高频触发
    private final java.util.Map<Long, Long> chunkNoiseCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    private void notifyNoise(Location location, double range, Player sourcePlayer) {
        if (ConfigManager.getInstance().getNoiseThreshold() <= 0) return;

        long chunkKey = location.getChunk().getChunkKey();
        long now = System.currentTimeMillis();

        if (chunkNoiseCooldowns.containsKey(chunkKey) && now - chunkNoiseCooldowns.get(chunkKey) < 1000) {
            return;
        }
        chunkNoiseCooldowns.put(chunkKey, now);

        awarenessManager.alertNoise(location, range, sourcePlayer);
    }
}
