package com.zgcwkj.xpwechatcall;

import android.graphics.drawable.BitmapDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.zgcwkj.comm.Friend;
import com.zgcwkj.comm.FriendSync;

/**
 * 好友列表行：负责好友数据的视图绑定
 */
public class FriendRow {

    /**
     * 行操作回调
     */
    public interface Listener {
        void onShortcut(Friend friend);

        void onEdit(Friend friend);

        void onDelete(Friend friend);
    }

    /**
     * 创建一行好友视图
     */
    public static View inflate(ViewGroup parent) {
        return LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend, parent, false);
    }

    /**
     * 把好友数据绑定到行视图上
     * 整行点击=创建通话图标，长按=编辑好友
     */
    public static void bind(View view, Friend friend, Listener listener) {
        var avatar = (TextView) view.findViewById(R.id.avatar);
        var icon = FriendSync.loadIcon(view.getContext(), friend, 0);
        if (icon != null) {
            avatar.setText("");
            avatar.setBackground(new BitmapDrawable(view.getResources(), icon));
        } else {
            avatar.setText(firstChar(friend.nickname));
            avatar.setBackgroundResource(R.drawable.bg_avatar);
        }
        ((TextView) view.findViewById(R.id.nickname)).setText(friend.nickname);
        ((TextView) view.findViewById(R.id.wxid)).setText(friend.wxid);
        view.findViewById(R.id.btn_shortcut).setOnClickListener(v -> listener.onShortcut(friend));
        view.findViewById(R.id.btn_edit).setOnClickListener(v -> listener.onEdit(friend));
        view.findViewById(R.id.btn_delete).setOnClickListener(v -> listener.onDelete(friend));
        view.setOnClickListener(v -> listener.onShortcut(friend));
        view.setOnLongClickListener(v -> {
            listener.onEdit(friend);
            return true;
        });
        view.setVisibility(View.VISIBLE);
    }

    private static String firstChar(String name) {
        if (name == null || name.isEmpty()) {
            return "微";
        }
        return name.substring(0, 1);
    }
}
