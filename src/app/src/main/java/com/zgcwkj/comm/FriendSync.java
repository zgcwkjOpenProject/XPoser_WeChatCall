package com.zgcwkj.comm;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 微信好友同步数据通道
 * Hook 运行在微信进程，无法直接写入本应用私有目录，因此通过本应用的
 * ContentProvider 把好友数据回传；Provider 收到后落盘到应用私有文件，
 * 本应用再读取该文件展示导入结果。
 */
public class FriendSync {

    /** ContentProvider 授权标识，与 AndroidManifest 保持一致 */
    public static final String AUTHORITY = "com.zgcwkj.xpwechatcall.friends";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    /** 回传好友数据的方法名 */
    public static final String METHOD_PUSH = "push_friends";
    /** 回传好友头像的方法名 */
    public static final String METHOD_PUSH_AVATAR = "push_avatar";
    /** 好友数据落盘文件名 */
    public static final String FILE_NAME = "wechat_friends.json";
    /** 头像存放目录名 */
    public static final String AVATAR_DIR = "avatars";
    /** 自定义图标存放目录名 */
    public static final String ICON_DIR = "icons";

    /** JSON 字段 */
    public static final String KEY_FRIENDS = "friends";
    public static final String KEY_TIME = "time";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_NAME = "name";
    public static final String KEY_REMARK = "remark";
    public static final String KEY_NICKNAME = "nickname";

    /** 头像回传字段 */
    public static final String KEY_AVATAR_WXID = "wxid";
    public static final String KEY_AVATAR_DATA = "data";

    private FriendSync() {
    }

    /**
     * 好友数据文件位置
     */
    public static File fileOf(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * 好友头像文件位置
     */
    public static File avatarFileOf(Context context, String wxid) {
        var safe = wxid == null ? "" : wxid.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(new File(context.getFilesDir(), AVATAR_DIR), safe + ".png");
    }

    /**
     * 保存好友头像，返回是否成功
     */
    public static boolean saveAvatar(Context context, String wxid, byte[] data) {
        if (wxid == null || wxid.isEmpty() || data == null || data.length == 0) {
            return false;
        }
        try {
            var dir = new File(context.getFilesDir(), AVATAR_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            try (var out = new FileOutputStream(avatarFileOf(context, wxid))) {
                out.write(data);
                out.flush();
            }
            return true;
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "save avatar failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 读取好友头像并裁剪为正方形，不存在时返回 null
     *
     * @param size 输出边长，<=0 表示不缩放
     */
    public static android.graphics.Bitmap loadAvatar(Context context, String wxid, int size) {
        return decodeSquare(avatarFileOf(context, wxid), size);
    }

    /**
     * 保存用户选择的图标，返回文件路径，失败返回 null
     */
    public static String saveIcon(Context context, String key, android.net.Uri uri) {
        if (key == null || key.isEmpty() || uri == null) {
            return null;
        }
        try {
            var dir = new File(context.getFilesDir(), ICON_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            var safe = key.replaceAll("[^A-Za-z0-9_-]", "_");
            var file = new File(dir, safe + "_" + System.currentTimeMillis() + ".png");
            try (var in = context.getContentResolver().openInputStream(uri);
                 var out = new FileOutputStream(file)) {
                if (in == null) {
                    return null;
                }
                var buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "save icon failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 删除自定义图标文件
     */
    public static void deleteIcon(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            new File(path).delete();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /**
     * 加载好友图标：优先使用自定义图片，否则使用微信头像
     *
     * @param size 输出边长，<=0 表示不缩放
     */
    public static android.graphics.Bitmap loadIcon(Context context, Friend friend, int size) {
        if (friend.iconPath != null && !friend.iconPath.isEmpty()) {
            var custom = decodeSquare(new File(friend.iconPath), size);
            if (custom != null) {
                return custom;
            }
        }
        return loadAvatar(context, friend.wxid, size);
    }

    /**
     * 读取图片文件并裁剪为正方形，失败返回 null
     */
    private static android.graphics.Bitmap decodeSquare(File file, int size) {
        try {
            if (!file.isFile() || file.length() == 0) {
                return null;
            }
            var src = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            if (src == null) {
                return null;
            }
            var edge = Math.min(src.getWidth(), src.getHeight());
            if (edge <= 0) {
                return null;
            }
            var cropped = android.graphics.Bitmap.createBitmap(src,
                (src.getWidth() - edge) / 2, (src.getHeight() - edge) / 2, edge, edge);
            if (size <= 0) {
                return cropped;
            }
            return android.graphics.Bitmap.createScaledBitmap(cropped, size, size, true);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 保存好友 JSON，返回是否成功
     */
    public static boolean save(Context context, String json) {
        try {
            try (var writer = new OutputStreamWriter(
                new FileOutputStream(fileOf(context)), StandardCharsets.UTF_8)) {
                writer.write(json);
                writer.flush();
            }
            return true;
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "save friends failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 读取好友 JSON，不存在时返回 null
     */
    public static String read(Context context) {
        var file = fileOf(context);
        if (!file.exists()) {
            return null;
        }
        try (var reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "read friends failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 好友数据最后更新时间，未同步过返回 0
     */
    public static long lastSyncTime(Context context) {
        var file = fileOf(context);
        return file.exists() ? file.lastModified() : 0L;
    }
}
