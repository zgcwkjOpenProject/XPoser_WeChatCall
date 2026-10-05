package com.zgcwkj.xpwechatcall.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.Friend;
import com.zgcwkj.comm.FriendSync;
import com.zgcwkj.comm.ShortcutHelp;
import com.zgcwkj.xpwechatcall.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面控件配置页
 *
 * 拖出控件后弹出，选择好友与通话类型，确定后写入控件配置并刷新显示。
 */
public class WidgetConfigActivity extends Activity {

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private final List<Friend> friends = new ArrayList<>();
    private int selectedIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 用户中途取消时控件不会被添加
        setResult(RESULT_CANCELED);
        appWidgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID);
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        setContentView(R.layout.activity_widget_config);

        loadFriends();
        if (friends.isEmpty()) {
            Toast.makeText(this, R.string.widget_no_friends, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        var listView = (ListView) findViewById(R.id.widget_friend_list);
        listView.setAdapter(new FriendAdapter());
        listView.setOnItemClickListener((parent, view, position, id) -> {
            selectedIndex = position;
            ((BaseAdapter) parent.getAdapter()).notifyDataSetChanged();
        });

        var typeGroup = (RadioGroup) findViewById(R.id.widget_call_type);
        findViewById(R.id.widget_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.widget_confirm).setOnClickListener(v -> {
            if (selectedIndex < 0) {
                Toast.makeText(this, R.string.widget_pick_friend, Toast.LENGTH_SHORT).show();
                return;
            }
            var friend = friends.get(selectedIndex);
            var callType = typeGroup.getCheckedRadioButtonId() == R.id.widget_type_voice
                ? ShortcutHelp.CALL_VOICE : ShortcutHelp.CALL_VIDEO;
            CallWidgetProvider.saveConfig(this, appWidgetId, friend.wxid, friend.nickname, callType);
            var manager = AppWidgetManager.getInstance(this);
            CallWidgetProvider.updateWidget(this, manager, appWidgetId, layoutIdOf());
            setResult(RESULT_OK, new Intent().putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId));
            finish();
        });
    }

    /**
     * 当前控件对应的布局
     */
    private int layoutIdOf() {
        var info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId);
        if (info == null || info.provider == null) {
            return R.layout.widget_call_1x1;
        }
        var name = info.provider.getClassName();
        if (name.endsWith("Widget2x2Provider")) {
            return R.layout.widget_call_2x2;
        }
        if (name.endsWith("Widget4x4Provider")) {
            return R.layout.widget_call_4x4;
        }
        return R.layout.widget_call_1x1;
    }

    /**
     * 读取已保存的好友列表
     */
    private void loadFriends() {
        var array = ConfigHelp.getFriends(this);
        if (array == null) {
            return;
        }
        for (var i = 0; i < array.length(); i++) {
            var friend = Friend.fromJson(array.optJSONObject(i));
            if (friend != null && !friend.wxid.isEmpty()) {
                friends.add(friend);
            }
        }
    }

    /**
     * 好友选择列表适配器
     */
    private class FriendAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return friends.size();
        }

        @Override
        public Object getItem(int position) {
            return friends.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            var view = convertView != null ? convertView
                : getLayoutInflater().inflate(R.layout.item_widget_friend, parent, false);
            var friend = friends.get(position);
            var avatar = (ImageView) view.findViewById(R.id.wf_avatar);
            var bitmap = FriendSync.loadIcon(WidgetConfigActivity.this, friend, 120);
            if (bitmap != null) {
                avatar.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
            } else {
                avatar.setImageDrawable(null);
            }
            var name = (TextView) view.findViewById(R.id.wf_name);
            name.setText(friend.nickname);
            name.setTextColor(getResources().getColor(
                position == selectedIndex ? R.color.primary : R.color.text_primary));
            return view;
        }
    }
}
