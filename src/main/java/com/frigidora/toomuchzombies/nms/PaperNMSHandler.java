package com.frigidora.toomuchzombies.nms;

import java.util.Comparator;
import java.util.EnumSet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.potion.PotionEffect;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieBehaviorGoal;

public class PaperNMSHandler implements NMSHandler {

    @Override
    public void injectCustomAI(Zombie zombie) {
        // 使用 Paper MobGoals API 注入自定义 AI 目标
        Bukkit.getMobGoals().addGoal(zombie, 2, new ZombieBehaviorGoal(zombie));
    }

    public void injectChaosTarget(Zombie zombie) {
        // 添加一个攻击其他僵尸的目标
        Bukkit.getMobGoals().addGoal(zombie, 1, new ChaosTargetGoal(zombie));
    }

    @Override
    public void moveTo(Zombie zombie, Location location, double speed) {
        // 使用 Paper Pathfinder API 移动
        double speedR=speed;
        if(speed<0.0) {speedR=0.0;}
        else if(speed>1.0) {
            speedR=1.0;
            zombie.addPotionEffect(new PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 10, 1));
        }
        zombie.getPathfinder().moveTo(location, speedR);
    }

    @Override
    public void breakBlockAnimation(int entityId, Location blockLocation, int stage) {
        // 使用 entityId 作为稳定来源，降低多僵尸并发时的裂纹覆盖冲突
        int safeSourceId = entityId == 0 ? Integer.MIN_VALUE : entityId;
        float progress = stage < 0 ? 0.0f : Math.max(0.0f, Math.min(1.0f, stage / 9.0f));

        for (Player p : blockLocation.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(blockLocation) > 64 * 64) {
                continue;
            }
            p.sendBlockDamage(blockLocation, progress, safeSourceId);
        }
    }

    @Override
    public void setAggressive(Zombie zombie, boolean aggressive) {
        // 设置僵尸是否具有意识（主动攻击）
        zombie.setAware(aggressive);
    }
    
    // 内部混乱目标
    public static class ChaosTargetGoal implements Goal<Zombie> {
        private final Zombie zombie;
        private final GoalKey<Zombie> key;
        
        public ChaosTargetGoal(Zombie zombie) {
            this.zombie = zombie;
            this.key = GoalKey.of(Zombie.class, new NamespacedKey(TooMuchZombies.getInstance(), "chaos_target"));
        }
        
        @Override
        public boolean shouldActivate() {
            return zombie.getTarget() == null || !(zombie.getTarget() instanceof Zombie);
        }
        
        @Override
        public void tick() {
            // Find nearest zombie
            Zombie nearest = zombie.getWorld().getEntitiesByClass(Zombie.class).stream()
                .filter(z -> !z.equals(zombie))
                .filter(z -> z.getLocation().distanceSquared(zombie.getLocation()) < 256) // 16 blocks
                .min(Comparator.comparingDouble(z -> z.getLocation().distanceSquared(zombie.getLocation())))
                .orElse(null);
            
            if (nearest != null) {
                zombie.setTarget(nearest);
            }
        }
        
        @Override
        public GoalKey<Zombie> getKey() {
            return key;
        }
        
        @Override
        public EnumSet<GoalType> getTypes() {
            return EnumSet.of(GoalType.TARGET);
        }
    }
}
