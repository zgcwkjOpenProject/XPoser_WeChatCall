package com.zgcwkj.xpwechatcall;

import com.zgcwkj.xpwechatcall.hook.ContactHook;
import com.zgcwkj.xpwechatcall.hook.WeChatHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 模块入口
 * 仅需 Hook 微信进程，用于接收桌面快捷方式透传的通话请求
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "XpWeChatCall";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);
        if ("com.tencent.mm".equals(lpparam.packageName)) {
            new WeChatHook().hook();
            new ContactHook().hook();
        }
    }
}
