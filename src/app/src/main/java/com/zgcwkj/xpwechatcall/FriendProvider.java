package com.zgcwkj.xpwechatcall;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import com.zgcwkj.comm.FriendSync;
import com.zgcwkj.comm.LogHelp;

/**
 * 好友数据接收 Provider
 * 运行在微信进程的 Hook 通过 call() 把好友 JSON 回传给本应用，
 * 这里落盘到应用私有文件，供主界面读取导入。
 * 仅接受来自微信（com.tencent.mm）的调用，避免被其它应用写入伪造数据。
 */
public class FriendProvider extends ContentProvider {

    private static final String TAG = "XpWeChatCall";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isFromWeChat()) {
            LogHelp.w(TAG, "reject push from uid=" + Binder.getCallingUid());
            return null;
        }
        var result = new Bundle();
        if (FriendSync.METHOD_PUSH.equals(method) && arg != null && !arg.isEmpty()) {
            var ok = FriendSync.save(getContext(), arg);
            LogHelp.i(TAG, "friend push received, ok=" + ok + ", size=" + arg.length());
            result.putBoolean("ok", ok);
            return result;
        }
        if (FriendSync.METHOD_PUSH_AVATAR.equals(method) && extras != null) {
            var wxid = extras.getString(FriendSync.KEY_AVATAR_WXID);
            var data = extras.getByteArray(FriendSync.KEY_AVATAR_DATA);
            var ok = FriendSync.saveAvatar(getContext(), wxid, data);
            result.putBoolean("ok", ok);
            return result;
        }
        return null;
    }

    /**
     * 校验调用方是否为微信
     */
    private boolean isFromWeChat() {
        try {
            var context = getContext();
            if (context == null) {
                return false;
            }
            var packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
            if (packages != null) {
                for (var pkg : packages) {
                    if (WECHAT_PACKAGE.equals(pkg)) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            LogHelp.e(TAG, "check caller failed: " + e.getMessage(), e);
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
