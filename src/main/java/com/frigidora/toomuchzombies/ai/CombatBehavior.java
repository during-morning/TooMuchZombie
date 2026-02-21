package com.frigidora.toomuchzombies.ai;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

public class CombatBehavior {

    public void tick(ZombieAgent agent) {
        if (agent.getLastKnownTargetLocation() == null) return;
        
        Location targetLoc = agent.getLastKnownTargetLocation();
        if (!agent.getZombie().getWorld().equals(targetLoc.getWorld())) return;
        
        double distanceSq = agent.getZombie().getLocation().distanceSquared(targetLoc);
        
        // 强制看向目标
        // agent.getZombie().lookAt(targetLoc); // 需要 Paper API 或 NMS。
        // 我们将通过计算投射物的速度来模拟瞄准。

        switch (agent.getRole()) {
            case ARCHER:
                // 强制手持弓
                if (agent.getZombie().getEquipment().getItemInMainHand().getType() != Material.BOW) {
                    agent.getZombie().getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                }
                if (distanceSq < 256 && agent.checkAndResetSkillCooldown("SHOOT", 3000)) { 
                    shootArrow(agent, targetLoc);
                }
                break;
            case RUSHER:
                 // 强制手持 TNT (仅视觉，或者作为技能标识)
                 if (agent.getZombie().getEquipment().getItemInMainHand().getType() != Material.TNT) {
                     agent.getZombie().getEquipment().setItemInMainHand(new ItemStack(Material.TNT));
                 }
                 // TNT 僵尸：冷却 20秒 (20000ms)，自伤，高级 TNT
                 if (distanceSq < 400 && agent.checkAndResetSkillCooldown("TNT", 20000)) { 
                    throwTNT(agent, targetLoc);
                }
                break;
            case SUICIDE:
                if (distanceSq < 9) { // 减小触发距离 (< 3格)
                    explode(agent);
                }
                break;
            case NURSE:
                 // 强制手持药水
                 if (agent.getZombie().getEquipment().getItemInMainHand().getType() != Material.SPLASH_POTION) {
                     ItemStack potion = new ItemStack(Material.SPLASH_POTION);
                     PotionMeta meta = (PotionMeta) potion.getItemMeta();
                     if (meta != null) {
                         meta.setBasePotionType(PotionType.HARMING);
                         potion.setItemMeta(meta);
                     }
                     agent.getZombie().getEquipment().setItemInMainHand(potion);
                 }
                 if (distanceSq < 100 && agent.checkAndResetSkillCooldown("POTION", 8000)) { 
                    throwPotion(agent, targetLoc);
                 }
                 break;
             case ENDER:
                 // 强制手持末影珍珠
                 if (agent.getZombie().getEquipment().getItemInMainHand().getType() != Material.ENDER_PEARL) {
                     agent.getZombie().getEquipment().setItemInMainHand(new ItemStack(Material.ENDER_PEARL));
                 }
                 if (distanceSq > 100 && agent.checkAndResetSkillCooldown("PEARL", 10000)) { 
                     throwPearl(agent, targetLoc);
                 }
                 break;
             case MINER:
                 // 强制手持镐子
                 if (!agent.getZombie().getEquipment().getItemInMainHand().getType().name().contains("PICKAXE")) {
                     agent.getZombie().getEquipment().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
                 }
                 break;
             case COMBAT:
                 maintainCombatLoadout(agent);
                 handleShieldCombat(agent, distanceSq);
                 break;
             default:
                 break;
        }
    }

    private void maintainCombatLoadout(ZombieAgent agent) {
        Zombie zombie = agent.getZombie();
        if (!zombie.getEquipment().getItemInMainHand().getType().name().endsWith("SWORD")) {
            zombie.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        }
        if (zombie.getEquipment().getItemInOffHand().getType() != Material.SHIELD) {
            zombie.getEquipment().setItemInOffHand(new ItemStack(Material.SHIELD));
        }
    }

    private void handleShieldCombat(ZombieAgent agent, double distanceSq) {
        Zombie zombie = agent.getZombie();
        LivingEntity target = getCurrentTarget(agent);
        if (target == null || !target.isValid() || target.isDead()) {
            return;
        }

        if (agent.wasDamagedRecently(900) && agent.checkAndResetSkillCooldown("SHIELD_GUARD", 2600)) {
            agent.activateShieldGuard(800);
            zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, 20, 1, true, false, false));
            zombie.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 20, 1, true, false, false));
            zombie.swingOffHand();
            zombie.getWorld().playSound(zombie.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 0.9f, 1.0f);
        }

        if (distanceSq <= 9.0 && agent.checkAndResetSkillCooldown("SHIELD_BASH", 3200)) {
            Vector kb = target.getLocation().toVector().subtract(zombie.getLocation().toVector()).setY(0).normalize().multiply(0.7).setY(0.2);
            target.setVelocity(target.getVelocity().add(kb));
            target.damage(2.0, zombie);
            zombie.getWorld().playSound(target.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 0.8f, 0.8f);
        }
    }

    private LivingEntity getCurrentTarget(ZombieAgent agent) {
        LivingEntity fromMemory = agent.getTargetEntity();
        if (fromMemory != null && fromMemory.isValid()) {
            return fromMemory;
        }
        if (agent.getZombie().getTarget() instanceof LivingEntity) {
            return (LivingEntity) agent.getZombie().getTarget();
        }
        return null;
    }

    private void shootArrow(ZombieAgent agent, Location target) {
        Zombie z = agent.getZombie();
        Vector velocity = calculateTrajectory(z.getEyeLocation(), target, 1.6); // 箭矢速度通常在 1.6 - 3.0 之间
        // 根据要求调整速度（之前要求 0.8 倍率，但为了准确性我们需要一致的速度）
        // 让我们使用一个“自然”的固定速度，如果需要可以慢一点。
        // 用户要求“朝向玩家的箭（方向 + 速度）”。
        // 标准箭矢速度大约在 1.6（弓未完全拉满）到 3.0（完全拉满）之间。
        // 我们使用 1.6 作为基础速度。
        
        z.launchProjectile(Arrow.class, velocity);
    }

    private Vector calculateTrajectory(Location from, Location to, double speed) {
        // 基础物理：d = v*t + 0.5*g*t^2
        // 我们需要找到 v（速度向量）。
        // 在没有固定角度或固定时间的情况下，完美求解很复杂。
        // 简化方法：根据距离稍微瞄准目标上方。
        
        Vector dir = to.clone().subtract(from).toVector();
        double distance = dir.length();
        
        // 重力补偿（近似值）
        // 箭矢重力大约是 0.05 blocks/tick^2？
        // 让我们直接根据距离向上调整仰角。
        double pitchAdjustment = distance * 0.02; // 调整此值
        
        dir.normalize();
        dir.setY(dir.getY() + pitchAdjustment);
        dir.normalize().multiply(speed);
        
        return dir;
    }

    private void throwTNT(ZombieAgent agent, Location target) {
        Zombie z = agent.getZombie();
        
        // 如果可能，瞄准玩家附近的防御工事（高硬度方块）
        Location bestTarget = target;
        double highestHardness = 0;
        
        // 扫描玩家周围 5x3x5 的区域
        for (int x = -2; x <= 2; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int zOffset = -2; zOffset <= 2; zOffset++) {
                    org.bukkit.block.Block b = target.clone().add(x, y, zOffset).getBlock();
                    if (b.getType().isSolid()) {
                        double hardness = b.getType().getHardness();
                        if (hardness > highestHardness) {
                            highestHardness = hardness;
                            bestTarget = b.getLocation().add(0.5, 0.5, 0.5);
                        }
                    }
                }
            }
        }
        
        // 如果我们找到了坚硬的方块（如黑曜石/石砖），就瞄准那里。
        // 否则瞄准玩家。
        
        // 自伤：每扔出一个 TNT，自身受到 10 点爆炸伤害
        z.damage(10.0);
        z.getWorld().playSound(z.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);

        TNTPrimed tnt = z.getWorld().spawn(z.getEyeLocation(), TNTPrimed.class);
        Vector direction = calculateVelocity(z.getEyeLocation(), bestTarget);
        tnt.setVelocity(direction);
        
        // 30% 概率升级为高级 TNT
        boolean isAdvanced = Math.random() < 0.3;
        
        tnt.setFuseTicks(isAdvanced ? 20 : 40); // 高级 TNT 爆炸更快 (1s vs 2s)
        tnt.setYield(isAdvanced ? 4.0f : 2.5f); // 高级 TNT 威力更大 (4.0 vs 2.5)
        if (isAdvanced) {
            tnt.setGlowing(true); // 高亮显示
            tnt.setCustomName(org.bukkit.ChatColor.RED + "Advanced TNT");
            tnt.setCustomNameVisible(true);
        }
        
        // 添加元数据以识别僵尸的 TNT
        tnt.setMetadata("ZombieTNT", new org.bukkit.metadata.FixedMetadataValue(com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), true));
    }
    
    private void throwPotion(ZombieAgent agent, Location target) {
        Zombie z = agent.getZombie();
        
        // 1. 寻找受伤的友军
        Zombie injuredFriend = null;
        double minHealthPct = 1.0;
        
        for (org.bukkit.entity.Entity e : z.getNearbyEntities(10, 5, 10)) {
            if (e instanceof Zombie && !e.equals(z)) {
                Zombie friend = (Zombie) e;
                double max = friend.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
                double current = friend.getHealth();
                double pct = current / max;
                if (pct < 0.8 && pct < minHealthPct) {
                    minHealthPct = pct;
                    injuredFriend = friend;
                }
            }
        }
        
        Location throwTarget;
        PotionType potionType;
        
        if (injuredFriend != null) {
            // 治疗友军
            throwTarget = injuredFriend.getEyeLocation();
            potionType = PotionType.HEALING; // 或者 STRENGTH/REGENERATION
            // 20% 概率给力量
            if (Math.random() < 0.2) potionType = PotionType.STRENGTH;
        } else {
            // 攻击敌人
            throwTarget = target;
            potionType = PotionType.HARMING;
            // 如果距离太远，不投掷攻击药水
             if (!z.getWorld().equals(target.getWorld()) || z.getLocation().distanceSquared(target) > 100) return;
        }

        Vector direction = calculateVelocity(z.getEyeLocation(), throwTarget);
        ThrownPotion potion = z.launchProjectile(ThrownPotion.class, direction);
        
        ItemStack item = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        if (meta != null) {
            meta.setBasePotionType(potionType);
            item.setItemMeta(meta);
            potion.setItem(item);
        }
    }
    
    private void throwPearl(ZombieAgent agent, Location target) {
        agent.incrementTeleportCount();
        if (agent.getTeleportCount() >= 10) {
            agent.getZombie().setHealth(0);
            return;
        }

        Zombie z = agent.getZombie();
        Vector direction = calculateVelocity(z.getEyeLocation(), target);
        z.launchProjectile(EnderPearl.class, direction);
    }

    private void explode(ZombieAgent agent) {
        // 减小爆炸范围 (从 4.0F 降至 2.5F)
        agent.getZombie().getWorld().createExplosion(agent.getZombie().getLocation(), 2.5F, false, true);
        agent.getZombie().setHealth(0);
    }
    
    private Vector calculateVelocity(Location from, Location to) {
        Vector dir = to.clone().subtract(from).toVector();
        double distance = dir.length();
        dir.normalize();
        dir.multiply(0.4); // 减小投掷速度 (从 0.8 降至 0.4)
        dir.setY(dir.getY() + (distance * 0.05)); // 弧度调整
        return dir;
    }
}
