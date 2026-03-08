package com.frigidora.toomuchzombies.mechanics;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Phantom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;

public class PhantomManager implements Listener {

    private static PhantomManager instance;
    private final Map<UUID, Location> diveTargets = new ConcurrentHashMap<>();
    private final java.util.Set<Phantom> phantomCache = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> managedPhantoms = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> flightStartTimes = new ConcurrentHashMap<>();

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

    private void startTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();

                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                    if (p.isGliding()) {
                        if (!flightStartTimes.containsKey(p.getUniqueId())) {
                            flightStartTimes.put(p.getUniqueId(), now);
                        } else {
                            long duration = now - flightStartTimes.get(p.getUniqueId());
                            if (duration >= 30000) {
                                flightStartTimes.put(p.getUniqueId(), now);
                                if (Math.random() < 0.5) {
                                    triggerFlightLock(p);
                                }
                            }
                        }
                    } else {
                        flightStartTimes.remove(p.getUniqueId());
                    }
                }

                phantomCache.removeIf(phantom -> phantom == null || !phantom.isValid() || phantom.isDead());

                for (Phantom phantom : phantomCache) {
                    if (handleBeaconRepel(phantom)) {
                        continue;
                    }
                    handleKamikaze(phantom);
                }
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 1L, 1L);
    }

    private boolean handleBeaconRepel(Phantom phantom) {
        Location beaconLoc = BeaconManager.getInstance().getNearestActiveBeacon(phantom.getLocation(), 40.0);
        if (beaconLoc == null) return false;

        if (diveTargets.containsKey(phantom.getUniqueId())) {
            diveTargets.remove(phantom.getUniqueId());
            phantom.getWorld().playSound(phantom.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_HURT, 1.0f, 0.5f);
        }

        org.bukkit.util.Vector repelDir = phantom.getLocation().toVector().subtract(beaconLoc.toVector());
        double dist = repelDir.length();
        if (dist < 0.1) repelDir = new org.bukkit.util.Vector(0, 1, 0);
        else repelDir.normalize();

        double strength = Math.max(0.5, (40.0 - dist) / 20.0);
        repelDir.multiply(strength).add(new org.bukkit.util.Vector(0, 0.2, 0));
        phantom.setVelocity(repelDir);

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

    private void markManaged(Phantom phantom) {
        if (phantom == null) return;
        phantom.setMetadata("TMZ_MANAGED_PHANTOM", new FixedMetadataValue(TooMuchZombies.getInstance(), true));
        managedPhantoms.add(phantom.getUniqueId());
        phantomCache.add(phantom);
    }

    private void armDiveTarget(Phantom phantom, Location target) {
        if (phantom == null || target == null) return;
        markManaged(phantom);
        diveTargets.put(phantom.getUniqueId(), target.clone());
    }

    private void triggerFlightLock(org.bukkit.entity.Player p) {
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS, 100, 0));

        org.bukkit.potion.PotionEffectType slowType = org.bukkit.potion.PotionEffectType.getByName("SLOW");
        if (slowType == null) slowType = org.bukkit.potion.PotionEffectType.getByName("SLOWNESS");
        if (slowType != null) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(slowType, 100, 3));
        }

        org.bukkit.inventory.ItemStack chest = p.getInventory().getChestplate();
        if (chest != null && chest.getType() == org.bukkit.Material.ELYTRA) {
            org.bukkit.inventory.meta.Damageable meta = (org.bukkit.inventory.meta.Damageable) chest.getItemMeta();
            if (meta != null) {
                meta.setDamage(meta.getDamage() + 5);
                chest.setItemMeta(meta);
                if (meta.getDamage() >= chest.getType().getMaxDurability()) {
                    chest.setAmount(0);
                    p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                }
            }
        }

        p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_BITE, 2.0f, 0.5f);

        boolean foundPhantom = false;
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(50, 50, 50)) {
            if (e instanceof Phantom) {
                Phantom ph = (Phantom) e;
                armDiveTarget(ph, p.getLocation());
                ph.setVelocity(p.getLocation().toVector().subtract(e.getLocation().toVector()).normalize().multiply(3.0));
                foundPhantom = true;
            }
        }

        if (!foundPhantom) {
            Location spawnLoc = findSafeAirAbove(p.getLocation(), 20);
            if (spawnLoc != null) {
                Phantom phantom = (Phantom) p.getWorld().spawnEntity(spawnLoc, org.bukkit.entity.EntityType.PHANTOM);
                armDiveTarget(phantom, p.getLocation());
                phantom.setVelocity(new org.bukkit.util.Vector(0, -3, 0));
            }
        }
    }

    private Location findSafeAirAbove(Location base, int up) {
        if (base == null || base.getWorld() == null) return null;

        int maxY = base.getWorld().getMaxHeight() - 2;
        int minY = Math.max(base.getBlockY() + 2, base.getWorld().getMinHeight() + 2);

        Location primary = base.clone().add(0, Math.max(6, up), 0);
        if (primary.getY() > maxY) primary.setY(maxY);

        for (int i = 0; i < 18; i++) {
            if (!primary.getBlock().getType().isSolid() && !primary.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                return primary;
            }
            primary.add(0, -1, 0);
            if (primary.getY() <= minY) break;
        }

        int[][] offsets = new int[][]{{6,0},{-6,0},{0,6},{0,-6},{8,8},{-8,8},{8,-8},{-8,-8}};
        for (int[] off : offsets) {
            Location alt = base.clone().add(off[0], Math.max(10, up), off[1]);
            if (alt.getY() > maxY) alt.setY(maxY);
            for (int i = 0; i < 18; i++) {
                if (!alt.getBlock().getType().isSolid() && !alt.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                    return alt;
                }
                alt.add(0, -1, 0);
                if (alt.getY() <= minY) break;
            }
        }

        int fallbackY = Math.min(maxY, base.getWorld().getHighestBlockYAt(base) + 12);
        Location fallback = new Location(base.getWorld(), base.getX(), fallbackY, base.getZ());
        if (!fallback.getBlock().getType().isSolid()) return fallback;
        return null;
    }

    private void handleKamikaze(Phantom phantom) {
        Location target = diveTargets.get(phantom.getUniqueId());

        if (target == null) {
            if (phantom.getTicksLived() % 20 != 0) return;

            // 只让插件管理的幻翼执行自爆决策，避免原版幻翼造成“莫名爆炸”。
            if (!managedPhantoms.contains(phantom.getUniqueId())) {
                return;
            }

            double autonomousChance = 0.05;
            double breachChance = 1.0;

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
                autonomousChance = 0.3;
                if (maxNearbyLevel == 11) autonomousChance = 0.2;
            }

            Location request = ZombieAIManager.getInstance().getNearestBreachRequest(phantom.getLocation(), 60.0);
            if (request != null && Math.random() < breachChance) {
                armDiveTarget(phantom, request);
                ZombieAIManager.getInstance().fulfillBreachRequest(request);
                phantom.getWorld().playSound(phantom.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_SWOOP, 2.0f, 1.5f);
                phantom.setTarget(null);
                return;
            }

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
                    armDiveTarget(phantom, nearestPlayer.getLocation());
                    phantom.getWorld().playSound(phantom.getLocation(), org.bukkit.Sound.ENTITY_PHANTOM_SWOOP, 2.0f, 1.5f);
                    phantom.setTarget(null);
                }
            }
            return;
        }

        if (!phantom.getWorld().equals(target.getWorld())) {
            diveTargets.remove(phantom.getUniqueId());
            return;
        }

        double distSq = phantom.getLocation().distanceSquared(target);
        if (distSq < 4.0) {
            explode(phantom);
            return;
        }

        org.bukkit.util.Vector velocity = phantom.getVelocity();
        if (velocity.lengthSquared() > 0.01) {
            Location ahead = phantom.getLocation().clone().add(velocity.clone().normalize().multiply(1.5));
            if (ahead.getBlock().getType().isSolid()) {
                diveTargets.remove(phantom.getUniqueId());
                return;
            }
        }

        org.bukkit.util.Vector dir = target.toVector().subtract(phantom.getLocation().toVector()).normalize();
        phantom.setVelocity(dir.multiply(1.5));
        phantom.getWorld().spawnParticle(org.bukkit.Particle.FLAME, phantom.getLocation(), 2, 0.1, 0.1, 0.1, 0.05);
    }

    private void explode(Phantom phantom) {
        UUID id = phantom.getUniqueId();
        diveTargets.remove(id);

        if (!managedPhantoms.contains(id)) {
            return;
        }

        phantom.getWorld().createExplosion(phantom.getLocation(), 2.6f, false, false);
        phantom.setHealth(0);
        phantom.remove();
    }

    public void registerPhantom(Phantom phantom) {
        if (phantom != null && phantom.isValid()) {
            markManaged(phantom);
        }
    }

    @EventHandler
    public void onEntitySpawn(org.bukkit.event.entity.EntitySpawnEvent event) {
        if (event.getEntity() instanceof Phantom) {
            Phantom phantom = (Phantom) event.getEntity();
            if (phantom.hasMetadata("TMZ_MANAGED_PHANTOM")) {
                markManaged(phantom);
            }
        }
    }

    @EventHandler
    public void onEntityCombust(org.bukkit.event.entity.EntityCombustEvent event) {
        if (event.getEntity() instanceof Phantom) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity() instanceof Phantom) {
            UUID id = event.getEntity().getUniqueId();
            diveTargets.remove(id);
            managedPhantoms.remove(id);
            phantomCache.remove((Phantom) event.getEntity());
        }
    }
}
