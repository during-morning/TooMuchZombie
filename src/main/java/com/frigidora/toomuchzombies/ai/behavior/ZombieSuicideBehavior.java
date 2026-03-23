package com.frigidora.toomuchzombies.ai.behavior;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Zombie;
import org.bukkit.util.Vector;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;
import com.frigidora.toomuchzombies.enums.ZombieRole;

public class ZombieSuicideBehavior {
    
    private final ZombieAgent agent;
    private final Zombie zombie;
    
    private Location breachTarget;
    private long lastCheckTime;
    private boolean isCharging;
    private long chargeStartTime;

    public ZombieSuicideBehavior(ZombieAgent agent) {
        this.agent = agent;
        this.zombie = agent.getZombie();
    }

    public boolean isActive() {
        return isCharging;
    }

    public void tick() {
        if (agent.getRole() != ZombieRole.SUICIDE) return;

        // 信标范围内禁止技能（包括自爆冲锋）。
        if (com.frigidora.toomuchzombies.mechanics.BeaconManager.getInstance().isNearActiveBeacon(zombie.getLocation(), 24.0)) {
            isCharging = false;
            breachTarget = null;
            return;
        }

        // 如果已经在冲锋中
        if (isCharging) {
            if (breachTarget == null) {
                isCharging = false;
                return;
            }
            
            // 检查距离和世界
            if (!zombie.getWorld().equals(breachTarget.getWorld())) {
                isCharging = false;
                breachTarget = null;
                return;
            }
            
            if (zombie.getLocation().distanceSquared(breachTarget) < 4.0) { // < 2 blocks
                // 抵达目标，引爆！
                detonate();
                return;
            }

            if (System.currentTimeMillis() - chargeStartTime > 5000L) {
                isCharging = false;
                breachTarget = null;
                return;
            }
            
            // 持续向目标冲锋
            if (TooMuchZombies.getNMSHandler() != null) {
                double speed = 1.8;
                // 白天减速：速度减小 70%
                long time = zombie.getWorld().getTime();
                if (time >= 0 && time < 12000) {
                    speed *= 0.3;
                }
                TooMuchZombies.getNMSHandler().moveTo(zombie, breachTarget, speed); // 极快速度
            }
            
            // 粒子效果提示
            try {
                org.bukkit.Particle particle = org.bukkit.Particle.valueOf("SMOKE_LARGE");
                zombie.getWorld().spawnParticle(particle, zombie.getLocation().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0);
            } catch (IllegalArgumentException e) {
                // Fallback for newer versions if name changed, though SMOKE_LARGE is standard
                 try {
                    zombie.getWorld().spawnParticle(org.bukkit.Particle.valueOf("LARGE_SMOKE"), zombie.getLocation().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0);
                 } catch (Exception ignored) {}
            }
            return;
        }

        // 定期检查是否有爆破请求 (每 0.5 秒)
        if (System.currentTimeMillis() - lastCheckTime > 350) {
            lastCheckTime = System.currentTimeMillis();
            
            // 优先消费较远的爆破请求，避免 TNT 单位“看得见但接不到”。
            Location request = ZombieAIManager.getInstance().getNearestBreachRequest(zombie.getLocation(), 56.0);
            if (request == null) {
                request = findLocalBreachPoint();
            }
            if (request != null) {
                // 接受任务
                this.breachTarget = request;
                this.isCharging = true;
                this.chargeStartTime = System.currentTimeMillis();
                ZombieAIManager.getInstance().fulfillBreachRequest(request); // 标记为已接单
                
                // 发出声音
                zombie.getWorld().playSound(zombie.getLocation(), org.bukkit.Sound.ENTITY_TNT_PRIMED, 1.0f, 1.0f);
            }
        }
    }

    private Location findLocalBreachPoint() {
        org.bukkit.entity.LivingEntity target = agent.getTargetEntity() != null ? agent.getTargetEntity() : zombie.getTarget();
        if (target == null || !target.isValid() || !target.getWorld().equals(zombie.getWorld())) {
            return null;
        }

        Vector toward = target.getLocation().toVector().subtract(zombie.getLocation().toVector()).setY(0);
        if (toward.lengthSquared() < 0.05) {
            toward = zombie.getLocation().getDirection().setY(0);
        }
        if (toward.lengthSquared() < 0.05) {
            return null;
        }
        toward.normalize();

        Block feet = zombie.getLocation().add(toward.clone().multiply(1.0)).getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        if (feet.getType().isSolid()) {
            return feet.getLocation().add(0.5, 0.5, 0.5);
        }
        if (head.getType().isSolid()) {
            return head.getLocation().add(0.5, 0.5, 0.5);
        }
        return null;
    }

    private void detonate() {
        // 信标保护检查：禁止在活跃信标 50 格内破坏方块
        if (com.frigidora.toomuchzombies.mechanics.BeaconManager.getInstance().isNearActiveBeacon(zombie.getLocation(), 50.0)) {
            // 哑火效果
            try {
                zombie.getWorld().spawnParticle(org.bukkit.Particle.valueOf("SMOKE"), zombie.getLocation(), 20, 0.5, 0.5, 0.5, 0.05);
            } catch (Exception e) {
                try {
                    zombie.getWorld().spawnParticle(org.bukkit.Particle.valueOf("SMOKE_NORMAL"), zombie.getLocation(), 20, 0.5, 0.5, 0.5, 0.05);
                } catch (Exception ignored) {}
            }
            zombie.getWorld().playSound(zombie.getLocation(), org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
            zombie.setHealth(0);
            zombie.remove();
            return;
        }

        zombie.getWorld().createExplosion(zombie.getLocation(), 2.8F, false, true);
        zombie.setHealth(0);
        zombie.remove(); // 彻底移除，触发死亡事件
    }
}
