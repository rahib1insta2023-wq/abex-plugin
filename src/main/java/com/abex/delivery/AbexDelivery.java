package com.abex.delivery;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class AbexDelivery extends JavaPlugin {
    private String apiKey, projectId, email, password, storeId, ownerUID;
    private int interval;

    private String idToken = null;
    private String refreshToken = null;
    private long tokenExpiry = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        apiKey     = getConfig().getString("firebase.api-key", "");
        projectId  = getConfig().getString("firebase.project-id", "");
        email      = getConfig().getString("auth.email", "");
        password   = getConfig().getString("auth.password", "");
        storeId    = getConfig().getString("store.id", "");
        ownerUID   = getConfig().getString("store.owner-uid", "");
        interval   = getConfig().getInt("poll-interval-seconds", 300);

        if (apiKey.isEmpty() || email.isEmpty() || ownerUID.isEmpty()) {
            getLogger().severe("config.yml incomplete! Check api-key, email, owner-uid");
            return;
        }

        if (!signIn()) {
            getLogger().severe("Firebase login failed! Check email/password");
            return;
        }

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollOnce,
                20L * 5, 20L * interval);
        getLogger().info("AbexDelivery started for store: " + storeId);
    }

    private boolean signIn() {
        try {
            URL url = new URL("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + apiKey);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");

            String body = "{\"email\":\"" + esc(email) + "\",\"password\":\"" + esc(password) + "\",\"returnSecureToken\":true}";
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }

            if (c.getResponseCode() != 200) {
                getLogger().warning("Login HTTP " + c.getResponseCode());
                return false;
            }
            String res = readAll(c.getInputStream());
            idToken      = jsonValue(res, "idToken");
            refreshToken = jsonValue(res, "refreshToken");
            String expiresIn = jsonValue(res, "expiresIn");
            long secs = 3600;
            try { secs = Long.parseLong(expiresIn); } catch (Exception ignored) {}
            tokenExpiry = System.currentTimeMillis() + (secs - 60) * 1000L;
            return idToken != null;
        } catch (Exception e) {
            getLogger().warning("Login error: " + e.getMessage());
            return false;
        }
    }

    private boolean refreshTokenIfNeeded() {
        if (idToken != null && System.currentTimeMillis() < tokenExpiry) return true;
        if (refreshToken == null) return signIn();
        try {
            URL url = new URL("https://securetoken.googleapis.com/v1/token?key=" + apiKey);
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

    private void pollOnce() {
        if (!refreshTokenIfNeeded()) return;
        try {
            String urlStr = "https://firestore.googleapis.com/v1/projects/" + projectId
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

            if (c.getResponseCode() != 200) {
                getLogger().warning("Query HTTP " + c.getResponseCode());
                return;
            }
            String res = readAll(c.getInputStream());
            processResults(res);
        } catch (Exception e) {
            getLogger().warning("Poll error: " + e.getMessage());
        }
    }

    private void processResults(String json) {
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"projects/[^\"]+/documents/deliveries/([^\"]+)\"").matcher(json);
        List<String> docIds = new ArrayList<>();
        while (m.find()) {
            String id = m.group(1);
            if (!docIds.contains(id)) docIds.add(id);
        }
        for (String docId : docIds) executeOrder(docId, json);
    }

    private void executeOrder(String docId, String json) {
        try {
            int pos = json.indexOf("deliveries/" + docId);
            if (pos < 0) return;
            String chunk = json.substring(pos, Math.min(json.length(), pos + 3000));

            List<String> cmds = extractStringArray(chunk, "commands");
            if (cmds.isEmpty()) return;

            Bukkit.getScheduler().runTask(this, () -> {
                for (String raw : cmds) {
                    String cmd = raw.startsWith("/") ? raw.substring(1) : raw;
                    try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); }
                    catch (Exception ex) { getLogger().warning("Cmd failed: " + cmd); }
                }
                getLogger().info("Delivered " + docId + " (" + cmds.size() + " cmds)");
            });

            markDelivered(docId);
        } catch (Exception e) {
            getLogger().warning("Exec error " + docId + ": " + e.getMessage());
        }
    }

    private void markDelivered(String docId) throws IOException {
        String urlStr = "https://firestore.googleapis.com/v1/projects/" + projectId
                + "/databases/(default)/documents/deliveries/" + docId
                + "?updateMask.fieldPaths=delivered&updateMask.fieldPaths=deliveredAt";
        String body = "{\"fields\":{\"delivered\":{\"booleanValue\":true},"
                    + "\"deliveredAt\":{\"integerValue\":\"" + System.currentTimeMillis() + "\"}}}";

        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("PATCH");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + idToken);
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        c.getResponseCode();
    }

    private String jsonValue(String j, String k) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(k) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(j);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    private List<String> extractStringArray(String j, String k) {
        List<String> out = new ArrayList<>();
        int i = j.indexOf("\"" + k + "\"");
        if (i < 0) return out;
        int s = j.indexOf('[', i);
        if (s < 0) return out;
        int e = s + 1, d = 1; boolean str = false;
        while (e < j.length()) {
            char c = j.charAt(e);
            if (c == '\\' && str) { e += 2; continue; }
            if (c == '"') str = !str;
            if (!str) {
                if (c == '[') d++;
                else if (c == ']') { d--; if (d == 0) break; }
            }
            e++;
        }
        String inner = j.substring(s + 1, e);
        Matcher m = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(inner);
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

    @Override public void onDisable() { idToken = null; refreshToken = null; }
}
