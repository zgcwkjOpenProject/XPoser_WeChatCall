package com.zgcwkj.comm;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 配置文件读写工具
 * 配置保存在应用私有目录 config.json，内容为 JSON，仅本应用读写，无需存储权限
 */
public class ConfigHelp {

    private static final String TAG = "XpWeChatCall";
    private static final String FILE_NAME = "config.json";

    public static final String KEY_LOG_ENABLED = "log_enabled";
    public static final String KEY_FRIENDS = "friends";

    // 默认开启文件日志，便于在应用内「日志」页排查问题
    public static final boolean DEF_LOG_ENABLED = true;

    private ConfigHelp() {
    }

    private static File fileOf(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * 读取配置并补齐默认值，文件不存在或解析失败时返回完整默认配置
     */
    public static JSONObject load(Context context) {
        var file = fileOf(context);
        JSONObject json = null;
        if (file.exists()) {
            try (var reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                if (sb.length() > 0) {
                    json = new JSONObject(sb.toString());
                }
            } catch (Exception e) {
                LogHelp.e(TAG, "load config failed: " + e.getMessage(), e);
            }
        }
        if (json == null) {
            json = new JSONObject();
        }
        putIfAbsent(json, KEY_LOG_ENABLED, DEF_LOG_ENABLED);
        if (!json.has(KEY_FRIENDS)) {
            try {
                json.put(KEY_FRIENDS, new JSONArray());
            } catch (Exception ignored) {
                // 忽略
            }
        }
        return json;
    }

    private static void putIfAbsent(JSONObject json, String key, Object value) {
        if (!json.has(key)) {
            try {
                json.put(key, value);
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }

    /**
     * 保存配置到私有目录
     */
    public static void save(Context context, JSONObject json) {
        var file = fileOf(context);
        var dir = file.getParentFile();
        try {
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "create config dir failed: " + e.getMessage(), e);
            return;
        }
        try (var writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(json.toString(2));
            writer.flush();
        } catch (Exception e) {
            LogHelp.e(TAG, "save config failed: " + e.getMessage(), e);
        }
    }

    public static boolean isLogEnabled(Context context) {
        return load(context).optBoolean(KEY_LOG_ENABLED, false);
    }

    public static void setLogEnabled(Context context, boolean enabled) {
        var json = load(context);
        try {
            json.put(KEY_LOG_ENABLED, enabled);
        } catch (Exception ignored) {
            // 忽略
        }
        save(context, json);
    }

    public static JSONArray getFriends(Context context) {
        return load(context).optJSONArray(KEY_FRIENDS);
    }

    public static void setFriends(Context context, JSONArray friends) {
        var json = load(context);
        try {
            json.put(KEY_FRIENDS, friends);
        } catch (Exception ignored) {
            // 忽略
        }
        save(context, json);
    }
}
