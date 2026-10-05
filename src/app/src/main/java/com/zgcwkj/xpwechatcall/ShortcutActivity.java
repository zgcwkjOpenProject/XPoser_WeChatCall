package com.zgcwkj.xpwechatcall;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.ShortcutHelp;

import java.io.File;

/**
 * 桌面快捷方式入口（透明界面）
 * 点击图标后由本 Activity 读取好友信息，并把通话请求转发给微信进程，
 * 微信进程中的 Hook 读取 Intent 附加信息后拉起视频通话。
 */
public class ShortcutActivity extends Activity {

    private static final String TAG = "XpWeChatCall";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogHelp.init(new File(getFilesDir(), "logs"), ConfigHelp.isLogEnabled(this));

        var intent = getIntent();
        var wxid = intent.getStringExtra(ShortcutHelp.EXTRA_WXID);
        if (wxid == null || wxid.isEmpty()) {
            finish();
            return;
        }
        var nickname = intent.getStringExtra(ShortcutHelp.EXTRA_NICKNAME);
        var callType = intent.getStringExtra(ShortcutHelp.EXTRA_CALL_TYPE);
        launchWeChat(wxid, nickname, callType);
        finish();
    }

    /**
     * 拉起微信并把通话参数透传给微信进程中的 Hook
     */
    private void launchWeChat(String wxid, String nickname, String callType) {
        var target = new Intent();
        target.setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        target.putExtra(ShortcutHelp.EXTRA_WXID, wxid);
        target.putExtra(ShortcutHelp.EXTRA_NICKNAME, nickname);
        target.putExtra(ShortcutHelp.EXTRA_CALL_TYPE, callType == null ? ShortcutHelp.CALL_VIDEO : callType);

        var isVideo = !ShortcutHelp.CALL_VOICE.equals(callType);
        try {
            startActivity(target);
            LogHelp.i(TAG, "launch wechat wxid=" + wxid + " video=" + isVideo);
            Toast.makeText(this,
                getString(R.string.toast_shortcut_started,
                    getString(isVideo ? R.string.shortcut_type_video : R.string.shortcut_type_voice)),
                Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogHelp.e(TAG, "launch wechat failed: " + e.getMessage(), e);
            Toast.makeText(this, R.string.toast_shortcut_failed, Toast.LENGTH_LONG).show();
        }
    }
}
