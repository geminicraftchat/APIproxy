package cn.ningmo.geminicraftchat;

import org.bukkit.plugin.java.JavaPlugin;
import cn.ningmo.geminicraftchat.config.ConfigManager;
import cn.ningmo.geminicraftchat.commands.MainCommand;
import cn.ningmo.geminicraftchat.commands.AdminCommand;
import cn.ningmo.geminicraftchat.listeners.ChatListener;
import cn.ningmo.geminicraftchat.chat.ChatManager;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.List;
import java.util.Map;

public class GeminiCraftChat extends JavaPlugin {
    private static GeminiCraftChat instance;
    private ConfigManager configManager;
    private ChatManager chatManager;
    private Logger pluginLogger;

    @Override
    public void onEnable() {
        instance = this;
        this.pluginLogger = getLogger();
        
        logStartupInfo();
        info("正在初始化插件...");
        
        try {
            // 初始化配置管理器
            this.configManager = new ConfigManager(this);
            this.configManager.loadConfig();
            info("配置加载成功");
        } catch (Exception e) {
            error("配置加载失败", e);
            error("将使用默认配置");
        }
        
        // 验证配置并提供警告而不是直接禁用
        validateConfig();
        
        // 初始化聊天管理器
        try {
            this.chatManager = new ChatManager(this);
            info("聊天管理器初始化成功");
        } catch (Exception e) {
            error("聊天管理器初始化失败", e);
            warning("AI聊天功能将不可用，但其他功能仍可使用");
        }
        
        // 注册命令
        try {
            getCommand("gcc").setExecutor(new MainCommand(this));
            getCommand("gccadmin").setExecutor(new AdminCommand(this));
            info("命令注册成功");
        } catch (Exception e) {
            error("命令注册失败", e);
            warning("命令功能将不可用");
        }
        
        // 注册监听器
        try {
            getServer().getPluginManager().registerEvents(new ChatListener(this), this);
            info("事件监听器注册成功");
        } catch (Exception e) {
            error("事件监听器注册失败", e);
            warning("聊天监听功能将不可用");
        }
        
        // 输出配置信息
        logConfigInfo();
        
        info("GeminiCraftChat v" + getDescription().getVersion() + " 插件已启动!");
    }

    @Override
    public void onDisable() {
        if (chatManager != null) {
            try {
                chatManager.clearAllHistory();
                info("已清理所有聊天历史记录");
            } catch (Exception e) {
                error("清理聊天历史记录时发生错误", e);
            }
        }
        
        info("GeminiCraftChat 插件已关闭!");
    }

    private void validateConfig() {
        boolean hasWarnings = false;
        
        // 检查API配置
        List<Map<?, ?>> items = getConfig().getMapList("api.items");
        if (items.isEmpty()) {
            error("没有配置任何API节点！");
            hasWarnings = true;
        }

        // 检查每个API节点
        for (Map<?, ?> item : items) {
            // 安全地获取配置值
            Object nameObj = item.get("name");
            Object typeObj = item.get("type");
            String name = nameObj != null ? nameObj.toString() : "unnamed";
            String type = typeObj != null ? typeObj.toString() : "proxy";
            
            // 检查必要的配置
            if (!item.containsKey("key")) {
                warning("API节点 [" + name + "] 未配置密钥");
                hasWarnings = true;
            }
            
            if ("proxy".equals(type) || "openai".equals(type)) {
                if (!item.containsKey("url")) {
                    warning("API节点 [" + name + "] 未配置URL");
                    hasWarnings = true;
                }
            }
        }
        
        // 检查其他配置
        if (getConfig().getInt("chat.max_history", 10) <= 0) {
            warning("历史记录长度必须大于0，将使用默认值 10");
            hasWarnings = true;
        }
        
        if (getConfig().getLong("chat.cooldown", 10000) < 0) {
            warning("冷却时间不能为负数，将使用默认值 10 秒");
            hasWarnings = true;
        }
        
        // 检查敏感词过滤
        if (getConfig().getBoolean("filter.enabled", true)) {
            List<String> filterWords = getConfig().getStringList("filter.words");
            if (filterWords == null || filterWords.isEmpty()) {
                warning("敏感词过滤已启用但未配置敏感词列表");
                hasWarnings = true;
            }
        }
        
        if (hasWarnings) {
            warning("配置验证完成，存在一些警告，请检查配置文件");
        } else {
            info("配置验证完成，一切正常");
        }
    }

    private void logConfigInfo() {
        // 检查代理设置
        if (configManager.isHttpProxyEnabled()) {
            info("代理已启用 - " + configManager.getHttpProxyHost() + ":" + configManager.getHttpProxyPort() + 
                " (" + configManager.getProxyType() + ")");
        }
        
        // 输出API模式
        String mode = getConfig().getString("api.mode", "single");
        info("API负载均衡模式: " + mode.toUpperCase());
        
        // 输出API节点信息
        List<Map<?, ?>> items = getConfig().getMapList("api.items");
        int enabledCount = 0;
        for (Map<?, ?> item : items) {
            // 安全地获取配置值
            Object enabledObj = item.get("enabled");
            boolean enabled = true;
            if (enabledObj instanceof Boolean) {
                enabled = (Boolean) enabledObj;
            }
            
            if (enabled) {
                enabledCount++;
                Object nameObj = item.get("name");
                Object typeObj = item.get("type");
                String name = nameObj != null ? nameObj.toString() : "unnamed";
                String type = typeObj != null ? typeObj.toString() : "proxy";
                info(String.format("已加载API节点: %s (%s)", name, type.toUpperCase()));
            }
        }
        info("共启用 " + enabledCount + " 个API节点");
        
        // 输出触发词信息
        info("默认触发词: " + configManager.getDefaultTrigger());
        List<String> triggers = configManager.getTriggerWords();
        if (!triggers.isEmpty()) {
            info("其他触发词: " + String.join(", ", triggers));
        }
        
        // 输出人设信息
        try {
            int personaCount = getConfig().getConfigurationSection("personas").getKeys(false).size();
            info("已加载 " + personaCount + " 个人设");
        } catch (Exception e) {
            warning("人设配置加载失败，将使用默认人设");
        }
        
        // 输出安全设置信息
        if (getConfig().getBoolean("security.command_check.enabled", true)) {
            int blockedCommands = getConfig().getStringList("security.command_check.blocked_commands").size();
            int blockedKeywords = getConfig().getStringList("security.command_check.blocked_keywords").size();
            info("命令检查已启用 - " + blockedCommands + " 个命令, " + blockedKeywords + " 个关键词");
        }
        
        // 输出限制信息
        info(String.format("消息限制: %d字符/条, %d条/分钟", 
            getConfig().getInt("security.limits.max_message_length", 500),
            getConfig().getInt("security.limits.rate_limit", 5)));
        
        // 输出调试状态
        if (isDebugEnabled()) {
            info("调试模式已启用");
        }
    }

    public static GeminiCraftChat getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ChatManager getChatManager() {
        return chatManager;
    }

    public void log(Level level, String message) {
        pluginLogger.log(level, message);
    }

    public void log(Level level, String message, Throwable throwable) {
        if (throwable != null) {
            pluginLogger.log(level, message, throwable);
            if (isDebugEnabled()) {
                throwable.printStackTrace();
            }
        } else {
            pluginLogger.log(level, message);
        }
    }

    public void debug(String message) {
        if (isDebugEnabled()) {
            pluginLogger.info("[DEBUG] " + message);
        }
    }

    public void debug(String message, Throwable throwable) {
        if (isDebugEnabled()) {
            pluginLogger.log(Level.INFO, "[DEBUG] " + message, throwable);
            throwable.printStackTrace();
        }
    }

    public void error(String message) {
        pluginLogger.severe(message);
    }

    public void error(String message, Throwable throwable) {
        pluginLogger.log(Level.SEVERE, message, throwable);
        if (isDebugEnabled()) {
            throwable.printStackTrace();
        }
    }

    public void warning(String message) {
        pluginLogger.warning(message);
    }

    public void warning(String message, Throwable throwable) {
        pluginLogger.log(Level.WARNING, message, throwable);
        if (isDebugEnabled()) {
            throwable.printStackTrace();
        }
    }

    public void info(String message) {
        pluginLogger.info(message);
    }

    private void logStartupInfo() {
        info("=== GeminiCraftChat v" + getDescription().getVersion() + " ===");
        info("作者: " + String.join(", ", getDescription().getAuthors()));
        if (isDebugEnabled()) {
            info("调试模式已启用");
            debug("Java版本: " + System.getProperty("java.version"));
            debug("服务器版本: " + getServer().getVersion());
            debug("插件版本: " + getDescription().getVersion());
            debug("API版本: " + getDescription().getAPIVersion());
        }
    }

    public void validateAndLogConfig() {
        validateConfig();
        logConfigInfo();
    }

    public void reloadPlugin() {
        // 保存默认配置
        saveDefaultConfig();
        
        // 重载配置文件
        reloadConfig();
        
        try {
            // 重新初始化配置管理器
            configManager.loadConfig();
            getLogger().info("配置文件重载成功");
            
            // 重新初始化聊天管理器
            if (chatManager != null) {
                // 保存所有历史记录（可选）
                chatManager.saveAllHistory();
                // 停止清理任务
                chatManager.stopCleanupTask();
            }
            
            chatManager = new ChatManager(this);
            chatManager.startCleanupTask();
            getLogger().info("聊天管理器重新初始化成功");
            
            // 验证新配置
            validateAndLogConfig();
            
            getLogger().info("插件重载完成");
        } catch (Exception e) {
            getLogger().severe("插件重载失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean isDebugEnabled() {
        return configManager.getConfig().getBoolean("debug", false);
    }
} 