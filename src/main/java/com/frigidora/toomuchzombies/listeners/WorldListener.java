package com.frigidora.toomuchzombies.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.mechanics.PhantomManager;

public class WorldListener implements Listener {

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Zombie) {
                Zombie zombie = (Zombie) entity;
                zombie.remove();
                ZombieAIManager.getInstance().unregisterZombie(zombie.getUniqueId());
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
                    if (entity instanceof Phantom) {
                        PhantomManager.getInstance().registerPhantom((Phantom) entity);
                    }
                }
            });
    }
}
