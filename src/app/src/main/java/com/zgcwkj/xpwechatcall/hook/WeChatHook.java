package com.zgcwkj.xpwechatcall.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.ShortcutHelp;
import com.zgcwkj.xpwechatcall.voip.UiCallHelper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信 Hook
 * 拦截微信主界面启动/复用时的 Intent，若携带本模块的通话参数则直接发起视频/语音通话
 */
public class WeChatHook {

    private static final String TAG = "XpWeChatCall";

    /** 微信主界面类名，稳定且可被外部拉起 */
    private static final String LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI";

    /** 微信聊天页类名 */
    private static final String CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI";

    public void hook() {
        // 微信使用 Tinker 热修复，运行时类由 context 的类加载器加载，
        // 与 lpparam.classLoader 不同，因此必须等应用上下文就绪后再挂钩
        XposedHelpers.findAndHookMethod(android.app.Application.class, "attach",
            android.content.Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 必须使用运行时类加载器：微信使用 Tinker 热修复，
                    // lpparam.classLoader 与运行时类加载器不是同一个，用它挂钩不会生效
                    var context = (android.content.Context) param.args[0];
                    installLauncherHooks(context.getClassLoader());
                }
            });
        log("wechat hook installed, waiting for app context");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED =
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 用运行时类加载器挂钩 LauncherUI
     */
    private void installLauncherHooks(ClassLoader loader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        var clazz = XposedHelpers.findClass(LAUNCHER_UI, loader);

        XposedHelpers.findAndHookMethod(clazz, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                var activity = (Activity) param.thisObject;
                handle(activity, activity.getIntent());
            }
        });

        XposedHelpers.findAndHookMethod(clazz, "onNewIntent", Intent.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                handle((Activity) param.thisObject, (Intent) param.args[0]);
            }
        });

        log("hooked " + LAUNCHER_UI + " via " + loader);

        // 聊天页恢复时执行模拟点击，发起通话
        XposedHelpers.findAndHookMethod(CHATTING_UI, loader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                UiCallHelper.onChatResumed((Activity) param.thisObject);
            }
        });
        log("hooked " + CHATTING_UI + ".onResume");
    }

    /**
     * 处理携带通话参数的 Intent，读取参数后清理标记避免重复触发
     */
    private void handle(Activity activity, Intent intent) {
        var wxid = intent.getStringExtra(ShortcutHelp.EXTRA_WXID);
        if (wxid == null || wxid.isEmpty()) {
            return;
        }
        var callType = intent.getStringExtra(ShortcutHelp.EXTRA_CALL_TYPE);

        // 清理参数，避免微信复用同一 Intent 时重复发起通话
        intent.removeExtra(ShortcutHelp.EXTRA_WXID);
        intent.removeExtra(ShortcutHelp.EXTRA_NICKNAME);
        intent.removeExtra(ShortcutHelp.EXTRA_CALL_TYPE);

        var video = !ShortcutHelp.CALL_VOICE.equals(callType);
        log("start call wxid=" + wxid + " video=" + video);
        UiCallHelper.startCall(activity, wxid, video);
    }

    private void log(String message) {
        LogHelp.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
