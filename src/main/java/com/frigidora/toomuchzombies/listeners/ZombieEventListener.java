package com.frigidora.toomuchzombies.listeners;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import com.frigidora.toomuchzombies.TooMuchZombies;
import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.ai.ZombieAgent;

/**
 * 僵尸事件监听器 - 从 ZombieGame 移植
 * 
 * 监听僵尸的受伤和死亡事件，触发群体响应
 */
public class ZombieEventListener implements Listener {
    
    private final TooMuchZombies plugin;
    
    public ZombieEventListener(TooMuchZombies plugin) {
        this.plugin = plugin;
    }

    
    /**
     * 僵尸受伤事件 - 触发群体召唤
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onZombieHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Zombie)) {
            return;
        }
        
        Zombie zombie = (Zombie) event.getEntity();
        ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
        
        if (agent == null) {
            return;
        }
        
        // 触发受伤响应
        agent.onZombieHurt();
    }

    
    /**
     * 僵尸死亡事件 - 清理状态
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onZombieDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie)) {
            return;
        }
        
        Zombie zombie = (Zombie) event.getEntity();
        ZombieAgent agent = ZombieAIManager.getInstance().getAgent(zombie.getUniqueId());
        
        if (agent == null) {
            return;
        }
        
        // 触发死亡响应（停止所有行为）
        agent.onZombieHurt();
    }

    
    /**
     * 定期召唤机制 - 在攻击时定期召唤附近僵尸
     * 这个由 ZombieAIManager 的 tick 方法调用
     */
    public static void tickPeriodicCall(ZombieAgent agent) {
        if (agent == null || agent.getZombie() == null) {
            return;
        }
        
        LivingEntity target = agent.getZombie().getTarget();
        if (target == null || !target.isValid()) {
            return;
        }
        
        // 每 20 秒召唤一次（400 ticks）
        long now = System.currentTimeMillis();
        if (agent.checkAndResetSkillCooldown("PERIODIC_CALL", 20000L)) {
            agent.callToAttack(target);
        }
    }
}
