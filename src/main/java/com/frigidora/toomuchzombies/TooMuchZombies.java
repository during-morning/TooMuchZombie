package com.frigidora.toomuchzombies;

import org.bukkit.plugin.java.JavaPlugin;

public class TooMuchZombies extends JavaPlugin {

    private static TooMuchZombies instance;
    private static com.frigidora.toomuchzombies.nms.NMSHandler nmsHandler;

    @Override
    public void onLoad() {
    }

    @Override
    public void onEnable() {
        instance = this;
        
        nmsHandler = new com.frigidora.toomuchzombies.nms.PaperNMSHandler();
        
        getLogger().info("TooMuchZombies v" + getDescription().getVersion() + " has been enabled!");
        
        // 初始化管理器
        com.frigidora.toomuchzombies.config.ConfigManager.initialize();
        com.frigidora.toomuchzombies.config.LanguageManager.initialize();
        com.frigidora.toomuchzombies.mechanics.PlayerLevelManager.initialize();
        com.frigidora.toomuchzombies.mechanics.BloodMoonManager.initialize();
        com.frigidora.toomuchzombies.mechanics.ChaosManager.initialize();
        com.frigidora.toomuchzombies.mechanics.PhantomManager.initialize();
        com.frigidora.toomuchzombies.mechanics.LightSourceManager.initialize();
        com.frigidora.toomuchzombies.mechanics.BeaconManager.initialize();
        com.frigidora.toomuchzombies.mechanics.TemporaryBlockManager.initialize();
        com.frigidora.toomuchzombies.mechanics.ZombieFactory.loadConfig();
        com.frigidora.toomuchzombies.ai.ZombieAIManager.initialize();
        
        // 注册命令
        getCommand("za").setExecutor(new com.frigidora.toomuchzombies.commands.ZACommand());
        
        // 注册监听器
        getServer().getPluginManager().registerEvents(new com.frigidora.toomuchzombies.listeners.GameEventListener(), this);
        getServer().getPluginManager().registerEvents(new com.frigidora.toomuchzombies.listeners.BeaconListener(), this);
        getServer().getPluginManager().registerEvents(new com.frigidora.toomuchzombies.listeners.WorldListener(), this);
    }

    @Override
    public void onDisable() {
        if (com.frigidora.toomuchzombies.mechanics.BeaconManager.getInstance() != null) {
            com.frigidora.toomuchzombies.mechanics.BeaconManager.getInstance().saveBeacons();
        }
        if (com.frigidora.toomuchzombies.mechanics.PlayerLevelManager.getInstance() != null) {
            com.frigidora.toomuchzombies.mechanics.PlayerLevelManager.getInstance().saveStats();
        }
        getLogger().info("TooMuchZombies has been disabled!");
    }

    public static TooMuchZombies getInstance() {
        return instance;
    }

    public static com.frigidora.toomuchzombies.nms.NMSHandler getNMSHandler() {
        return nmsHandler;
    }

    private static java.util.Map<java.util.UUID, org.bukkit.entity.Player> fakePlayers = new java.util.HashMap<>();

    public static org.bukkit.entity.Player getFakePlayer(org.bukkit.World world) {
        // Simple fake player implementation or return null if strict check is not needed
        // For now, we return null which might cause issues with some protection plugins,
        // but creating a full CraftPlayer is complex without NMS.
        // A better approach for simple plugins is to find a nearby real player or operator.
        // Or use a library.
        // To avoid compilation error in ZombieBreakerBehavior, we return a mock or null.
        // Ideally, we should implement a proper FakePlayer factory.
        return null; 
    }
}
