package com.frigidora.toomuchzombies.listeners;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import io.papermc.paper.event.block.BeaconActivatedEvent;
import com.frigidora.toomuchzombies.mechanics.BeaconManager;

/**
 * 监听信标的放置和破坏，以实时更新 BeaconManager
 */
public class BeaconListener implements Listener {

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() == Material.BEACON) {
            BeaconManager.getInstance().addBeacon(event.getBlock().getLocation());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.BEACON) {
            BeaconManager.getInstance().removeBeacon(event.getBlock().getLocation());
        }
    }

    @EventHandler
    public void onBeaconActivated(BeaconActivatedEvent event) {
        BeaconManager.getInstance().addBeacon(event.getBlock().getLocation());
    }
}
