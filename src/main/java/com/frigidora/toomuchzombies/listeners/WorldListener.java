package com.frigidora.toomuchzombies.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.enums.ZombieRole;
import com.frigidora.toomuchzombies.mechanics.PhantomManager;
import com.frigidora.toomuchzombies.mechanics.ZombieFactory;

public class WorldListener implements Listener {

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Zombie) {
                ZombieAIManager.getInstance().unregisterZombie(entity.getUniqueId());
            } else if (entity instanceof Phantom) {
                // PhantomManager 使用 EntitySpawnEvent 和 EntityDeathEvent 以及 Cache 自动清理
                // 但为了保险，我们可以手动移除（需要 PhantomManager 提供方法，或者让 cache 自动过期）
                // 目前 PhantomManager 会在 tick 中清理 !isValid() 的实体，
                // ChunkUnload 后 isValid() 为 false，所以会被自动清理。
                // 这里暂时不需要手动操作，除非需要立即释放内存。
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // 延迟 1 tick 执行，确保实体已完全加载
        com.frigidora.toomuchzombies.TooMuchZombies.getInstance().getServer().getScheduler().runTask(
            com.frigidora.toomuchzombies.TooMuchZombies.getInstance(), () -> {
                if (!event.getChunk().isLoaded()) return;

                for (Entity entity : event.getChunk().getEntities()) {
                    if (entity instanceof Zombie) {
                        Zombie zombie = (Zombie) entity;
                        if (ZombieAIManager.getInstance().getAgent(zombie.getUniqueId()) == null) {
                            restoreOrAssignRole(zombie);
                        }
                    } else if (entity instanceof Phantom) {
                        PhantomManager.getInstance().registerPhantom((Phantom) entity);
                    }
                }
            });
    }

    private void restoreOrAssignRole(Zombie zombie) {
        // 尝试从名字解析角色和等级
        String name = zombie.getCustomName();
        if (name != null && name.contains("(Lv.")) {
            try {
                // 格式: ROLE (Lv.X)
                String roleName = name.substring(0, name.indexOf(" ("));
                String levelStr = name.substring(name.indexOf("Lv.") + 3, name.indexOf(")"));
                
                ZombieRole role = ZombieRole.valueOf(roleName);
                int level = Integer.parseInt(levelStr);
                
                // 重新注册，但不重新装备（假设装备还在）
                // 注意：registerZombie 只负责注册 AI，不负责装备
                ZombieAIManager.getInstance().registerZombie(zombie, role, level);
                
                // 注入 NMS AI
                if (com.frigidora.toomuchzombies.TooMuchZombies.getNMSHandler() != null) {
                    com.frigidora.toomuchzombies.TooMuchZombies.getNMSHandler().injectCustomAI(zombie);
                }
                return;
            } catch (Exception e) {
                // 解析失败，回退到重新分配
            }
        }
        
        // 如果没有名字或解析失败，重新分配角色
        ZombieFactory.assignRole(zombie);
    }
}
