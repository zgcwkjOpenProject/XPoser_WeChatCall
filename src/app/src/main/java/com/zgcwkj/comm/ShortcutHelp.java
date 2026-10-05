package com.zgcwkj.comm;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;

/**
 * 桌面快捷方式工具
 * 优先使用系统固定快捷方式接口，不支持时回退到旧版广播方式
 */
public class ShortcutHelp {

    private static final String TAG = "XpWeChatCall";

    /** 快捷方式 Intent 中携带的额外参数 */
    public static final String EXTRA_WXID = "xp_wxid";
    public static final String EXTRA_NICKNAME = "xp_nickname";
    public static final String EXTRA_CALL_TYPE = "xp_call_type";

    /** 通话类型 */
    public static final String CALL_VIDEO = "video";
    public static final String CALL_VOICE = "voice";

    private ShortcutHelp() {
    }

    /**
     * 为好友创建桌面快捷方式，返回是否已发起创建请求
     */
    public static boolean requestShortcut(Context context, Friend friend) {
        var target = buildTarget(context, friend);
        var icon = buildIcon(context, friend);
        var label = friend.nickname;
        try {
            var manager = (ShortcutManager) context.getSystemService(Context.SHORTCUT_SERVICE);
            if (manager != null && manager.isRequestPinShortcutSupported()) {
                var info = new ShortcutInfo.Builder(context, shortcutId(friend))
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(Icon.createWithBitmap(icon))
                    .setIntent(target)
                    .build();
                return manager.requestPinShortcut(info, null);
            }
        } catch (Throwable e) {
            LogHelp.e(TAG, "requestPinShortcut failed: " + e.getMessage(), e);
        }
        return legacyShortcut(context, friend, target, icon, label);
    }

    /**
     * 旧版广播方式创建快捷方式，兼容不支持固定快捷方式的桌面
     */
    @SuppressWarnings("deprecation")
    private static boolean legacyShortcut(Context context, Friend friend, Intent target, Bitmap icon, String label) {
        try {
            var intent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, target);
            intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, label);
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
            intent.putExtra("duplicate", false);
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable e) {
            LogHelp.e(TAG, "legacy shortcut failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 构建快捷方式点击后跳转的 Intent，指向本模块的转发 Activity
     */
    private static Intent buildTarget(Context context, Friend friend) {
        var intent = new Intent();
        intent.setClassName(context.getPackageName(), "com.zgcwkj.xpwechatcall.ShortcutActivity");
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_WXID, friend.wxid);
        intent.putExtra(EXTRA_NICKNAME, friend.nickname);
        intent.putExtra(EXTRA_CALL_TYPE, friend.callType);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    /**
     * 生成快捷方式图标：优先使用好友头像，取不到时按通话类型绘制首字图标
     */
    private static Bitmap buildIcon(Context context, Friend friend) {
        // 自定义图标优先，否则使用微信头像，都取不到时绘制首字图标
        var avatar = FriendSync.loadIcon(context, friend, 144);
        if (avatar != null) {
            return avatar;
        }
        var size = 144;
        var bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        var canvas = new Canvas(bitmap);
        var bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 视频用微信绿，语音用蓝色，方便桌面上区分
        bg.setColor(friend.isVideo()
            ? Color.parseColor("#07C160")
            : Color.parseColor("#2F80ED"));
        canvas.drawRect(0f, 0f, size, size, bg);

        var text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size * 0.45f);
        text.setFakeBoldText(true);
        var metrics = text.getFontMetrics();
        var y = size * 0.46f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(firstChar(friend.nickname), size / 2f, y, text);
        return bitmap;
    }

    private static String firstChar(String nickname) {
        if (nickname == null || nickname.isEmpty()) {
            return "微";
        }
        return nickname.substring(0, 1);
    }

    private static String shortcutId(Friend friend) {
        // 同一好友的视频和语音是两个不同图标，ID 需包含类型
        return "xp_call_" + Math.abs(friend.wxid.hashCode()) + "_" + friend.callType;
    }
}
