package cn.ningmo.geminicraftchat.config;

import org.bukkit.configuration.file.FileConfiguration;
import cn.ningmo.geminicraftchat.GeminiCraftChat;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private final GeminiCraftChat plugin;
    private FileConfiguration config;

    public ConfigManager(GeminiCraftChat plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public String getDefaultTrigger() {
        return config.getString("chat.trigger", "ai");
    }

    public List<String> getTriggerWords() {
        List<String> words = config.getStringList("chat.trigger_words");
        return words != null ? words : new ArrayList<>();
    }

    public int getMaxHistory() {
        return config.getInt("chat.max_history", 10);
    }

    public String getThinkingFormat() {
        return config.getString("chat.format.thinking", "§7[AI] §f正在思考中...");
    }

    public String getResponseFormat() {
        return config.getString("chat.format.response", "§7[AI] §f%s");
    }

    public String getErrorFormat() {
        return config.getString("chat.format.error", "§c[AI] 发生错误：%s");
    }

    public long getCooldown() {
        return config.getLong("chat.cooldown", 10000);
    }

    public boolean isFilterEnabled() {
        return config.getBoolean("filter.enabled", true);
    }

    public List<String> getFilterWords() {
        List<String> words = config.getStringList("filter.words");
        return words != null ? words : new ArrayList<>();
    }

    public boolean isHttpProxyEnabled() {
        return config.getBoolean("api.http_proxy.enabled", false);
    }

    public String getHttpProxyHost() {
        return config.getString("api.http_proxy.host", "127.0.0.1");
    }

    public int getHttpProxyPort() {
        return config.getInt("api.http_proxy.port", 7890);
    }

    public String getProxyType() {
        return config.getString("api.http_proxy.type", "HTTP");
    }
} 