package com.zgcwkj.xpwechatcall.hook;

import android.app.Application;
import android.content.Context;

import com.zgcwkj.comm.FriendSync;
import com.zgcwkj.comm.LogHelp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信好友列表读取
 *
 * 实现方式：微信使用 WCDB（com.tencent.wcdb.database.SQLiteDatabase）访问
 * EnMicroMsg.db，好友数据存放在 rcontact 表。模块挂钩 SQLiteDatabase 的构造函数，
 * 捕获微信已打开的主库连接（该连接内部已持有解密密钥），
 * 再用其 rawQuery 执行自己的查询，把结果通过 ContentProvider 回传给本应用。
 *
 * 该方式不依赖解密数据库、无需 root，rcontact 表名与字段名跨版本稳定。
 *
 * 注意：微信使用 Tinker 热修复，运行时类由 Application 上下文的
 * DelegateLastClassLoader 加载，与 lpparam.classLoader 不是同一个，
 * 因此所有类级 Hook 必须使用 context.getClassLoader() 安装，否则不会生效。
 */
public class ContactHook {

    private static final String TAG = "XpWeChatCall";

    /** 微信数据库文件特征 */
    private static final String DB_NAME = "EnMicroMsg.db";

    /** WCDB 数据库类 */
    private static final String CLS_SQLITE_DB = "com.tencent.wcdb.database.SQLiteDatabase";

    /** 微信主界面类名 */
    private static final String CLS_LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI";

    /** 好友查询 SQL：只取需要字段，限制条数避免大列表卡顿 */
    private static final String CONTACT_SQL =
        "select username, conRemark, nickname, verifyFlag, deleteFlag, chatroomFlag, type "
            + "from rcontact limit 5000";

    /**
     * 需要排除的微信系统账号
     */
    private static final Set<String> SYSTEM_ACCOUNTS = new HashSet<>(Arrays.asList(
        "filehelper", "newsapp", "fmessage", "weibo", "qqmail", "tmessage", "qmessage",
        "floatbottle", "shakeapp", "medianote", "weixin", "officialaccounts",
        "notification_messages", "weixinreminder", "exhelper", "weishu", "voiceinputapp",
        "linkedinplugin", "notifymessage", "appbrandcustomerservicemsg", "mphelper",
        "blogapp", "facebookapp", "qqfriend", "feedsapp", "bottle", "masssendapp",
        "helper_entry", "newlaunchapp", "pcclient", "ipadclient", "macclient",
        "lbsapp", "voipapp", "voicevoipapp", "downloaderapp", "opencustomerservicemsg",
        "service_officialaccounts", "appbrand_notify_message", "schedule_message",
        "weapp", "weixinlens", "tenpay", "wangyiyun", "readtemplate", "snsapp", "shopapp",
        "wxpay", "searchapp", "tmassistant", "appbrandnotifymessage"
    ));

    /** 抽取进行中标记，避免重复调度 */
    private static final AtomicBoolean EXTRACTING = new AtomicBoolean(false);

    /** 微信应用上下文，用于回传数据 */
    private static volatile Context sAppContext;

    /** 已捕获的 WCDB 主库连接（弱引用，避免阻止 GC） */
    private static final java.util.List<java.lang.ref.WeakReference<Object>> CAPTURED_DBS =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 类级 Hook 是否已安装 */
    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean(false);

    /**
     * 安装 Hook
     */
    public void hook() {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sAppContext = (Context) param.args[0];
                    installHooks();
                }
            });
        log("contact hook installed, waiting for app context");
    }

    /**
     * 用运行时类加载器安装 Hook
     */
    private void installHooks() {
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        // 必须使用运行时类加载器：微信使用 Tinker 热修复，
        // lpparam.classLoader 与运行时类加载器不是同一个，用它挂钩不会生效
        var loader = sAppContext.getClassLoader();
        log("installing hooks with loader: " + loader);
        hookDatabaseOpen(loader);
        hookLauncherResume(loader);
    }

    /**
     * 把好友头像回传给本应用
     *
     * 微信进程可读取自身头像缓存，逐个头像通过 ContentProvider 传给本应用落盘，
     * 供创建桌面图标时使用。
     */
    private void pushAvatars(Map<String, Entry> friends) {
        var ctx = sAppContext;
        var count = 0;
        for (var username : friends.keySet()) {
            try {
                var file = findAvatarFile(username);
                if (file == null) {
                    continue;
                }
                var data = java.nio.file.Files.readAllBytes(file.toPath());
                var extras = new android.os.Bundle();
                extras.putString(FriendSync.KEY_AVATAR_WXID, username);
                extras.putByteArray(FriendSync.KEY_AVATAR_DATA, data);
                var result = ctx.getContentResolver().call(
                    FriendSync.CONTENT_URI, FriendSync.METHOD_PUSH_AVATAR, null, extras);
                if (result != null && result.getBoolean("ok")) {
                    count++;
                }
            } catch (Throwable e) {
                log("push avatar " + username + " failed: " + e);
            }
        }
        log("pushed avatars=" + count);
    }

    /**
     * 查找好友头像文件
     *
     * 微信头像缓存有两种结构（均在 MicroMsg/&lt;用户哈希&gt;/avatar/&lt;md5前2位&gt;/&lt;md5第3-4位&gt;/ 下）：
     *   user_hd_&lt;md5&gt;.png / user_&lt;md5&gt;.png
     *   &lt;md5&gt;/small_&lt;hash&gt;（JPEG）
     */
    private File findAvatarFile(String username) throws Exception {
        var ctx = sAppContext;
        var hash = md5(username);
        var microMsg = new File(ctx.getDataDir(), "MicroMsg");
        var children = microMsg.listFiles();
        if (children == null) {
            return null;
        }
        for (var child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            var sub = new File(new File(new File(child, "avatar"), hash.substring(0, 2)),
                hash.substring(2, 4));
            if (!sub.isDirectory()) {
                continue;
            }
            for (var name : new String[]{"user_hd_" + hash + ".png", "user_" + hash + ".png"}) {
                var file = new File(sub, name);
                if (file.isFile() && file.length() > 0) {
                    return file;
                }
            }
            // 小头像放在以 md5 命名的子目录中，文件名形如 small_<hash>
            var inner = new File(sub, hash).listFiles();
            if (inner != null) {
                for (var file : inner) {
                    if (file.isFile() && file.getName().startsWith("small_") && file.length() > 0) {
                        return file;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 计算字符串的 MD5 十六进制值
     */
    private static String md5(String text) throws Exception {
        var digest = java.security.MessageDigest.getInstance("MD5");
        var bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var sb = new StringBuilder();
        for (var b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 挂钩数据库打开方法，捕获微信已打开的主库连接
     */
    private void hookDatabaseOpen(ClassLoader loader) {
        var clazz = XposedHelpers.findClass(CLS_SQLITE_DB, loader);
        XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                captureDatabase(param.thisObject);
            }
        });
        // 微信可能在本模块加载前就已打开主库，此时构造函数不会再触发，
        // 因此额外挂钩常用实例方法，从已存在的连接上捕获
        for (var method : clazz.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!CAPTURE_METHODS.contains(method.getName())) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    captureDatabase(param.thisObject);
                }
            });
        }
        log("sqlite database hooked");
    }

    /** 用于从已存在连接捕获实例的常用方法名 */
    private static final java.util.Set<String> CAPTURE_METHODS = new java.util.HashSet<>(Arrays.asList(
        "rawQuery", "rawQueryWithFactory", "query", "queryWithFactory",
        "execSQL", "insert", "update", "delete", "replace", "getPath"
    ));

    /**
     * 记录捕获到的数据库连接，命中微信主库时触发抽取
     */
    private void captureDatabase(Object db) {
        synchronized (CAPTURED_DBS) {
            for (var ref : CAPTURED_DBS) {
                if (ref.get() == db) {
                    return;
                }
            }
            CAPTURED_DBS.add(new java.lang.ref.WeakReference<>(db));
        }
        var path = dbPathOf(db);
        if (path.contains(DB_NAME)) {
            log("captured main db: " + path);
            scheduleExtract();
        }
    }

    /**
     * 读取数据库文件路径
     */
    private String dbPathOf(Object db) {
        return (String) XposedHelpers.callMethod(db, "getPath");
    }

    /**
     * 微信主界面恢复时触发抽取（此时库已完成初始化）
     */
    private void hookLauncherResume(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(CLS_LAUNCHER_UI, loader,
            "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    scheduleExtract();
                }
            });
    }

    /**
     * 启动一次抽取（异步、节流、可重试）
     *
     * 每次导入都希望拿到最新数据，因此不做一次性限制，仅按时间节流。
     */
    private void scheduleExtract() {
        var now = System.currentTimeMillis();
        if (now - sLastExtractTime < 3000 || !EXTRACTING.compareAndSet(false, true)) {
            return;
        }
        sLastExtractTime = now;
        new Thread(() -> {
            try {
                // 等待微信完成数据库初始化
                Thread.sleep(1500);
                extractFriends();
            } catch (Throwable e) {
                log("extract failed: " + e);
            } finally {
                EXTRACTING.set(false);
            }
        }, "xp-extract-friends").start();
    }

    /** 上次抽取时间，用于节流 */
    private static volatile long sLastExtractTime;

    /**
     * 用捕获的主库连接查询好友并回传
     */
    private void extractFriends() {
        for (var db : snapshotDatabases()) {
            var path = dbPathOf(db);
            if (!path.contains(DB_NAME)) {
                continue;
            }
            try {
                var cursor = XposedHelpers.callMethod(db, "rawQuery", CONTACT_SQL, new Object[0]);
                try {
                    if (readCursor(cursor)) {
                        return;
                    }
                } finally {
                    XposedHelpers.callMethod(cursor, "close");
                }
            } catch (Throwable e) {
                log("query via " + path + " failed: " + e);
            }
        }
    }

    /**
     * 已捕获的数据库连接快照
     */
    private java.util.List<Object> snapshotDatabases() {
        var list = new java.util.ArrayList<Object>();
        synchronized (CAPTURED_DBS) {
            for (var ref : CAPTURED_DBS) {
                var db = ref.get();
                if (db != null) {
                    list.add(db);
                }
            }
        }
        return list;
    }

    /**
     * 读取游标中的好友数据并回传
     *
     * @return 是否读取到好友
     */
    private boolean readCursor(Object cursor) {
        var count = (int) XposedHelpers.callMethod(cursor, "getCount");
        if (count <= 0) {
            log("cursor empty");
            return false;
        }
        var indexUser = columnIndex(cursor, "username");
        var indexRemark = columnIndex(cursor, "conRemark");
        var indexNick = columnIndex(cursor, "nickname");
        var indexVerify = columnIndex(cursor, "verifyFlag");
        var indexDelete = columnIndex(cursor, "deleteFlag");
        var indexChatroom = columnIndex(cursor, "chatroomFlag");
        var indexType = columnIndex(cursor, "type");

        var friends = new LinkedHashMap<String, Entry>();
        for (var row = 0; row < count; row++) {
            XposedHelpers.callMethod(cursor, "moveToPosition", row);
            var username = getString(cursor, indexUser);
            if (username == null || username.isEmpty()) {
                continue;
            }
            var verifyFlag = getInt(cursor, indexVerify);
            var deleteFlag = getInt(cursor, indexDelete);
            var chatroomFlag = getInt(cursor, indexChatroom);
            var type = getInt(cursor, indexType);
            if (!isFriend(username, verifyFlag, deleteFlag, chatroomFlag, type)) {
                continue;
            }
            var entry = new Entry();
            entry.username = username;
            entry.remark = getString(cursor, indexRemark);
            entry.nickname = getString(cursor, indexNick);
            friends.put(username, entry);
        }
        log("scanned rows=" + count + ", friends=" + friends.size());
        if (friends.isEmpty()) {
            return false;
        }
        push(friends);
        pushAvatars(friends);
        return true;
    }

    /**
     * 好友判定：仅保留真实好友
     *
     * 实测 rcontact.type（微信 8.0.77）：
     *   0x1  = 好友位（真实好友与部分系统账号都会置位，需配合用户名判断）
     *   0x8  = 公众号
     *   0x20 = 服务号
     *   0x4  = 群成员/非好友（数量最多，未置好友位）
     * 群聊靠用户名后缀 @chatroom 区分，不能依赖 type 位。
     */
    private boolean isFriend(String username, int verifyFlag, int deleteFlag,
                             int chatroomFlag, int type) {
        if (deleteFlag != 0 || chatroomFlag != 0) {
            return false;
        }
        // 群聊、公众号、陌生人等用户名均含 @
        if (username.contains("@")) {
            return false;
        }
        // 公众号
        if (username.startsWith("gh_")) {
            return false;
        }
        if ((verifyFlag & 8) != 0) {
            return false;
        }
        // 必须置位好友标志
        if ((type & 1) == 0) {
            return false;
        }
        // 排除公众号、服务号
        if ((type & 8) != 0 || (type & 0x20) != 0) {
            return false;
        }
        return !SYSTEM_ACCOUNTS.contains(username);
    }

    /**
     * 把好友数据回传给本应用
     */
    private void push(Map<String, Entry> friends) {
        var context = sAppContext;
        try {
            var array = new JSONArray();
            for (var entry : friends.values()) {
                var name = entry.remark;
                if (name.isEmpty()) {
                    name = entry.nickname;
                }
                if (name.isEmpty()) {
                    name = entry.username;
                }
                var item = new JSONObject();
                item.put(FriendSync.KEY_USERNAME, entry.username);
                item.put(FriendSync.KEY_NAME, name);
                item.put(FriendSync.KEY_REMARK, entry.remark);
                item.put(FriendSync.KEY_NICKNAME, entry.nickname);
                array.put(item);
            }
            var root = new JSONObject();
            root.put(FriendSync.KEY_TIME, System.currentTimeMillis());
            root.put(FriendSync.KEY_FRIENDS, array);
            var json = root.toString();
            var result = context.getContentResolver().call(
                FriendSync.CONTENT_URI, FriendSync.METHOD_PUSH, json, null);
            if (result != null && result.getBoolean("ok")) {
                log("pushed " + array.length() + " friends");
            } else {
                log("push failed: provider returned null/false");
            }
        } catch (Throwable e) {
            log("push friends failed: " + e);
        }
    }

    private int columnIndex(Object cursor, String name) {
        return (int) XposedHelpers.callMethod(cursor, "getColumnIndex", name);
    }

    /** 读取字符串列，SQL NULL 返回空串 */
    private String getString(Object cursor, int index) {
        var value = (String) XposedHelpers.callMethod(cursor, "getString", index);
        return value == null ? "" : value;
    }

    private int getInt(Object cursor, int index) {
        return (int) XposedHelpers.callMethod(cursor, "getInt", index);
    }

    /**
     * 好友条目
     */
    private static class Entry {
        String username;
        String remark;
        String nickname;
    }

    private void log(String message) {
        LogHelp.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
