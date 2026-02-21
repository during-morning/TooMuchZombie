package com.frigidora.toomuchzombies.commands;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;

import com.frigidora.toomuchzombies.ai.ZombieAIManager;
import com.frigidora.toomuchzombies.config.ConfigManager;
import com.frigidora.toomuchzombies.config.LanguageManager;
import com.frigidora.toomuchzombies.enums.BreachAssignmentRole;
import com.frigidora.toomuchzombies.enums.ZombieRole;
import com.frigidora.toomuchzombies.mechanics.BloodMoonManager;
import com.frigidora.toomuchzombies.mechanics.ChaosManager;
import com.frigidora.toomuchzombies.mechanics.PlayerLevelManager;
import com.frigidora.toomuchzombies.mechanics.TemporaryBlockManager;
import com.frigidora.toomuchzombies.mechanics.ZombieFactory;

public class ZACommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        LanguageManager lm = LanguageManager.getInstance();
        
        if (args.length == 0) {
            if (!sender.hasPermission("toomuchzombies.admin")) {
                sender.sendMessage(lm.getMessage("no-permission"));
                return true;
            }
            sender.sendMessage(lm.getMessage("command-usage"));
            return true;
        }

        String sub = args[0].toLowerCase();
        
        // 权限检查
        String permission = "toomuchzombies.command." + sub;
        if (sub.equals("forcebloodmoon")) permission = "toomuchzombies.command.bloodmoon";
        if (sub.equals("forcechaos")) permission = "toomuchzombies.command.chaos";
        
        if (!sender.hasPermission(permission) && !sender.hasPermission("toomuchzombies.admin")) {
            sender.sendMessage(lm.getMessage("no-permission"));
            return true;
        }

        switch (sub) {
            case "spawn":
                handleSpawn(sender, args);
                break;
            case "info":
                handleInfo(sender);
                break;
            case "killall":
                ZombieAIManager.getInstance().killAllZombies();
                sender.sendMessage(lm.getMessage("killall-success"));
                break;
            case "forcebloodmoon":
                if (sender instanceof Player) {
                    BloodMoonManager.getInstance().startBloodMoonSequence(((Player) sender).getWorld());
                    sender.sendMessage(lm.getMessage("blood-moon-triggered"));
                } else {
                    sender.sendMessage(lm.getMessage("players-only"));
                }
                break;
            case "forcechaos":
                if (sender instanceof Player) {
                    ChaosManager.getInstance().startChaos(((Player) sender).getWorld());
                    sender.sendMessage(lm.getMessage("chaos-started"));
                } else {
                    sender.sendMessage(lm.getMessage("players-only"));
                }
                break;
            case "reload":
                ConfigManager.getInstance().reload();
                LanguageManager.getInstance().reload();
                sender.sendMessage(lm.getMessage("config-reloaded"));
                break;
            case "max":
                handleMax(sender, args);
                break;
            case "level":
                handleLevel(sender, args);
                break;
            case "getlight":
                handleGetLight(sender);
                break;
            case "debug":
                handleDebug(sender, args);
                break;
            default:
                sender.sendMessage(lm.getMessage("unknown-subcommand"));
        }
        return true;
    }

    private void handleDebug(CommandSender sender, String[] args) {
        LanguageManager lm = LanguageManager.getInstance();
        if (args.length < 2) {
            sender.sendMessage(lm.getMessage("debug-usage"));
            return;
        }

        String topic = args[1].toLowerCase();
        if ("spawn".equals(topic)) {
            sender.sendMessage(lm.getMessage("debug-spawn-title"));
            sender.sendMessage("§7rejects: §f" + ZombieFactory.getSpawnRejectStatsLine());
            return;
        }

        if ("ai".equals(topic)) {
            java.util.Map<BreachAssignmentRole, Integer> roleStats = ZombieAIManager.getInstance().getBreachRoleStats();
            java.util.Map<String, Integer> nodeHits = ZombieAIManager.getInstance().getCooperationNodeHitStats();
            java.util.Map<com.frigidora.toomuchzombies.ai.AbilityIntent, Integer> intentHits = ZombieAIManager.getInstance().getArbitrationHitStats();
            java.util.Map<String, Integer> buildFailStats = ZombieAIManager.getInstance().getBuilderFailureStats();
            java.util.Map<String, Integer> breakerRejectStats = ZombieAIManager.getInstance().getBreakerRejectStats();
            int p = roleStats.getOrDefault(BreachAssignmentRole.PRIMARY, 0);
            int s = roleStats.getOrDefault(BreachAssignmentRole.SUPPORT, 0);
            int b = roleStats.getOrDefault(BreachAssignmentRole.BODYGUARD, 0);
            int tempBlocks = TemporaryBlockManager.getInstance() != null ? TemporaryBlockManager.getInstance().getTemporaryBlockCount() : 0;
            sender.sendMessage(lm.getMessage("debug-ai-title"));
            sender.sendMessage("§7breachRoleP/S/B: §f" + p + "/" + s + "/" + b + " §7tempBlocks: §f" + tempBlocks);
            sender.sendMessage("§7cooperationHits: §f" + nodeHits);
            sender.sendMessage("§7abilityIntents: §f" + intentHits);
            sender.sendMessage("§7builderFailures: §f" + buildFailStats);
            sender.sendMessage("§7breakerRejects: §f" + breakerRejectStats);
            return;
        }

        if ("reset".equals(topic)) {
            ZombieFactory.resetSpawnRejectStats();
            ZombieAIManager.getInstance().resetDebugStats();
            sender.sendMessage(lm.getMessage("debug-reset-success"));
            return;
        }

        sender.sendMessage(lm.getMessage("debug-usage"));
    }

    private void handleMax(CommandSender sender, String[] args) {
        LanguageManager lm = LanguageManager.getInstance();
        if (args.length < 2) {
            sender.sendMessage("§c用法: /za max <player>");
            return;
        }

        Player target = sender.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(lm.getMessage("player-not-found"));
            return;
        }

        int maxLevel = ConfigManager.getInstance().getLevelMax();
        PlayerLevelManager.getInstance().setLevelOverride(target, maxLevel);
        sender.sendMessage("§a已将玩家 " + target.getName() + " 的等级设置为最大值 (" + maxLevel + ")");
    }

    private void handleLevel(CommandSender sender, String[] args) {
        LanguageManager lm = LanguageManager.getInstance();
        
        if (args.length < 2) {
             sender.sendMessage(lm.getMessage("command-usage"));
             return;
        }

        String sub = args[1].toLowerCase();
        
        if (sub.equals("set")) {
            if (args.length < 4) {
                sender.sendMessage(lm.getMessage("command-usage"));
                return;
            }
            Player target = sender.getServer().getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(lm.getMessage("player-not-found"));
                return;
            }
            try {
                int level = Integer.parseInt(args[3]);
                PlayerLevelManager.getInstance().setLevelOverride(target, level);
                sender.sendMessage(lm.getMessage("level-set-success", "%player%", target.getName(), "%level%", String.valueOf(level)));
            } catch (NumberFormatException e) {
                sender.sendMessage(lm.getMessage("command-usage"));
            }
        } else if (sub.equals("info")) {
             Player target;
             if (args.length > 2) {
                 target = sender.getServer().getPlayer(args[2]);
                 if (target == null) {
                     sender.sendMessage(lm.getMessage("player-not-found"));
                     return;
                 }
             } else if (sender instanceof Player) {
                 target = (Player) sender;
             } else {
                 sender.sendMessage(lm.getMessage("command-usage"));
                 return;
             }
             
             int level = PlayerLevelManager.getInstance().getPlayerLevel(target);
             sender.sendMessage(lm.getMessage("player-level", "%player%", target.getName(), "%level%", String.valueOf(level)));
        } else {
            // 向后兼容或简写 "za level <player>" -> info?
            // 根据要求假设严格的语法
            sender.sendMessage(lm.getMessage("command-usage"));
        }
    }
    
    private void handleGetLight(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(LanguageManager.getInstance().getMessage("players-only"));
            return;
        }
        
        Player player = (Player) sender;
        org.bukkit.inventory.ItemStack light = new org.bukkit.inventory.ItemStack(org.bukkit.Material.LIGHT);
        org.bukkit.inventory.meta.ItemMeta meta = light.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e光源方块");
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add("§7持有此方块可驱散周围15米内的僵尸");
            lore.add("§7右键点击可爆发强大的光能，重创50米内的僵尸");
            meta.setLore(lore);
            light.setItemMeta(meta);
        }
        
        player.getInventory().addItem(light);
        player.sendMessage("§a你获得了光源方块！");
    }

    private void handleInfo(CommandSender sender) {
        LanguageManager lm = LanguageManager.getInstance();
        int zombieCount = ZombieAIManager.getInstance().getZombieCount();
        
        double totalLevel = 0;
        int count = 0;
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            totalLevel += PlayerLevelManager.getInstance().getPlayerLevel(p);
            count++;
        }
        double avg = count > 0 ? totalLevel / count : 0;
        
        sender.sendMessage(lm.getMessage("info-message", 
            "%count%", String.valueOf(zombieCount), 
            "%avg_level%", String.format("%.2f", avg)));
            
        // 排行榜（简单的前 5 名）
        // 如果玩家很多，这可能会有点消耗性能，但目前还可以
        // 假设是小型服务器
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        LanguageManager lm = LanguageManager.getInstance();
        
        if (!(sender instanceof Player)) {
            sender.sendMessage(lm.getMessage("players-only"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(lm.getMessage("spawn-usage"));
            return;
        }
        
        try {
            ZombieRole role = ZombieRole.valueOf(args[1].toUpperCase());
            Player player = (Player) sender;
            Location loc = player.getLocation();
            
            Zombie zombie = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
            // 根据玩家应用等级
            int level = PlayerLevelManager.getInstance().getPlayerLevel(player);
            com.frigidora.toomuchzombies.mechanics.ZombieFactory.applyLevelAttributes(zombie, level);
            com.frigidora.toomuchzombies.mechanics.ZombieFactory.assignRole(zombie, role);
            
            sender.sendMessage(lm.getMessage("spawn-success", "%role%", role.name()));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(lm.getMessage("invalid-role"));
        }
    }
}
