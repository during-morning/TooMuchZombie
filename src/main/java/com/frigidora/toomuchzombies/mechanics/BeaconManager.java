package com.frigidora.toomuchzombies.mechanics;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import com.frigidora.toomuchzombies.TooMuchZombies;

/**
 * 信标防御系统管理器
 * 负责追踪信标位置，检测激活状态，并提供查询接口。
 */
public class BeaconManager {

    private static BeaconManager instance;
    private final Set<Location> trackedBeacons = Collections.synchronizedSet(new HashSet<>());
    private final Set<Location> activeBeacons = Collections.synchronizedSet(new HashSet<>());
    
    private File beaconsFile;
    private FileConfiguration beaconsConfig;
    
    private BeaconManager() {
        loadBeacons();
        startTask();
    }
    
    public static void initialize() {
        if (instance == null) {
            instance = new BeaconManager();
        }
    }
    
    public static BeaconManager getInstance() {
        return instance;
    }
    
    /**
     * 添加一个新的信标位置进行追踪
     */
    public void addBeacon(Location loc) {
        trackedBeacons.add(loc);
        saveBeaconsAsync();
        // 立即检查一次状态
        checkBeaconStatus(loc);
    }
    
    /**
     * 移除一个信标位置
     */
    public void removeBeacon(Location loc) {
        trackedBeacons.remove(loc);
        activeBeacons.remove(loc);
        saveBeaconsAsync();
    }
    
    /**
     * 获取指定位置附近的最近活跃信标
     * @param loc 查询位置
     * @param maxDistance 最大搜索距离
     * @return 最近的活跃信标位置，如果没有则返回 null
     */
    public Location getNearestActiveBeacon(Location loc, double maxDistance) {
        if (activeBeacons.isEmpty()) return null;
        
        Location nearest = null;
        double minDstSq = maxDistance * maxDistance;
        
        // 遍历活跃信标
        // 注意：这里需要线程安全，因为 activeBeacons 可能在异步更新
        synchronized (activeBeacons) {
            for (Location beaconLoc : activeBeacons) {
                if (!beaconLoc.getWorld().equals(loc.getWorld())) continue;
                
                double dstSq = beaconLoc.distanceSquared(loc);
                if (dstSq < minDstSq) {
                    minDstSq = dstSq;
                    nearest = beaconLoc;
                }
            }
        }
        
        return nearest;
    }
    
    /**
     * 检查某个位置是否在任意活跃信标的范围内
     */
    public boolean isNearActiveBeacon(Location loc, double range) {
        return getNearestActiveBeacon(loc, range) != null;
    }
    
    // --- 内部逻辑 ---
    
    private void startTask() {
        // 每 100 ticks (5秒) 检查一次所有追踪信标的状态
        new BukkitRunnable() {
            @Override
            public void run() {
                updateAllBeacons();
            }
        }.runTaskTimer(TooMuchZombies.getInstance(), 100L, 100L);
    }
    
    private void updateAllBeacons() {
        // 创建副本以避免并发修改异常
        List<Location> toCheck;
        synchronized (trackedBeacons) {
            toCheck = new ArrayList<>(trackedBeacons);
        }
        
        for (Location loc : toCheck) {
            checkBeaconStatus(loc);
        }
    }
    
    private void checkBeaconStatus(Location loc) {
        // 这是一个同步操作，因为涉及到读取 BlockState
        // 为了性能，我们仅在主线程调用此方法 (runTaskTimer 默认在主线程)
        
        World world = loc.getWorld();
        if (world == null) return; // 世界可能未加载
        
        // 检查区块是否加载，如果未加载则跳过，避免强制加载区块造成卡顿
        if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return; 
        }
        
        Block block = loc.getBlock();
        BlockState state = block.getState(false); // false = 不强制刷新快照
        
        if (state instanceof Beacon) {
            Beacon beacon = (Beacon) state;
            
            // 判定标准：等级 > 0 (有金字塔结构) 且 有主效果 (已激活)
            // 注意：getPrimaryEffect() 在某些版本可能为 null 即使已激活，需结合 getTier
            // 这里我们要求必须有层级
            if (beacon.getTier() > 0) {
                activeBeacons.add(loc);
            } else {
                activeBeacons.remove(loc);
            }
        } else {
            // 方块不再是信标（可能被外部插件移除，或者破坏事件未被捕获）
            // 从追踪列表中移除
            // 注意：在迭代中移除是不安全的，这里只是更新 activeBeacons
            // 真正的清理将在下次迭代或通过监听器处理
            // 为安全起见，我们仅标记为非活跃
            activeBeacons.remove(loc);
        }
    }
    
    // --- 持久化 ---
    
    private void loadBeacons() {
        beaconsFile = new File(TooMuchZombies.getInstance().getDataFolder(), "beacons.yml");
        if (!beaconsFile.exists()) {
            return;
        }
        
        beaconsConfig = YamlConfiguration.loadConfiguration(beaconsFile);
        List<String> list = beaconsConfig.getStringList("beacons");
        
        for (String s : list) {
            try {
                Location loc = deserializeLocation(s);
                if (loc != null) {
                    trackedBeacons.add(loc);
                }
            } catch (Exception e) {
                TooMuchZombies.getInstance().getLogger().warning("无法加载信标位置: " + s);
            }
        }
    }
    
    public void saveBeacons() {
        if (beaconsFile == null) {
            beaconsFile = new File(TooMuchZombies.getInstance().getDataFolder(), "beacons.yml");
        }
        
        if (beaconsConfig == null) {
            beaconsConfig = new YamlConfiguration();
        }
        
        List<String> list = new ArrayList<>();
        synchronized (trackedBeacons) {
            for (Location loc : trackedBeacons) {
                list.add(serializeLocation(loc));
            }
        }
        
        beaconsConfig.set("beacons", list);
        
        try {
            beaconsConfig.save(beaconsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void saveBeaconsAsync() {
        new BukkitRunnable() {
            @Override
            public void run() {
                saveBeacons();
            }
        }.runTaskAsynchronously(TooMuchZombies.getInstance());
    }
    
    private String serializeLocation(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
    
    private Location deserializeLocation(String s) {
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null; // 世界不存在
        
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);
        
        return new Location(world, x, y, z);
    }
}
