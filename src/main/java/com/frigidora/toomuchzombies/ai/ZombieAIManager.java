package com.frigidora.toomuchzombies.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.mechanics.AwarenessManager;
import com.frigidora.toomuchzombies.enums.BreachAssignmentRole;
import com.frigidora.toomuchzombies.enums.ZombieRole;

public class ZombieAIManager implements Listener {
    private static ZombieAIManager instance;
    private final Map<UUID, ZombieAgent> agents = new ConcurrentHashMap<>();
    private final SpatialPartition spatialPartition = new SpatialPartition(); // 空间分区
    
    private final SmartPathingBehavior pathingBehavior = new SmartPathingBehavior();
    private final CombatBehavior combatBehavior = new CombatBehavior();
    private final HiveMindManager hiveMindManager = new HiveMindManager();
    private final AbilityArbitrator abilityArbitrator = new AbilityArbitrator();
    private final Map<AbilityIntent, Integer> arbitrationHitStats = new ConcurrentHashMap<>();
    
    // 构建预留表：记录方块位置和预留时间戳
    private final Map<org.bukkit.Location, Long> reservedBuildSpots = new ConcurrentHashMap<>();
    
    // 协作挖掘表：Block Location -> Number of miners working on it
    private final Map<org.bukkit.Location, Integer> activeMiningBlocks = new ConcurrentHashMap<>();
    
    // 协作建筑路径表：Target Location -> (Last selfPos used, Last update time)
    private static class BuildPathData {
        org.bukkit.Location currentPos;
        long lastUpdate;
        BuildPathData(org.bukkit.Location pos) { this.currentPos = pos; this.lastUpdate = System.currentTimeMillis(); }
    }
    private final Map<org.bukkit.Location, BuildPathData> activeBuildPaths = new ConcurrentHashMap<>();
    
    // 爆破请求表：Request Location -> Time Created
    private final Map<org.bukkit.Location, Long> breachRequests = new ConcurrentHashMap<>();
    private final Map<UUID, BreachAssignmentRole> breachRoles = new ConcurrentHashMap<>();
    private final Map<UUID, Long> breachRoleLeaseUntil = new ConcurrentHashMap<>();

    // 分时调度索引
    private int tickIndex = 0;
    private static final int TIME_SLICES = 5; // 分成 5 个时间片

    // --- 性能优化：分片列表 (Sharded Lists) ---
    // 为了实现真正的 O(N/M) 分时调度，我们需要直接遍历对应时间片的列表，而不是遍历所有 Key 然后取模
    private final java.util.List<UUID>[] shardedAgents = new java.util.List[TIME_SLICES];
    private final Map<UUID, UUID> plannedTargets = new ConcurrentHashMap<>();
    private final AtomicBoolean asyncPlanning = new AtomicBoolean(false);

    private record PlayerSnapshot(UUID uuid, UUID worldId, double x, double y, double z, double health, double maxHealth, int foodLevel) {}
    private record AgentSnapshot(UUID uuid, UUID worldId, double x, double y, double z, UUID currentTargetId) {}
    
    public java.util.Collection<ZombieAgent> getNearbyAgents(Location loc, double range) {
        // 使用 SpatialPartition 快速查找
        return spatialPartition.getNearbyAgents(loc, (int) range);
    }

    private ZombieAIManager() {
        // 初始化分片
        for (int i = 0; i < TIME_SLICES; i++) {
            shardedAgents[i] = new java.util.concurrent.CopyOnWriteArrayList<>();
        }
        
        TooMuchZombies.getInstance().getServer().getPluginManager().registerEvents(this, TooMuchZombies.getInstance());
        startTask();
    }

    private void startTask() {
        // 启动 AI 循环
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 1L, 1L);
    }

    @EventHandler
    public void onZombieDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Zombie) {
            Zombie z = (Zombie) event.getEntity();
            ZombieAgent agent = getAgent(z.getUniqueId());
            if (agent != null) {
                agent.recordDamage(event.getDamager() != null ? event.getDamager().getLocation() : null);
            }
        }
    }

    public static void initialize() {
        if (instance == null) {
            instance = new ZombieAIManager();
        }
    }

    public static ZombieAIManager getInstance() {
        return instance;
    }

    public SpatialPartition getSpatialPartition() {
        return spatialPartition;
    }
    
    public void registerZombie(Zombie zombie, ZombieRole role, int level) {
        ZombieAgent agent = new ZombieAgent(zombie, role);
        agent.setLevel(level);
        agents.put(zombie.getUniqueId(), agent);
        
        // 分配到分片列表
        int shardIndex = Math.abs(zombie.getUniqueId().hashCode()) % TIME_SLICES;
        shardedAgents[shardIndex].add(zombie.getUniqueId());
        
        spatialPartition.update(agent);
    }

    public void unregisterZombie(UUID uuid) {
        ZombieAgent agent = agents.remove(uuid);
        if (agent != null) {
            // 从分片列表移除
            int shardIndex = Math.abs(uuid.hashCode()) % TIME_SLICES;
            shardedAgents[shardIndex].remove(uuid);
            
            spatialPartition.remove(agent);
        }
        breachRoles.remove(uuid);
        breachRoleLeaseUntil.remove(uuid);
    }
    
    public ZombieAgent getAgent(UUID uuid) {
        return agents.get(uuid);
    }

    public int getZombieCount() {
        return agents.size();
    }

    public int getZombieCountInWorld(World world) {
        if (world == null) {
            return 0;
        }
        int count = 0;
        for (ZombieAgent agent : agents.values()) {
            Zombie z = agent.getZombie();
            if (z != null && z.isValid() && world.equals(z.getWorld())) {
                count++;
            }
        }
        return count;
    }
    
    public boolean isBuildSpotReserved(org.bukkit.Location loc) {
        Long time = reservedBuildSpots.get(loc);
        if (time == null) return false;
        // 5秒后过期
        if (System.currentTimeMillis() - time > 5000) {
            reservedBuildSpots.remove(loc);
            return false;
        }
        return true;
    }
    
    public void reserveBuildSpot(org.bukkit.Location loc) {
        reservedBuildSpots.put(loc, System.currentTimeMillis());
    }

    public void registerMiningOperation(org.bukkit.Location loc) {
        activeMiningBlocks.merge(loc, 1, Integer::sum);
    }
    
    public void unregisterMiningOperation(org.bukkit.Location loc) {
        activeMiningBlocks.computeIfPresent(loc, (k, v) -> v > 1 ? v - 1 : null);
    }

    public void registerBuildPath(org.bukkit.Location target, org.bukkit.Location currentPos) {
        activeBuildPaths.put(target, new BuildPathData(currentPos));
    }

    public org.bukkit.Location getActiveBuildPathPos(org.bukkit.Location target) {
        BuildPathData data = activeBuildPaths.get(target);
        return data != null ? data.currentPos : null;
    }

    public void removeBuildPath(org.bukkit.Location target) {
        activeBuildPaths.remove(target);
    }
    
    public int getMinersOnBlock(org.bukkit.Location loc) {
        return activeMiningBlocks.getOrDefault(loc, 0);
    }
    
    public void requestBreach(org.bukkit.Location loc) {
        // 避免重复请求 (5秒内不重复)
        if (!breachRequests.containsKey(loc)) {
            breachRequests.put(loc, System.currentTimeMillis());
        }
    }

    public BreachAssignmentRole assignBreachRole(UUID zombieId, org.bukkit.Location breachLocation) {
        ZombieAgent agent = agents.get(zombieId);
        if (agent == null || breachLocation == null) return BreachAssignmentRole.NONE;

        long now = System.currentTimeMillis();
        long leaseMs = ConfigManager.getInstance().getBreachLeaseMs();
        Long leaseUntil = breachRoleLeaseUntil.get(zombieId);
        BreachAssignmentRole existing = breachRoles.get(zombieId);
        if (existing != null && leaseUntil != null && leaseUntil > now) {
            return existing;
        }

        int primary = 0;
        int support = 0;
        int bodyguard = 0;
        for (Map.Entry<UUID, BreachAssignmentRole> e : breachRoles.entrySet()) {
            Long until = breachRoleLeaseUntil.get(e.getKey());
            if (until == null || until <= now) continue;
            switch (e.getValue()) {
                case PRIMARY:
                    primary++;
                    break;
                case SUPPORT:
                    support++;
                    break;
                case BODYGUARD:
                    bodyguard++;
                    break;
                default:
                    break;
            }
        }

        double distSq = agent.getZombie().getLocation().distanceSquared(breachLocation);
        double primaryScore = distSq * ConfigManager.getInstance().getBreachPrimaryDistanceBias();
        double supportScore = distSq * ConfigManager.getInstance().getBreachSupportDistanceBias();
        double bodyguardScore = distSq * ConfigManager.getInstance().getBreachBodyguardDistanceBias();

        BreachAssignmentRole role = BreachAssignmentRole.SUPPORT;
        if (primary < ConfigManager.getInstance().getBreachPrimaryCap() && primaryScore <= supportScore) {
            role = BreachAssignmentRole.PRIMARY;
        } else if (support >= ConfigManager.getInstance().getBreachSupportCap()
            && bodyguard < ConfigManager.getInstance().getBreachBodyguardCap()) {
            role = BreachAssignmentRole.BODYGUARD;
        } else if (support >= ConfigManager.getInstance().getBreachSupportCap()
            && primary < ConfigManager.getInstance().getBreachPrimaryCap()) {
            role = BreachAssignmentRole.PRIMARY;
        } else if (bodyguard < ConfigManager.getInstance().getBreachBodyguardCap() && bodyguardScore < supportScore * 0.9) {
            role = BreachAssignmentRole.BODYGUARD;
        }

        breachRoles.put(zombieId, role);
        breachRoleLeaseUntil.put(zombieId, now + leaseMs);
        return role;
    }

    public BreachAssignmentRole forceBreachRole(UUID zombieId, BreachAssignmentRole role) {
        if (role == null || role == BreachAssignmentRole.NONE) return BreachAssignmentRole.NONE;
        breachRoles.put(zombieId, role);
        breachRoleLeaseUntil.put(zombieId, System.currentTimeMillis() + ConfigManager.getInstance().getBreachLeaseMs());
        return role;
    }

    public BreachAssignmentRole getBreachRole(UUID zombieId) {
        long now = System.currentTimeMillis();
        Long until = breachRoleLeaseUntil.get(zombieId);
        if (until == null || until <= now) {
            breachRoles.remove(zombieId);
            breachRoleLeaseUntil.remove(zombieId);
            return BreachAssignmentRole.NONE;
        }
        return breachRoles.getOrDefault(zombieId, BreachAssignmentRole.NONE);
    }

    public Map<BreachAssignmentRole, Integer> getBreachRoleStats() {
        long now = System.currentTimeMillis();
        Map<BreachAssignmentRole, Integer> stats = new java.util.EnumMap<>(BreachAssignmentRole.class);
        for (BreachAssignmentRole role : BreachAssignmentRole.values()) {
            stats.put(role, 0);
        }
        for (Map.Entry<UUID, BreachAssignmentRole> entry : breachRoles.entrySet()) {
            Long until = breachRoleLeaseUntil.get(entry.getKey());
            if (until != null && until > now) {
                stats.put(entry.getValue(), stats.get(entry.getValue()) + 1);
            }
        }
        return stats;
    }

    public Map<String, Integer> getCooperationNodeHitStats() {
        Map<String, Integer> out = new java.util.HashMap<>();
        for (ZombieAgent agent : agents.values()) {
            Map<String, Integer> one = agent.getCooperationBehavior().getNodeHitCountersSnapshot();
            for (Map.Entry<String, Integer> e : one.entrySet()) {
                out.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return out;
    }

    public Map<AbilityIntent, Integer> getArbitrationHitStats() {
        Map<AbilityIntent, Integer> out = new java.util.EnumMap<>(AbilityIntent.class);
        for (AbilityIntent intent : AbilityIntent.values()) {
            out.put(intent, arbitrationHitStats.getOrDefault(intent, 0));
        }
        return out;
    }

    public Map<String, Integer> getBuilderFailureStats() {
        Map<String, Integer> out = new java.util.HashMap<>();
        for (ZombieAgent agent : agents.values()) {
            Map<String, Integer> one = agent.getBuilderBehavior().getFailureCountersSnapshot();
            for (Map.Entry<String, Integer> e : one.entrySet()) {
                out.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return out;
    }

    public Map<String, Integer> getBreakerRejectStats() {
        Map<String, Integer> out = new java.util.HashMap<>();
        for (ZombieAgent agent : agents.values()) {
            Map<String, Integer> one = agent.getBreakerBehavior().getRejectCountersSnapshot();
            for (Map.Entry<String, Integer> e : one.entrySet()) {
                out.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return out;
    }

    public void resetDebugStats() {
        breachRoles.clear();
        breachRoleLeaseUntil.clear();
        breachRequests.clear();
        arbitrationHitStats.clear();
        for (ZombieAgent agent : agents.values()) {
            agent.getCooperationBehavior().resetNodeHitCounters();
            agent.getBuilderBehavior().resetFailureCounters();
            agent.getBreakerBehavior().resetRejectCounters();
        }
    }
    
    public org.bukkit.Location getNearestBreachRequest(org.bukkit.Location myLoc, double maxDist) {
        org.bukkit.Location best = null;
        double minDistSq = maxDist * maxDist;
        
        long now = System.currentTimeMillis();
        
        // 简单的迭代查找
        for (Map.Entry<org.bukkit.Location, Long> entry : breachRequests.entrySet()) {
            if (now - entry.getValue() > 10000) continue; // 忽略超过10秒的请求
            
            if (entry.getKey().getWorld().equals(myLoc.getWorld())) {
                double d = entry.getKey().distanceSquared(myLoc);
                if (d < minDistSq) {
                    minDistSq = d;
                    best = entry.getKey();
                }
            }
        }
        return best;
    }
    
    public void fulfillBreachRequest(org.bukkit.Location loc) {
        breachRequests.remove(loc);
    }

    private void tick() {
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            if (world.getGameRuleValue(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE) == Boolean.FALSE) {
                world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, true);
            }
        }

        tickIndex = (tickIndex + 1) % TIME_SLICES;

        if (tickIndex == 0 && org.bukkit.Bukkit.getCurrentTick() % 20 == 0) {
            long now = System.currentTimeMillis();
            reservedBuildSpots.entrySet().removeIf(entry -> now - entry.getValue() > 5000);
            breachRequests.entrySet().removeIf(entry -> now - entry.getValue() > 10000);
            activeBuildPaths.entrySet().removeIf(entry -> now - entry.getValue().lastUpdate > 10000);
            breachRoleLeaseUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
            breachRoles.keySet().removeIf(id -> !breachRoleLeaseUntil.containsKey(id));
        }

        int currentCount = agents.size();
        boolean highLoad = currentCount > 800;
        int globalHardCap = highLoad ? 1100 : 1500;

        if (org.bukkit.Bukkit.getCurrentTick() % 4 == 0) {
            scheduleAsyncPlanning();
        }

        if (org.bukkit.Bukkit.getCurrentTick() % 20 == 0 && currentCount > globalHardCap) {
            int toRemove = currentCount - globalHardCap;
            for (UUID uuid : agents.keySet()) {
                if (toRemove <= 0) break;
                ZombieAgent agent = agents.get(uuid);
                if (agent != null && agent.getZombie().isValid()) {
                    agent.getZombie().remove();
                    unregisterZombie(uuid);
                    toRemove--;
                }
            }
        }

        processAgents(highLoad);
    }
    
    private void processAgents(boolean highLoad) {
        // 2. 检查每个玩家的上限和距离消失 & 执行 AI
        // 使用分时调度优化循环：只遍历当前时间片对应的分片列表
        
        java.util.List<UUID> currentShard = shardedAgents[tickIndex];
        
        // 使用迭代器以支持安全移除 (或者 CopyOnWriteArrayList 已经支持)
        for (UUID uuid : currentShard) {
            ZombieAgent agent = agents.get(uuid);
            if (agent == null || !agent.getZombie().isValid()) {
                agents.remove(uuid);
                currentShard.remove(uuid);
                continue;
            }
            
            Zombie z = agent.getZombie();

            if (agent.getTicksStuck() >= ConfigManager.getInstance().getRecoveryStuckTeleportTicks()) {
                if (tryRecoverStuckAgent(agent)) {
                    continue;
                }
            }
            
            // 更新空间分区位置
            spatialPartition.update(agent);
            
            // 无效实体清理 (Useless Check)
            // 优化：仅对每 100 tick (约5秒) 且没有目标的僵尸执行检查
            // 利用 checkAndResetSkillCooldown 实现节流
            if ((highLoad || agent.getTargetEntity() == null) && agent.checkAndResetSkillCooldown("USELESS_CHECK", highLoad ? 3000 : 5000)) {
                // 检查是否有玩家在附近 (64格)
                boolean playerNearby = false;
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (p.getWorld().equals(z.getWorld()) && p.getLocation().distanceSquared(z.getLocation()) < 64*64) {
                        playerNearby = true;
                        break;
                    }
                }
                if (!playerNearby) {
                     z.remove();
                     agents.remove(uuid);
                     currentShard.remove(uuid);
                     continue;
                }
            }
            
            // 3. 执行 AI 行为
            // 优化：移除此处的 AI 执行，交由 ZombieBehaviorGoal (Paper Goal) 每 tick 执行
            // 这里只负责管理任务（如清理、分区更新）
            /*
            try {
                executeBehavior(agent);
            } catch (Exception e) {
                // 防止单个实体错误导致崩溃
                e.printStackTrace();
            }
            */
        }
    }

    public void executeBehavior(ZombieAgent agent) {
        chooseOrRefreshTarget(agent);

        if ("full".equalsIgnoreCase(ConfigManager.getInstance().getAiOverrideMode())) {
            // Full mode: reduce conflicts with vanilla targets.
            if (agent.getZombie().getTarget() != null && !(agent.getZombie().getTarget() instanceof org.bukkit.entity.Player)) {
                agent.getZombie().setTarget(null);
            }
        }

        // 1. 检查传感器
        if (agent.getZombie().getTarget() instanceof Player) {
            Player target = (Player) agent.getZombie().getTarget();
            agent.setLastKnownTargetLocation(target.getLocation());
            agent.setTargetEntity(target);
            if (Math.random() < 0.1) { // 每 tick 10% 概率广播
                hiveMindManager.broadcastTarget(target, agent.getZombie());
            }
        }

        AbilityIntent intent = abilityArbitrator.decide(agent);
        arbitrationHitStats.merge(intent, 1, Integer::sum);

        // 2. 执行行为（仲裁）
        switch (intent) {
            case CHASE_COMBAT:
                pathingBehavior.tick(agent);
                combatBehavior.tick(agent);
                break;
            case STRUCTURE:
            case SUICIDE_CHARGE:
            case SURVIVE:
            case TARGET_SEARCH:
                pathingBehavior.tick(agent);
                break;
            case IDLE:
            default:
                break;
        }
    }

    private void chooseOrRefreshTarget(ZombieAgent agent) {
        if (!agent.checkAndResetSkillCooldown("TARGET_SCAN", ConfigManager.getInstance().getTargetingScanCooldownMs())) {
            return;
        }

        Zombie zombie = agent.getZombie();
        if (!zombie.isValid()) {
            return;
        }

        LivingEntity current = agent.getTargetEntity();
        if (current != null && current.isValid() && !current.isDead() && current.getWorld().equals(zombie.getWorld())) {
            agent.lockPursuitOn(current, 1200L);
        }
        LivingEntity best = null;
        UUID plannedId = plannedTargets.get(agent.getUuid());
        if (plannedId != null) {
            org.bukkit.entity.Player planned = org.bukkit.Bukkit.getPlayer(plannedId);
            if (planned != null && planned.isValid() && !planned.isDead() && planned.getWorld().equals(zombie.getWorld())) {
                best = planned;
            }
        }

        if (best == null) {
            double maxRange = Math.min(ConfigManager.getInstance().getTargetingMaxRange(), ConfigManager.getInstance().getHiveMindSensorRange());
            double rangeSq = maxRange * maxRange;
            double currentScore = current != null && current.isValid() && current.getWorld().equals(zombie.getWorld())
                ? evaluateTargetScore(zombie, current)
                : Double.NEGATIVE_INFINITY;

            double bestScore = Double.NEGATIVE_INFINITY;
            for (Player player : zombie.getWorld().getPlayers()) {
                if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                if (player.getLocation().distanceSquared(zombie.getLocation()) > rangeSq) {
                    continue;
                }
                double score = evaluateTargetScore(zombie, player);
                if (score > bestScore) {
                    bestScore = score;
                    best = player;
                }
            }

            if (best == null) {
                return;
            }

            double delta = ConfigManager.getInstance().getTargetingSwitchScoreDelta();
            if (current != null && current.isValid() && !best.equals(current) && bestScore < currentScore + delta) {
                return;
            }
        }

        if (current != null && current.isValid() && !current.isDead() && current.getWorld().equals(zombie.getWorld())) {
            if (agent.isPursuitLockedOn(current.getUniqueId()) && !best.getUniqueId().equals(current.getUniqueId())) {
                return;
            }
        }

        if (current != null && current.isValid() && !current.isDead() && current.getWorld().equals(zombie.getWorld()) && !best.equals(current)) {
            double currentDistSq = zombie.getLocation().distanceSquared(current.getLocation());
            double bestDistSq = zombie.getLocation().distanceSquared(best.getLocation());
            if (bestDistSq >= currentDistSq - 4.0) {
                return;
            }
        }

        zombie.setTarget(best);
        agent.clearInvestigationTarget();
        agent.setTargetEntity(best);
        agent.lockPursuitOn(best, 2800L);
        agent.setLastKnownTargetLocation(best.getLocation());
    }

    private double evaluateTargetScore(Zombie zombie, LivingEntity target) {
        if (!zombie.getWorld().equals(target.getWorld())) {
            return Double.NEGATIVE_INFINITY;
        }
        double distSq = zombie.getLocation().distanceSquared(target.getLocation());
        double distanceScore = 1.0 / Math.max(1.0, Math.sqrt(distSq));
        double maxHealth = target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
            ? target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()
            : 20.0;
        double healthScore = 1.0 - Math.max(0.0, Math.min(1.0, target.getHealth() / Math.max(1.0, maxHealth)));
        double lineOfSightBonus = zombie.hasLineOfSight(target) ? 0.35 : 0.0;
        int foodLevel = target instanceof Player player ? player.getFoodLevel() : 20;
        double hungerScore = target instanceof Player ? (1.0 - Math.max(0.0, Math.min(1.0, foodLevel / 20.0))) * 0.45 : 0.0;
        return distanceScore + healthScore + hungerScore + lineOfSightBonus;
    }
    
    private void scheduleAsyncPlanning() {
        if (!asyncPlanning.compareAndSet(false, true)) {
            return;
        }

        List<PlayerSnapshot> playerSnapshots = new ArrayList<>();
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
                ? player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()
                : 20.0;
            playerSnapshots.add(new PlayerSnapshot(
                player.getUniqueId(),
                player.getWorld().getUID(),
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ(),
                player.getHealth(),
                maxHealth,
                player.getFoodLevel()));
        }

        List<AgentSnapshot> agentSnapshots = new ArrayList<>(agents.size());
        for (ZombieAgent agent : agents.values()) {
            Zombie zombie = agent.getZombie();
            if (!zombie.isValid()) {
                continue;
            }
            LivingEntity current = agent.getTargetEntity();
            agentSnapshots.add(new AgentSnapshot(
                agent.getUuid(),
                zombie.getWorld().getUID(),
                zombie.getLocation().getX(),
                zombie.getLocation().getY(),
                zombie.getLocation().getZ(),
                current != null ? current.getUniqueId() : null));
        }

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(TooMuchZombies.getInstance(), () -> {
            try {
                Map<UUID, UUID> newPlans = new java.util.HashMap<>();
                double maxRange = Math.min(ConfigManager.getInstance().getTargetingMaxRange(), ConfigManager.getInstance().getHiveMindSensorRange());
                double rangeSq = maxRange * maxRange;
                for (AgentSnapshot agent : agentSnapshots) {
                    PlayerSnapshot best = null;
                    double bestScore = Double.NEGATIVE_INFINITY;
                    for (PlayerSnapshot player : playerSnapshots) {
                        if (!agent.worldId().equals(player.worldId())) {
                            continue;
                        }
                        double dx = player.x() - agent.x();
                        double dy = player.y() - agent.y();
                        double dz = player.z() - agent.z();
                        double distSq = dx * dx + dy * dy + dz * dz;
                        if (distSq > rangeSq || Math.abs(dy) > 28.0) {
                            continue;
                        }
                        double distanceScore = 1.0 / Math.max(1.0, Math.sqrt(distSq));
                        double healthScore = 1.0 - Math.max(0.0, Math.min(1.0, player.health() / Math.max(1.0, player.maxHealth())));
                        double hungerScore = (1.0 - Math.max(0.0, Math.min(1.0, player.foodLevel() / 20.0))) * 0.35;
                        double stickiness = player.uuid().equals(agent.currentTargetId()) ? 0.18 : 0.0;
                        double verticalPenalty = Math.abs(dy) * 0.015;
                        double score = distanceScore + healthScore + hungerScore + stickiness - verticalPenalty;
                        if (score > bestScore) {
                            bestScore = score;
                            best = player;
                        }
                    }
                    if (best != null) {
                        newPlans.put(agent.uuid(), best.uuid());
                    }
                }
                plannedTargets.clear();
                plannedTargets.putAll(newPlans);
            } finally {
                asyncPlanning.set(false);
            }
        });
    }

    private boolean tryRecoverStuckAgent(ZombieAgent agent) {
        Zombie zombie = agent.getZombie();
        LivingEntity target = agent.getTargetEntity() != null ? agent.getTargetEntity() : zombie.getTarget();
        if (target == null || !target.isValid() || !target.getWorld().equals(zombie.getWorld())) {
            return false;
        }

        if (agent.checkAndResetSkillCooldown("STUCK_RECOVER", ConfigManager.getInstance().getRecoveryRepathCooldownMs())) {
            agent.moveTo(target.getLocation(), 1.12);
        }

        if (agent.getTicksStuck() < ConfigManager.getInstance().getRecoveryStuckTeleportTicks()) {
            return false;
        }

        org.bukkit.util.Vector step = target.getLocation().toVector().subtract(zombie.getLocation().toVector()).setY(0);
        if (step.lengthSquared() < 0.05) {
            return false;
        }
        step.normalize().multiply(1.2);
        Location fallback = zombie.getLocation().clone().add(step);
        if (!fallback.getBlock().isPassable() || !fallback.clone().add(0, 1, 0).getBlock().isPassable()) {
            return false;
        }
        if (!fallback.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
            return false;
        }

        zombie.teleport(fallback);
        agent.resetStuckCounter();
        agent.moveTo(target.getLocation(), 1.15);
        return true;
    }

    public void alertZombie(Zombie zombie, Location location) {
        ZombieAgent agent = getAgent(zombie.getUniqueId());
        if (agent != null) {
            agent.setLastKnownTargetLocation(location);
            // 强制看向位置？
            // zombie.lookAt(location);
        }
    }

    public void killAllZombies() {
        for (ZombieAgent agent : agents.values()) {
            if (agent.getZombie().isValid()) {
                agent.getZombie().remove();
            }
        }
        agents.clear();
        breachRoles.clear();
        breachRoleLeaseUntil.clear();
    }
}
