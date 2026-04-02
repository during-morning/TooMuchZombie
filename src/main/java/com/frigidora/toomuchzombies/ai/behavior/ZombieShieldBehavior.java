package com.frigidora.toomuchzombies.ai.behavior;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import com.frigidora.toomuchzombies.ai.ZombieAgent;

/**
 * 僵尸盾牌行为 - 从 ZombieGame 移植（简化版）
 * 
 * 由于 Bukkit API 限制，无法完全控制僵尸使用盾牌
 * 这里主要实现威胁检测逻辑，为未来的 NMS 实现做准备
 */
public class ZombieShieldBehavior {
    
    private final ZombieAgent agent;
    private final Zombie zombie;
    
    private int coolTime = 0;
    private boolean shouldUseShield = false;
    private int useTime = 0;
    
    public ZombieShieldBehavior(ZombieAgent agent) {
        this.agent = agent;
        this.zombie = agent.getZombie();
    }

    
    /**
     * 每 tick 更新
     */
    public void tick() {
        coolTime--;
        useTime--;
        
        // 检查是否需要使用盾牌
        LivingEntity target = zombie.getTarget();
        if (target != null && target.isValid()) {
            shouldUseShield = needToUseShield(target);
        } else {
            shouldUseShield = false;
        }
    }

    
    /**
     * 检查是否可以使用盾牌
     */
    public boolean canUseShield() {
        if (!agent.canUseShield()) {
            return false;
        }
        
        ItemStack mainHand = zombie.getEquipment().getItemInMainHand();
        ItemStack offHand = zombie.getEquipment().getItemInOffHand();
        
        return mainHand.getType() == Material.SHIELD || offHand.getType() == Material.SHIELD;
    }

    
    /**
     * 检查是否需要使用盾牌 - 从 ZombieShieldHelpingGoal 移植
     */
    private boolean needToUseShield(LivingEntity target) {
        if (!zombie.hasLineOfSight(target)) {
            return false;
        }
        
        ItemStack mainHand = target.getEquipment().getItemInMainHand();
        ItemStack offHand = target.getEquipment().getItemInOffHand();
        
        return checkIfThreatening(target, mainHand) || checkIfThreatening(target, offHand);
    }

    
    /**
     * 检查物品是否具有威胁 - 从 ZombieGame 移植
     */
    private boolean checkIfThreatening(LivingEntity enemy, ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return false;
        }
        
        Material type = itemStack.getType();
        
        // 弓
        if (type == Material.BOW) {
            if (enemy instanceof Player) {
                Player player = (Player) enemy;
                if (player.isHandRaised()) {
                    useTime = 20;
                    return true;
                }
            }
        }
        
        // 弩
        else if (type == Material.CROSSBOW) {
            if (enemy instanceof Player) {
                Player player = (Player) enemy;
                if (player.isHandRaised()) {
                    useTime = 20;
                    return true;
                }
            }
        }
        
        // 三叉戟
        else if (type == Material.TRIDENT) {
            if (enemy instanceof Player) {
                Player player = (Player) enemy;
                if (player.isHandRaised()) {
                    useTime = 20;
                    return true;
                }
            }
        }
        
        // 近战武器（高耐久度）
        else if (zombie.getLocation().distance(enemy.getLocation()) <= 5.0) {
            if (itemStack.getType().getMaxDurability() >= 3) {
                useTime = 20;
                return true;
            }
        }
        
        return false;
    }

    
    public boolean shouldUseShield() {
        return shouldUseShield && canUseShield();
    }
}
