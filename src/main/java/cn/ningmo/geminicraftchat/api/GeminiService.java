package cn.ningmo.geminicraftchat.api;

import cn.ningmo.geminicraftchat.GeminiCraftChat;
import cn.ningmo.geminicraftchat.config.ConfigManager;
import cn.ningmo.geminicraftchat.persona.Persona;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class GeminiService {
    private final GeminiCraftChat plugin;
    private final ConfigManager configManager;
    private final Map<String, List<Map<String, String>>> chatHistories;
    private final Gson gson;
    private final Map<String, Object> config;
    private final String type;
    private final String name;
    private final int weight;

    @SuppressWarnings("unchecked")
    public GeminiService(GeminiCraftChat plugin, Map<?, ?> config, String type) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.chatHistories = new ConcurrentHashMap<>();
        this.gson = new Gson();
        
        // 安全地转换配置
        this.config = new HashMap<>();
        config.forEach((k, v) -> {
            if (k != null && v != null) {
                this.config.put(String.valueOf(k), v);
            }
        });
        
        this.type = type;
        
        // 安全地获取名称
        Object nameObj = config.get("name");
        this.name = nameObj != null ? nameObj.toString() : "unnamed";
        
        // 安全地获取权重
        Object weightObj = config.get("weight");
        this.weight = weightObj instanceof Number ? ((Number) weightObj).intValue() : 1;
    }

    public CompletableFuture<String> sendMessage(String playerId, String message, Optional<Persona> persona) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                switch (type.toLowerCase()) {
                    case "direct":
                        return sendDirectRequest(playerId, message, persona);
                    case "proxy":
                        return sendProxyRequest(playerId, message, persona);
                    case "openai":
                        return sendOpenAIRequest(playerId, message, persona);
                    default:
                        throw new IllegalStateException("未知的API类型: " + type);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("发送消息失败: " + e.getMessage());
                throw new RuntimeException("AI 响应失败", e);
            }
        });
    }

    private String sendProxyRequest(String playerId, String message, Optional<Persona> persona) throws IOException {
        // 获取代理配置
        String url = (String) config.get("url");
        String key = (String) config.get("key");
        
        if (url == null) {
            throw new IllegalStateException("未配置代理URL");
        }
        
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("message", message);
        
        // 添加人设信息
        if (persona.isPresent()) {
            requestBody.addProperty("persona", persona.get().getContext());
        }
        
        // 添加历史记录
        List<Map<String, String>> history = chatHistories.get(playerId);
        if (history != null && !history.isEmpty()) {
            JsonArray historyArray = new JsonArray();
            for (Map<String, String> msg : history) {
                JsonObject historyMsg = new JsonObject();
                historyMsg.addProperty("role", msg.get("role"));
                historyMsg.addProperty("content", msg.get("content"));
                historyArray.add(historyMsg);
            }
            requestBody.add("history", historyArray);
        }

        // 发送请求
        plugin.debug("中转API URL: " + url);
        plugin.debug("请求体: " + requestBody.toString());
        
        HttpURLConnection conn = createConnection(new URL(url));
        
        // 设置请求头
        conn.setRequestProperty("Content-Type", "application/json");
        if (key != null && !key.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
            plugin.debug("已设置Authorization头");
        }

        try {
            // 发送请求体
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(requestBody.toString());
                writer.flush();
            }

            // 获取响应
            int responseCode = conn.getResponseCode();
            plugin.debug("API响应代码: " + responseCode);
            
            if (responseCode == 404) {
                plugin.debug("API端点未找到，请检查URL是否正确");
                throw new IOException("API端点未找到(404)，请检查中转服务器URL配置");
            }

            if (responseCode != 200) {
                handleErrorResponse(conn);
            }

            String response = readResponse(conn);
            plugin.debug("API原始响应: " + response);

            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            String responseText = jsonResponse.get("response").getAsString();

            // 更新历史记录
            updateChatHistory(playerId, message, responseText);
            return responseText;
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection createConnection(URL url) throws IOException {
        HttpURLConnection conn;
        
        // 处理代理设置
        if (configManager.isHttpProxyEnabled()) {
            String proxyHost = configManager.getHttpProxyHost();
            int proxyPort = configManager.getHttpProxyPort();
            String proxyType = configManager.getProxyType();
            
            plugin.debug("使用" + proxyType + "代理: " + proxyHost + ":" + proxyPort);
            
            // 设置代理
            if ("SOCKS".equalsIgnoreCase(proxyType)) {
                System.setProperty("socksProxyHost", proxyHost);
                System.setProperty("socksProxyPort", String.valueOf(proxyPort));
            } else {
                System.setProperty("http.proxyHost", proxyHost);
                System.setProperty("http.proxyPort", String.valueOf(proxyPort));
                System.setProperty("https.proxyHost", proxyHost);
                System.setProperty("https.proxyPort", String.valueOf(proxyPort));
            }
        }
        
        try {
            conn = (HttpURLConnection) url.openConnection();
            
            // 获取超时设置
            Map<String, Object> timeoutConfig = new HashMap<>();
            Object timeoutObj = config.getOrDefault("timeout", new HashMap<>());
            if (timeoutObj instanceof Map) {
                ((Map<?, ?>) timeoutObj).forEach((k, v) -> {
                    if (k != null && v != null) {
                        timeoutConfig.put(k.toString(), v);
                    }
                });
            }
            
            int connectTimeout = ((Number) timeoutConfig.getOrDefault("connect", 30000)).intValue();
            int readTimeout = ((Number) timeoutConfig.getOrDefault("read", 30000)).intValue();
            
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setDoOutput(true);
            
            return conn;
        } finally {
            // 清理代理设置
            if (configManager.isHttpProxyEnabled()) {
                System.clearProperty("socksProxyHost");
                System.clearProperty("socksProxyPort");
                System.clearProperty("http.proxyHost");
                System.clearProperty("http.proxyPort");
                System.clearProperty("https.proxyHost");
                System.clearProperty("https.proxyPort");
            }
        }
    }

    private String sendDirectRequest(String playerId, String message, Optional<Persona> persona) throws IOException {
        // 获取API URL
        String apiUrl = (String) config.getOrDefault("url", 
            "https://generativelanguage.googleapis.com/v1/models/gemini-pro:generateContent");
        String apiKey = (String) config.get("key");
        
        List<Map<String, String>> history = chatHistories.computeIfAbsent(playerId, k -> new ArrayList<>());
        
        // 构建请求体
        JsonObject requestBody = new JsonObject();
        JsonArray contents = new JsonArray();
        
        // 如果有人设且历史记录为空，添加人设上下文
        if (persona.isPresent() && history.isEmpty()) {
            JsonObject personaContent = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", persona.get().getContext());
            parts.add(part);
            personaContent.add("parts", parts);
            personaContent.addProperty("role", "user");
            contents.add(personaContent);
            
            plugin.debug("添加人设上下文: " + persona.get().getContext());
        }

        // 添加用户消息
        JsonObject userContent = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("text", message);
        parts.add(part);
        userContent.add("parts", parts);
        userContent.addProperty("role", "user");
        contents.add(userContent);

        requestBody.add("contents", contents);
        
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.7);
        generationConfig.addProperty("topP", 0.95);
        generationConfig.addProperty("topK", 40);
        generationConfig.addProperty("maxOutputTokens", 1024);
        requestBody.add("generationConfig", generationConfig);

        // 发送请求
        URL url = new URL(apiUrl + "?key=" + apiKey);
        plugin.debug("发送请求到: " + url.toString().replaceAll("key=.*", "key=***"));
        plugin.debug("请求体: " + requestBody.toString());
        
        HttpURLConnection conn = createConnection(url);
        
        try {
            // 发送请求体
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(requestBody.toString());
                writer.flush();
            }

            // 获取响应
            int responseCode = conn.getResponseCode();
            plugin.debug("API响应代码: " + responseCode);

            if (responseCode != 200) {
                handleErrorResponse(conn);
            }

            String response = readResponse(conn);
            plugin.debug("API原始响应: " + response);

            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            String responseText = jsonResponse
                .getAsJsonArray("candidates")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0)
                .getAsJsonObject()
                .get("text")
                .getAsString();

            // 更新历史记录
            updateChatHistory(playerId, message, responseText);
            plugin.debug("更新历史记录成功");

            return responseText;
        } catch (Exception e) {
            plugin.debug("请求失败: " + e.getMessage());
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("请求处理失败", e);
        } finally {
            // 清理代理设置
            if (configManager.isHttpProxyEnabled()) {
                String proxyType = configManager.getProxyType();
                if ("SOCKS".equalsIgnoreCase(proxyType)) {
                    System.clearProperty("socksProxyHost");
                    System.clearProperty("socksProxyPort");
                } else {
                    System.clearProperty("http.proxyHost");
                    System.clearProperty("http.proxyPort");
                    System.clearProperty("https.proxyHost");
                    System.clearProperty("https.proxyPort");
                }
            }
            conn.disconnect();
        }
    }

    private String sendOpenAIRequest(String playerId, String message, Optional<Persona> persona) throws IOException {
        // 获取OpenAI配置
        String url = (String) config.get("url");
        String key = (String) config.get("key");
        String model = (String) config.getOrDefault("model", "gpt-3.5-turbo");
        double temperature = ((Number) config.getOrDefault("temperature", 0.7)).doubleValue();
        int maxTokens = ((Number) config.getOrDefault("max_tokens", 1024)).intValue();
        
        if (url == null) {
            throw new IllegalStateException("未配置OpenAI URL");
        }
        
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        
        // 添加参数配置
        requestBody.addProperty("temperature", temperature);
        requestBody.addProperty("max_tokens", maxTokens);
        requestBody.addProperty("top_p", configManager.getConfig().getDouble("api.openai.top_p", 0.95));
        requestBody.addProperty("frequency_penalty", configManager.getConfig().getDouble("api.openai.frequency_penalty", 0.0));
        requestBody.addProperty("presence_penalty", configManager.getConfig().getDouble("api.openai.presence_penalty", 0.0));
        
        // 构建消息数组
        JsonArray messages = new JsonArray();
        
        // 添加人设系统消息
        if (persona.isPresent()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", persona.get().getContext());
            messages.add(systemMessage);
        }
        
        // 添加历史记录
        List<Map<String, String>> history = chatHistories.get(playerId);
        if (history != null) {
            for (Map<String, String> msg : history) {
                JsonObject historyMessage = new JsonObject();
                historyMessage.addProperty("role", msg.get("role"));
                historyMessage.addProperty("content", msg.get("content"));
                messages.add(historyMessage);
            }
        }
        
        // 添加当前消息
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", message);
        messages.add(userMessage);
        
        requestBody.add("messages", messages);
        
        // 发送请求
        plugin.debug("发送OpenAI格式请求到: " + url);
        plugin.debug("请求体: " + requestBody.toString());
        
        HttpURLConnection conn = createConnection(new URL(url));
        if (key != null && !key.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(requestBody.toString());
                writer.flush();
            }

            int responseCode = conn.getResponseCode();
            plugin.debug("API响应代码: " + responseCode);

            if (responseCode != 200) {
                handleErrorResponse(conn);
            }

            String response = readResponse(conn);
            plugin.debug("API原始响应: " + response);

            // 解析OpenAI响应格式
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            String responseText = jsonResponse
                .getAsJsonArray("choices")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();

            // 更新历史记录
            updateChatHistory(playerId, message, responseText);
            plugin.debug("更新历史记录成功");

            return responseText;
        } finally {
            conn.disconnect();
        }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        StringBuilder response = new StringBuilder();
        try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.name())) {
            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
            }
        }
        return response.toString();
    }

    private void handleErrorResponse(HttpURLConnection conn) throws IOException {
        StringBuilder error = new StringBuilder();
        try (Scanner scanner = new Scanner(conn.getErrorStream(), StandardCharsets.UTF_8.name())) {
            while (scanner.hasNextLine()) {
                error.append(scanner.nextLine());
            }
        }
        String errorMessage = error.toString();
        plugin.debug("API错误响应: " + errorMessage);
        throw new IOException("Server returned HTTP response code: " + conn.getResponseCode() + "\n" + errorMessage);
    }

    private void updateChatHistory(String playerId, String message, String response) {
        List<Map<String, String>> history = chatHistories.computeIfAbsent(playerId, k -> new ArrayList<>());
        
        // 添加用户消息
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", message);
        history.add(userMessage);

        // 添加AI响应
        Map<String, String> aiMessage = new HashMap<>();
        aiMessage.put("role", "assistant");
        aiMessage.put("content", response);
        history.add(aiMessage);

        // 检查历史记录长度
        int maxHistory = configManager.getMaxHistory() * 2;
        while (history.size() > maxHistory) {
            history.remove(0);
            history.remove(0);
        }
        
        plugin.debug(String.format("更新历史记录 [%s]: %d条消息", playerId, history.size()));
    }

    public void clearHistory(String playerId) {
        chatHistories.remove(playerId);
    }

    public void clearAllHistory() {
        chatHistories.clear();
    }

    public int getWeight() {
        return weight;
    }

    public String getEndpointInfo() {
        return String.format("%s (%s)", name, type);
    }
} 