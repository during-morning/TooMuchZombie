package com.frigidora.toomuchzombies.ai.behavior;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.enums.BreachAssignmentRole;
import com.frigidora.toomuchzombies.enums.ZombieRole;

public class ZombieCooperationBehavior {

    private static final String RETREAT_REGROUP = "RETREAT_REGROUP";
    private static final String BREACH_SUPPORT = "BREACH_SUPPORT";
    private static final String FOCUS_FIRE = "FOCUS_FIRE";
    private static final String ENCIRCLE_PRESSURE = "ENCIRCLE_PRESSURE";
    private static final String FLANK_SYNC = "FLANK_SYNC";
    private static final String BODYGUARD = "BODYGUARD";

    private final ZombieAgent agent;
    private final Zombie zombie;

    private UUID protectTargetUUID;
    private long lastScanTime;
    private long lastBreachSupport;
    private long lastEncirclePlan;
    private long lastBodyguardPatrolPlan;

    private LivingEntity focusTarget;
    private long focusTargetExpiry;
    private UUID focusTargetUuid;
    private int formationSlotIndex;
    private long formationSlotExpiry;
    private UUID formationTargetUuid;
    private final Map<String, Integer> nodeHits = new ConcurrentHashMap<>();

    public ZombieCooperationBehavior(ZombieAgent agent) {
        this.agent = agent;
        this.zombie = agent.getZombie();
    }

    public void tick() {
        ZombieRole role = agent.getRole();
        if (role == ZombieRole.SUICIDE) return;

        if (tryRetreatRegroup()) {
            hit(RETREAT_REGROUP);
            return;
        }

        if (tryBreachSupport()) {
            hit(BREACH_SUPPORT);
            return;
        }

        if (tryFocusFire()) {
            hit(FOCUS_FIRE);
            return;
        }

        if (tryEncirclePressure()) {
            hit(ENCIRCLE_PRESSURE);
            return;
        }

        if (tryFlankSync()) {
            hit(FLANK_SYNC);
            return;
        }

        if (tryBodyguard()) {
            hit(BODYGUARD);
        }
    }

    private void hit(String node) {
        nodeHits.merge(node, 1, Integer::sum);
    }

    public Map<String, Integer> getNodeHitCountersSnapshot() {
        return Collections.unmodifiableMap(new java.util.HashMap<>(nodeHits));
    }

    public void resetNodeHitCounters() {
        nodeHits.clear();
    }

    public LivingEntity getFocusTarget() {
        if (focusTarget == null || System.currentTimeMillis() > focusTargetExpiry || !focusTarget.isValid() || focusTarget.isDead()) {
            focusTarget = null;
            focusTargetExpiry = 0L;
            focusTargetUuid = null;
        }
        return focusTarget;
    }

    public int getFormationSlotIndex() {
        LivingEntity target = getFocusTarget();
        if (target == null || !target.isValid()) return 0;
        if (formationTargetUuid != null
            && formationTargetUuid.equals(target.getUniqueId())
            && System.currentTimeMillis() <= formationSlotExpiry) {
            return formationSlotIndex;
        }

        java.util.List<UUID> allies = new java.util.ArrayList<>();
        for (Entity e : zombie.getNearbyEntities(24, 12, 24)) {
            if (e instanceof Zombie) {
                ZombieAgent other = ZombieAIManager.getInstance().getAgent(e.getUniqueId());
                if (other != null) {
                    LivingEntity otherTarget = other.getCooperationBehavior().getFocusTarget();
                    if (otherTarget != null && otherTarget.getUniqueId().equals(target.getUniqueId())) {
                        allies.add(e.getUniqueId());
                    }
                }
            }
        }
        allies.add(zombie.getUniqueId());
        allies.sort(java.util.Comparator.comparing(UUID::toString));
        int base = Math.max(0, allies.indexOf(zombie.getUniqueId()));
        int roleBias = agent.getRole() == ZombieRole.ARCHER ? 2 : (agent.getRole() == ZombieRole.COMBAT ? 1 : 0);
        formationSlotIndex = base + roleBias;
        formationTargetUuid = target.getUniqueId();
        formationSlotExpiry = System.currentTimeMillis() + 2400L;
        return formationSlotIndex;
    }

    public void clearTransientState() {
        focusTarget = null;
        focusTargetExpiry = 0L;
        focusTargetUuid = null;
        formationTargetUuid = null;
        formationSlotExpiry = 0L;
        protectTargetUUID = null;
        agent.setFocusTargetHint(null, 0L);
        agent.setProtectTargetHint(null, 0L);
        agent.setFlanking(false);
    }

    private void lockFocusTarget(LivingEntity target, long ttlMs) {
        if (target == null || !target.isValid() || target.isDead()) {
            focusTarget = null;
            focusTargetExpiry = 0L;
            focusTargetUuid = null;
            formationTargetUuid = null;
            formationSlotExpiry = 0L;
            agent.setFocusTargetHint(null, 0L);
            return;
        }
        focusTarget = target;
        focusTargetExpiry = System.currentTimeMillis() + Math.max(350L, ttlMs);
        focusTargetUuid = target.getUniqueId();
        agent.setFocusTargetHint(target, ttlMs);
    }

    private boolean tryRetreatRegroup() {
        LivingEntity currentTarget = zombie.getTarget();
        if (currentTarget == null) return false;

        double maxHealth = zombie.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
            ? zombie.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()
            : 20.0;
        double hpRatio = zombie.getHealth() / Math.max(1.0, maxHealth);

        int allyCount = 0;
        int enemyCount = 0;
        for (Entity e : zombie.getNearbyEntities(14, 8, 14)) {
            if (e instanceof Zombie) allyCount++;
            else if (e instanceof Player || e instanceof org.bukkit.entity.IronGolem || e instanceof org.bukkit.entity.Wolf) enemyCount++;
        }

        ConfigManager cfg = ConfigManager.getInstance();
        if (hpRatio > cfg.getCoopRetreatHealthThreshold()) return false;
        if ((enemyCount - allyCount) < cfg.getCoopAllyDisadvantageThreshold()) return false;

        org.bukkit.util.Vector away = zombie.getLocation().toVector().subtract(currentTarget.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = zombie.getLocation().getDirection().setY(0);
        if (away.lengthSquared() > 0.01) away.normalize();
        Location regroup = zombie.getLocation().clone().add(away.multiply(cfg.getCoopRegroupDistance()));
        agent.submitMoveIntent(regroup, 1.2, ZombieAgent.MovementPriority.CRITICAL, ZombieAgent.PathIntent.EVADE_LIGHT, 900L);
        return true;
    }

    private boolean tryBreachSupport() {
        if (agent.getRole() != ZombieRole.BUILDER
            && agent.getRole() != ZombieRole.MINER
            && agent.getRole() != ZombieRole.RUSHER) return false;

        Location breach = ZombieAIManager.getInstance().getNearestBreachRequest(zombie.getLocation(), 24);
        if (breach == null) return false;

        long now = System.currentTimeMillis();
        long cd = ConfigManager.getInstance().getCoopBreachSupportCooldownMs();
        if (now - lastBreachSupport < cd) return false;

        if (agent.getRole() == ZombieRole.MINER) {
            ZombieAIManager.getInstance().forceBreachRole(zombie.getUniqueId(), BreachAssignmentRole.PRIMARY);
        } else if (agent.getRole() == ZombieRole.BUILDER) {
            ZombieAIManager.getInstance().forceBreachRole(zombie.getUniqueId(), BreachAssignmentRole.SUPPORT);
        } else if (agent.getRole() == ZombieRole.RUSHER) {
            ZombieAIManager.getInstance().forceBreachRole(zombie.getUniqueId(), BreachAssignmentRole.PRIMARY);
        } else {
            ZombieAIManager.getInstance().assignBreachRole(zombie.getUniqueId(), breach);
        }
        double arriveRadius = ConfigManager.getInstance().getCoopBreachArriveRadius();
        if (zombie.getLocation().distanceSquared(breach) <= arriveRadius * arriveRadius) {
            ZombieAIManager.getInstance().fulfillBreachRequest(breach);
        } else {
            agent.submitMoveIntent(
                breach,
                agent.getRole() == ZombieRole.RUSHER ? 1.3 : 1.2,
                ZombieAgent.MovementPriority.HIGH,
                ZombieAgent.PathIntent.NAV_CORRIDOR,
                1200L
            );
        }
        lastBreachSupport = now;
        if (agent.getRole() == ZombieRole.BUILDER || agent.getRole() == ZombieRole.MINER) {
            agent.getBuilderBehavior().setActive(true);
        }
        return true;
    }

    private boolean tryFocusFire() {
        LivingEntity locked = getFocusTarget();
        if (locked != null && locked.isValid() && !locked.isDead()) {
            agent.setFocusTargetHint(locked, 1200L);
            return true;
        }

        double range = ConfigManager.getInstance().getCoopFocusFireRange();
        LivingEntity best = null;
        double bestScore = -1;

        for (Entity e : zombie.getNearbyEntities(range, 10, range)) {
            if (!(e instanceof LivingEntity) || e instanceof Zombie) continue;

            LivingEntity candidate = (LivingEntity) e;
            double dist = Math.max(1.0, zombie.getLocation().distance(candidate.getLocation()));
            double distanceScore = 1.0 / dist;

            double hpRatio = candidate.getHealth() / Math.max(1.0, candidate.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
                ? candidate.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()
                : 20.0);
            double lowHpScore = 1.0 - Math.max(0.0, Math.min(1.0, hpRatio));

            int allyLockCount = 0;
            for (Entity near : candidate.getNearbyEntities(16, 8, 16)) {
                if (near instanceof Zombie) {
                    Zombie z = (Zombie) near;
                    if (z.getTarget() != null && z.getTarget().getUniqueId().equals(candidate.getUniqueId())) {
                        allyLockCount++;
                    }
                }
            }
            double lockScore = Math.min(1.0, allyLockCount / 6.0);

            double score = distanceScore * 0.35 + lowHpScore * 0.40 + lockScore * 0.25;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null) return false;

        LivingEntity current = agent.getTargetEntity();
        if (current != null && current.isValid() && !current.isDead() && current.getWorld().equals(zombie.getWorld())) {
            double currentDistSq = zombie.getLocation().distanceSquared(current.getLocation());
            double bestDistSq = zombie.getLocation().distanceSquared(best.getLocation());
            if (!best.getUniqueId().equals(current.getUniqueId()) && bestDistSq > currentDistSq * 0.72) {
                return false;
            }
        }

        lockFocusTarget(best, 1800L);
        return true;
    }

    private boolean tryFlankSync() {
        if (zombie.getTarget() == null) return false;
        if (zombie.getLocation().distanceSquared(zombie.getTarget().getLocation()) <= 8.0 * 8.0) {
            agent.setFlanking(false);
            return false;
        }

        double range = ConfigManager.getInstance().getCoopFlankSyncRange();
        int engaged = 0;
        for (Entity e : zombie.getNearbyEntities(range, 8, range)) {
            if (e instanceof Zombie) {
                Zombie z = (Zombie) e;
                if (z.getTarget() != null && zombie.getTarget() != null
                    && z.getTarget().getUniqueId().equals(zombie.getTarget().getUniqueId())) {
                    engaged++;
                }
            }
        }

        if (engaged >= 3 && zombie.getLocation().distanceSquared(zombie.getTarget().getLocation()) >= 12.0 * 12.0) {
            agent.setFlanking(true);
            return true;
        }

        agent.setFlanking(false);
        return false;
    }

    private boolean tryEncirclePressure() {
        LivingEntity target = zombie.getTarget();
        if (target == null || !target.isValid()) return false;
        if (zombie.getLocation().distanceSquared(target.getLocation()) <= 9.0 * 9.0) return false;

        double range = ConfigManager.getInstance().getCoopEncircleRange();
        int allies = 0;
        int enemies = 1;
        for (Entity e : zombie.getNearbyEntities(range, 10, range)) {
            if (e instanceof Zombie) allies++;
            else if (e instanceof Player || e instanceof org.bukkit.entity.IronGolem || e instanceof org.bukkit.entity.Wolf) enemies++;
        }
        if (allies < 3) return false;
        if (System.currentTimeMillis() - lastEncirclePlan < ConfigManager.getInstance().getCoopEncircleReplanMs()) {
            return false;
        }
        lastEncirclePlan = System.currentTimeMillis();

        double pressure = allies / (double) Math.max(1, enemies);
        if (pressure < 1.1) return false;

        Vector toZombie = zombie.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        if (toZombie.lengthSquared() < 0.01) {
            toZombie = zombie.getLocation().getDirection().setY(0);
        }
        if (toZombie.lengthSquared() < 0.01) return false;
        toZombie.normalize();

        int hash = Math.abs(zombie.getUniqueId().hashCode());
        int lane = hash % 3; // 0 left, 1 center, 2 right
        double laneAngle = lane == 0 ? -0.95 : (lane == 2 ? 0.95 : 0.0);
        double radius = 2.8 + Math.min(5.0, pressure * 1.2);
        Vector slot = toZombie.clone().rotateAroundY(laneAngle).multiply(radius);
        Location move = target.getLocation().clone().add(slot);
        move.setY(zombie.getLocation().getY());
        agent.submitMoveIntent(
            move,
            1.2 + Math.min(0.25, pressure * 0.08),
            ZombieAgent.MovementPriority.MEDIUM,
            ZombieAgent.PathIntent.NAV_CORRIDOR,
            ConfigManager.getInstance().getCoopEncircleReplanMs()
        );
        return true;
    }

    private boolean tryBodyguard() {
        if (agent.getRole() == ZombieRole.MINER || agent.getRole() == ZombieRole.BUILDER) return false;

        if (protectTargetUUID == null) {
            if (System.currentTimeMillis() - lastScanTime > 2000) {
                scanForVIP();
                lastScanTime = System.currentTimeMillis();
            }
            return false;
        }

        ZombieAgent vipAgent = ZombieAIManager.getInstance().getAgent(protectTargetUUID);
        if (vipAgent == null || !vipAgent.getZombie().isValid()) {
            protectTargetUUID = null;
            return false;
        }

        Zombie vip = vipAgent.getZombie();
        if (!zombie.getWorld().equals(vip.getWorld())) {
            protectTargetUUID = null;
            return false;
        }

        if (vip.getLastDamageCause() instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) {
            Entity damager = ((org.bukkit.event.entity.EntityDamageByEntityEvent) vip.getLastDamageCause()).getDamager();
            if (damager instanceof LivingEntity && !(damager instanceof Zombie)) {
                agent.setProtectTargetHint((LivingEntity) damager, 1800L);
                return true;
            }
        }

        double distSq = zombie.getLocation().distanceSquared(vip.getLocation());
        if (distSq > 25.0) {
            agent.submitMoveIntent(vip.getLocation(), 1.1, ZombieAgent.MovementPriority.MEDIUM, ZombieAgent.PathIntent.NAV_CORRIDOR, 1200L);
        } else if (distSq < 9.0 && System.currentTimeMillis() - lastBodyguardPatrolPlan > 2200L) {
            lastBodyguardPatrolPlan = System.currentTimeMillis();
            double baseAngle = (Math.abs(zombie.getUniqueId().hashCode()) % 360) * Math.PI / 180.0;
            double radius = 1.8 + (Math.abs(zombie.getUniqueId().hashCode() >> 3) % 8) * 0.08;
            Location patrolLoc = vip.getLocation().clone().add(Math.cos(baseAngle) * radius, 0, Math.sin(baseAngle) * radius);
            patrolLoc.setY(zombie.getLocation().getY());
            agent.submitMoveIntent(patrolLoc, 0.78, ZombieAgent.MovementPriority.LOW, ZombieAgent.PathIntent.NAV_CORRIDOR, 1800L);
        }

        return true;
    }

    private void scanForVIP() {
        double minDistSq = ConfigManager.getInstance().getCoopBodyguardScanRange();
        minDistSq *= minDistSq;
        Zombie bestVIP = null;

        for (Entity e : zombie.getNearbyEntities(Math.sqrt(minDistSq), 10, Math.sqrt(minDistSq))) {
            if (e instanceof Zombie && !e.getUniqueId().equals(zombie.getUniqueId())) {
                ZombieAgent other = ZombieAIManager.getInstance().getAgent(e.getUniqueId());
                if (other != null && (other.getRole() == ZombieRole.MINER || other.getRole() == ZombieRole.BUILDER)) {
                    double d = e.getLocation().distanceSquared(zombie.getLocation());
                    if (d < minDistSq) {
                        minDistSq = d;
                        bestVIP = (Zombie) e;
                    }
                }
            }
        }

        if (bestVIP != null) {
            this.protectTargetUUID = bestVIP.getUniqueId();
        }
    }
}
