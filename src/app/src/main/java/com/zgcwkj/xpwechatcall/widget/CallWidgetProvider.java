package com.zgcwkj.xpwechatcall.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.Friend;
import com.zgcwkj.comm.FriendSync;
import com.zgcwkj.comm.ShortcutHelp;
import com.zgcwkj.xpwechatcall.R;

/**
 * 好友通话桌面控件
 *
 * 每个控件绑定一位好友，点击直接发起配置时确定的视频或语音通话。
 * 1x1 / 2x2 / 4x4 三种尺寸由子类提供各自布局。
 */
public class CallWidgetProvider extends AppWidgetProvider {

    /** 控件配置存储 */
    private static final String PREFS = "widget_config";
    private static final String KEY_WXID = "wxid_";
    private static final String KEY_NAME = "name_";
    private static final String KEY_TYPE = "type_";

    /** 子类返回各自的布局 */
    protected int layoutId() {
        return R.layout.widget_call_1x1;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (var id : ids) {
            updateWidget(context, manager, id, layoutId());
        }
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        var editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (var id : ids) {
            editor.remove(KEY_WXID + id);
            editor.remove(KEY_NAME + id);
            editor.remove(KEY_TYPE + id);
        }
        editor.apply();
    }

    /**
     * 刷新单个控件
     */
    public static void updateWidget(Context context, AppWidgetManager manager, int id, int layoutId) {
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var wxid = prefs.getString(KEY_WXID + id, "");
        var name = prefs.getString(KEY_NAME + id, "");
        var callType = prefs.getString(KEY_TYPE + id, ShortcutHelp.CALL_VIDEO);

        var views = new RemoteViews(context.getPackageName(), layoutId);
        views.setTextViewText(R.id.widget_name, name);
        var friend = new Friend(name, wxid, callType);
        // 该好友若在应用内改过图片，则显示自定义图标；否则回退到微信头像
        var saved = findSavedFriend(context, wxid);
        if (saved != null && saved.iconPath != null) {
            friend.iconPath = saved.iconPath;
        }
        var icon = FriendSync.loadIcon(context, friend, 512);
        if (icon != null) {
            views.setImageViewBitmap(R.id.widget_icon, icon);
        } else {
            // 无图标时清空图片，仅显示卡片与名字
            views.setImageViewBitmap(R.id.widget_icon, null);
        }
        views.setOnClickPendingIntent(R.id.widget_root,
            buildClickIntent(context, wxid, name, callType, id));
        manager.updateAppWidget(id, views);
    }

    /**
     * 刷新所有尺寸的控件（好友信息或图标变化后调用）
     */
    public static void updateAll(Context context) {
        var manager = AppWidgetManager.getInstance(context);
        refresh(context, manager, Widget1x1Provider.class, R.layout.widget_call_1x1);
        refresh(context, manager, Widget2x2Provider.class, R.layout.widget_call_2x2);
        refresh(context, manager, Widget4x4Provider.class, R.layout.widget_call_4x4);
    }

    private static void refresh(Context context, AppWidgetManager manager,
                                Class<?> provider, int layoutId) {
        var ids = manager.getAppWidgetIds(
            new android.content.ComponentName(context, provider));
        for (var id : ids) {
            updateWidget(context, manager, id, layoutId);
        }
    }

    /**
     * 从应用配置中按 wxid 查找好友，用于读取其自定义图标路径
     */
    private static Friend findSavedFriend(Context context, String wxid) {
        if (wxid == null || wxid.isEmpty()) {
            return null;
        }
        var array = ConfigHelp.getFriends(context);
        if (array == null) {
            return null;
        }
        for (var i = 0; i < array.length(); i++) {
            var friend = Friend.fromJson(array.optJSONObject(i));
            if (friend != null && wxid.equals(friend.wxid)) {
                return friend;
            }
        }
        return null;
    }

    /**
     * 点击控件后由 ShortcutActivity 转发给微信发起通话
     */
    private static PendingIntent buildClickIntent(Context context, String wxid, String name,
                                                  String callType, int widgetId) {
        var intent = new Intent();
        intent.setClassName(context.getPackageName(), "com.zgcwkj.xpwechatcall.ShortcutActivity");
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(ShortcutHelp.EXTRA_WXID, wxid);
        intent.putExtra(ShortcutHelp.EXTRA_NICKNAME, name);
        intent.putExtra(ShortcutHelp.EXTRA_CALL_TYPE, callType);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, widgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 保存控件配置
     */
    static void saveConfig(Context context, int id, String wxid, String name, String callType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WXID + id, wxid)
            .putString(KEY_NAME + id, name)
            .putString(KEY_TYPE + id, callType)
            .apply();
    }
}
