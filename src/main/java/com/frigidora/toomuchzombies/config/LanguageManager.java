package com.frigidora.toomuchzombies.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.frigidora.toomuchzombies.TooMuchZombies;

public class LanguageManager {

    private static LanguageManager instance;
    private FileConfiguration messages;
    private File messagesFile;

    private LanguageManager() {
        loadMessages();
    }

    public static void initialize() {
        if (instance == null) {
            instance = new LanguageManager();
        }
    }

    public static LanguageManager getInstance() {
        return instance;
    }

    public void loadMessages() {
        String lang = TooMuchZombies.getInstance().getConfig().getString("language", "en");
        String fileName = "messages_" + lang + ".yml";
        messagesFile = new File(TooMuchZombies.getInstance().getDataFolder(), fileName);
        
        if (!messagesFile.exists()) {
            try {
                TooMuchZombies.getInstance().saveResource(fileName, false);
            } catch (IllegalArgumentException e) {
                 TooMuchZombies.getInstance().getLogger().warning("语言文件 " + fileName + " 未找到。使用默认英语。");
                 fileName = "messages_en.yml";
                 messagesFile = new File(TooMuchZombies.getInstance().getDataFolder(), fileName);
                 if (!messagesFile.exists()) {
                     TooMuchZombies.getInstance().saveResource(fileName, false);
                 }
            }
        }
        
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        
        InputStream defConfigStream = TooMuchZombies.getInstance().getResource(fileName);
        if (defConfigStream == null) {
            defConfigStream = TooMuchZombies.getInstance().getResource("messages_en.yml");
        }
        
        if (defConfigStream != null) {
            messages.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)));
        }
    }

    public String getMessage(String key) {
        String msg = messages.getString(key);
        if (msg == null) {
            return "Missing message: " + key;
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public String getMessage(String key, String... placeholders) {
        String msg = getMessage(key);
        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                msg = msg.replace(placeholders[i], placeholders[i + 1]);
            }
        }
        return msg;
    }
    
    public void reload() {
        loadMessages();
    }
}
