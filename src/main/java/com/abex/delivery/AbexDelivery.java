package com.abex.delivery;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class AbexDelivery extends JavaPlugin {

    private static final String API_KEY = "AIzaSyDUNJFGmKxu3HFvdW_kQczHLzuwefOQo04";
    private static final String PROJECT_ID = "abex-786e7";

    private String email, password, storeId, ownerUID;
    private int interval = 120;
    private String idToken, refreshToken;
    private long tokenExpiry = 0;

    private String setupCode = null;
    private boolean isSetupMode = false;
    private int setupPollCount = 0;

    @Override
    public void onEnable() {
        loadData();
        if (email == null || storeId == null || ownerUID == null) {
            getLogger().info("========================================");
            getLogger().info(" AbexDelivery - Not Connected Yet");
            getLogger().info(" Type '/abex setup' in Minecraft to connect.");
            getLogger().info("========================================");
            return;
        }
        if (!signIn()) {
            getLogger().warning("========================================");
            getLogger().warning(" AbexBase login FAILED");
            getLogger().warning(" Run: /abex reset");
            getLogger().warning(" Then: /abex setup");
            getLogger().warning("========================================");
            return;
        }
        startDeliveryPolling();
        getLogger().info("========================================");
        getLogger().info(" AbexDelivery connected to store: " + storeId);
        getLogger().info("========================================");
    }

    private void startDeliveryPolling() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollDeliveries,
                20L * 10, 20L * interval);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("abex")) return false;
        if (args.length == 0) { showHelp(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "setup": handleSetup(sender); return true;
            case "status": showStatus(sender); return true;
            case "reset": handleReset(sender); return true;
            default: showHelp(sender); return true;
        }
    }

    private void showHelp(CommandSender s) {
        s.sendMessage("§6§l═══ AbexDelivery ═══");
        s.sendMessage("§e/abex setup §7- Connect to your store");
        s.sendMessage("§e/abex status §7- Show connection status");
        s.sendMessage("§e/abex reset §7- Disconnect");
    }

    private void handleSetup(CommandSender s) {
        if (email != null && storeId != null) {
            s.sendMessage("§cAlready connected to: §e" + storeId);
            s.sendMessage("§7Run §e/abex reset §7first.");
            return;
        }
        if (isSetupMode) {
            s.sendMessage("§eSetup in progress. Code: §a" + setupCode);
            return;
        }
        setupCode = "ABX-" + randomCode(4) + "-" + randomCode(4);
        isSetupMode = true;
        setupPollCount = 0;
        if (!createSetupEntry()) {
            s.sendMessage("§cFailed to create setup. Check internet.");
            isSetupMode = false;
            setupCode = null;
            return;
        }
        s.sendMessage("");
        s.sendMessage("§6§l╔══════════════════════════════╗");
        s.sendMessage("§6§l║    §e§lABEXDELIVERY SETUP§6§l    ║");
        s.sendMessage("§6§l╠══════════════════════════════╣");
        s.sendMessage("§6§l║  §fCode: §a§l" + setupCode + "  §6§l");
        s.sendMessage("§6§l╠══════════════════════════════╣");
        s.sendMessage("§6§l║  §71. Open Control Room      §6§l║");
        s.sendMessage("§6§l║  §72. Click 'Connect Plugin' §6§l║");
        s.sendMessage("§6§l║  §73. Enter this code        §6§l║");
        s.sendMessage("§6§l╚══════════════════════════════╝");
        s.sendMessage("");
        s.sendMessage("§7Waiting... §8(expires in 10 min)");
        pollSetup();
    }

    private void pollSetup() {
        if (!isSetupMode || setupCode == null) return;
        if (setupPollCount++ > 200) {
            isSetupMode = false;
            deleteSetupEntry();
            Bukkit.broadcastMessage("§c[AbexDelivery] Setup timed out.");
            setupCode = null;
            return;
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            if (!isSetupMode || setupCode == null) return;
            try {
                String res = fetchSetupEntry();
                if (res == null) { pollSetup(); return; }
                String status = jsonValue(res, "status");
                if ("connected".equals(status)) {
                    String newEmail = jsonValue(res, "pluginEmail");
                    String newPassword = jsonValue(res, "pluginPassword");
                    String newStoreId = jsonValue(res, "storeId");
                    String newOwnerUID = jsonValue(res, "ownerUID");
                    if (newEmail != null && newPassword != null && newStoreId != null && newOwnerUID != null) {
                        email = newEmail;
                        password = newPassword;
                        storeId = newStoreId;
                        ownerUID = newOwnerUID;
                        saveData();
                        deleteSetupEntry();
                        getLogger().info("Got credentials. Email: " + email);
                        Bukkit.broadcastMessage("§a§l[AbexDelivery] §r§aConnected to: §e" + storeId);
                        getLogger().info("Waiting 10 seconds for Firebase account activation...");
                        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
                            if (signIn()) {
                                isSetupMode = false;
                                setupCode = null;
                                startDeliveryPolling();
                                Bukkit.broadcastMessage("§a§l[AbexDelivery] §r§aLogin successful! Plugin is live.");
                            } else {
                                getLogger().warning("First login failed. Retrying in 20 seconds...");
                                Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
                                    if (signIn()) {
                                        isSetupMode = false;
                                        setupCode = null;
                                        startDeliveryPolling();
                                        Bukkit.broadcastMessage("§a§l[AbexDelivery] §r§aLogin successful! Plugin is live.");
                                    } else {
                                        Bukkit.broadcastMessage("§c[AbexDelivery] Login failed. Run /abex reset then /abex setup again.");
                                        isSetupMode = false;
                                        setupCode = null;
                                    }
                                }, 20L * 20);
                            }
                        }, 20L * 10);
                        return;
                    }
                }
            } catch (Exception ignored) {}
            pollSetup();
        }, 60L);
    }

    private void handleReset(CommandSender s) {
        email = null; password = null; storeId = null; ownerUID = null;
        idToken = null; refreshToken = null; tokenExpiry = 0;
        isSetupMode = false;
        if (setupCode != null) { deleteSetupEntry(); setupCode = null; }
        File dataFile = new File(getDataFolder(), "data.yml");
        if (dataFile.exists()) dataFile.delete();
        s.sendMessage("§aReset complete. Run §e/abex setup §ato reconnect.");
    }

    private void showStatus(CommandSender s) {
        if (email == null) {
            s.sendMessage("§6Status: §cNot connected");
            return;
        }
        s.sendMessage("§6§l═══ AbexDelivery ═══");
        s.sendMessage("§7Store: §f" + storeId);
        s.sendMessage("§7Email: §f" + email);
        if (idToken != null && System.currentTimeMillis() < tokenExpiry) {
            s.sendMessage("§7Status: §a§lCONNECTED ✓");
        } else {
            s.sendMessage("§7Status: §eLogged out (will retry)");
        }
    }

    private boolean signIn() {
        try {
            URL url = new URL("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + API_KEY);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            String body = "{\"email\":\"" + esc(email) + "\",\"password\":\"" + esc(password) + "\",\"returnSecureToken\":true}";
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code != 200) {
                String err = "";
                try { err = readAll(c.getErrorStream()); } catch (Exception ignored) {}
                getLogger().warning("Login HTTP " + code + ": " + err);
                return false;
            }
            String res = readAll(c.getInputStream());
            idToken = jsonValue(res, "idToken");
            refreshToken = jsonValue(res, "refreshToken");
            String expiresIn = jsonValue(res, "expiresIn");
            long secs = 3600;
            try { secs = Long.parseLong(expiresIn); } catch (Exception ignored) {}
            tokenExpiry = System.currentTimeMillis() + (secs - 60) * 1000L;
            return idToken != null;
        } catch (Exception e) {
            getLogger().warning("Login exception: " + e.getMessage());
            return false;
        }
    }

    private boolean refreshTokenIfNeeded() {
        if (idToken != null && System.currentTimeMillis() < tokenExpiry) return true;
        if (refreshToken == null) return signIn();
        try {
            URL url = new URL("https://securetoken.googleapis.com/v1/token?key=" + API_KEY);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "grant_type=refresh_token&refresh_token=" + refreshToken;
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() != 200) return signIn();
            String res = readAll(c.getInputStream());
            idToken = jsonValue(res, "id_token");
            refreshToken = jsonValue(res, "refresh_token");
            tokenExpiry = System.currentTimeMillis() + 55 * 60 * 1000L;
            return idToken != null;
        } catch (Exception e) { return signIn(); }
    }

    private void pollDeliveries() {
        if (!refreshTokenIfNeeded()) return;
        try {
            String urlStr = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                    + "/databases/(default)/documents:runQuery";
            String queryJson = "{"
                + "\"structuredQuery\":{"
                +   "\"from\":[{\"collectionId\":\"deliveries\"}],"
                +   "\"where\":{"
                +     "\"compositeFilter\":{"
                +       "\"op\":\"AND\","
                +       "\"filters\":["
                +         "{\"fieldFilter\":{\"field\":{\"fieldPath\":\"ownerUID\"},"
                +           "\"op\":\"EQUAL\",\"value\":{\"stringValue\":\"" + esc(ownerUID) + "\"}}},"
                +         "{\"fieldFilter\":{\"field\":{\"fieldPath\":\"storeId\"},"
                +           "\"op\":\"EQUAL\",\"value\":{\"stringValue\":\"" + esc(storeId) + "\"}}},"
                +         "{\"fieldFilter\":{\"field\":{\"fieldPath\":\"delivered\"},"
                +           "\"op\":\"EQUAL\",\"value\":{\"booleanValue\":false}}}"
                +       "]}}}"
                + "}";
            URL url = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + idToken);
            try (OutputStream o = c.getOutputStream()) { o.write(queryJson.getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() != 200) return;
            processResults(readAll(c.getInputStream()));
        } catch (Exception e) { getLogger().warning("Poll error: " + e.getMessage()); }
    }

    private void processResults(String json) {
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"projects/[^\"]+/documents/deliveries/([^\"]+)\"").matcher(json);
        Set<String> ids = new LinkedHashSet<>();
        while (m.find()) ids.add(m.group(1));
        for (String id : ids) executeOrder(id, json);
    }

    private void executeOrder(String docId, String json) {
        try {
            int pos = json.indexOf("deliveries/" + docId);
            if (pos < 0) return;
            String chunk = json.substring(pos, Math.min(json.length(), pos + 5000));
            List<String> cmds = extractStringArray(chunk, "commands");
            if (cmds.isEmpty()) return;
            
            String username = jsonValue(chunk, "username");
            final String playerName = (username != null && !username.isEmpty()) ? username : "Player";
            String storeName = jsonValue(chunk, "storeId");
            final String store = (storeName != null && !storeName.isEmpty()) ? storeName : "Store";
            
            Bukkit.getScheduler().runTask(this, () -> {
                // Execute commands
                for (String raw : cmds) {
                    String cmd = raw.startsWith("/") ? raw.substring(1) : raw;
                    try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); }
                    catch (Exception ex) { getLogger().warning("Cmd failed: " + cmd); }
                }
                
                // 🔔🎵 Play sound to ALL online players
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
                    } catch (Exception ignored) {}
                }
                
                // 🎨🎉 Full Color + Bold Broadcast Message
                Bukkit.broadcastMessage("");
                Bukkit.broadcastMessage("§8§m                                                        ");
                Bukkit.broadcastMessage("§6§l  ✦ §e§lORDER §a§lDELIVERED §e§l✦");
                Bukkit.broadcastMessage("§8§m                                                        ");
                Bukkit.broadcastMessage("§f  §b§l▶ §fPlayer: §e§l" + playerName);
                Bukkit.broadcastMessage("§f  §b§l▶ §fStore:  §a§l" + store);
                Bukkit.broadcastMessage("§f  §b§l▶ §a§lSay §e§lGG §a§lfor §e§l" + playerName + " §a§l!");
                Bukkit.broadcastMessage("§8§m                                                        ");
                Bukkit.broadcastMessage("");
                
                getLogger().info("Delivered order " + docId + " to " + playerName + " from store " + store);
            });
            markDelivered(docId);
        } catch (Exception e) {
            getLogger().warning("Exec error: " + e.getMessage());
        }
    }

    private void markDelivered(String docId) throws IOException {
        String urlStr = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                + "/databases/(default)/documents/deliveries/" + docId
                + "?updateMask.fieldPaths=delivered&updateMask.fieldPaths=deliveredAt";
        String body = "{\"fields\":{\"delivered\":{\"booleanValue\":true},"
                    + "\"deliveredAt\":{\"integerValue\":\"" + System.currentTimeMillis() + "\"}}}";
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + idToken);
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        if (code != 200 && code != 201) {
            getLogger().warning("markDelivered HTTP " + code);
        }
    }

    private boolean createSetupEntry() {
        try {
            String url = "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                    + "/databases/(default)/documents/plugin-setup?documentId=" + setupCode;
            String body = "{\"fields\":{"
                    + "\"status\":{\"stringValue\":\"waiting\"},"
                    + "\"createdAt\":{\"integerValue\":\"" + System.currentTimeMillis() + "\"}"
                    + "}}";
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            return c.getResponseCode() == 200;
        } catch (Exception e) { return false; }
    }

    private String fetchSetupEntry() {
        try {
            URL url = new URL("https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                    + "/databases/(default)/documents/plugin-setup/" + setupCode);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return null;
            return readAll(c.getInputStream());
        } catch (Exception e) { return null; }
    }

    private void deleteSetupEntry() {
        try {
            URL url = new URL("https://firestore.googleapis.com/v1/projects/" + PROJECT_ID
                    + "/databases/(default)/documents/plugin-setup/" + setupCode);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("DELETE");
            c.getResponseCode();
        } catch (Exception ignored) {}
    }

    private void loadData() {
        File dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) return;
        try {
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(dataFile)) { props.load(in); }
            email = props.getProperty("email");
            password = props.getProperty("password");
            storeId = props.getProperty("store-id");
            ownerUID = props.getProperty("owner-uid");
            interval = Integer.parseInt(props.getProperty("poll-interval-seconds", "120"));
        } catch (Exception e) { getLogger().warning("Failed to load data.yml"); }
    }

    private void saveData() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            File dataFile = new File(getDataFolder(), "data.yml");
            Properties props = new Properties();
            props.setProperty("email", email);
            props.setProperty("password", password);
            props.setProperty("store-id", storeId);
            props.setProperty("owner-uid", ownerUID);
            props.setProperty("poll-interval-seconds", String.valueOf(interval));
            try (FileOutputStream out = new FileOutputStream(dataFile)) {
                props.store(out, "AbexDelivery Connection Data - DO NOT SHARE");
            }
        } catch (Exception e) { getLogger().warning("Failed to save data.yml"); }
    }

    private String randomCode(int len) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    // ─── JSON Parser (both flat + Firestore nested format) ───
    private String jsonValue(String j, String k) {
        Matcher m1 = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(j);
        if (m1.find()) return m1.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        Matcher m2 = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(j);
        return m2.find() ? m2.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    private List<String> extractStringArray(String j, String k) {
        List<String> out = new ArrayList<>();
        int keyIdx = j.indexOf("\"" + k + "\"");
        if (keyIdx < 0) return out;
        int arrStart = j.indexOf('[', keyIdx);
        if (arrStart < 0) return out;
        int arrEnd = j.indexOf(']', arrStart);
        if (arrEnd < 0) return out;
        String arrayContent = j.substring(arrStart + 1, arrEnd);
        Matcher m = Pattern.compile("\"stringValue\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(arrayContent);
        while (m.find()) {
            String v = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
            out.add(v);
        }
        return out;
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void onDisable() { idToken = null; refreshToken = null; }
}
