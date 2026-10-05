package com.zgcwkj.xpwechatcall.voip;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.TextView;

import com.zgcwkj.comm.LogHelp;

import de.robv.android.xposed.XposedBridge;

/**
 * 通过模拟点击发起通话
 *
 * 微信发起通话需要三步操作：
 *   1. 点击聊天页输入框右侧「+」，展开功能面板
 *   2. 点击面板中的「视频通话」，弹出通话类型选择框
 *   3. 在对话框中选择「视频通话」或「语音通话」
 *
 * 只依赖界面文案与官方点击接口，不依赖混淆类名与内部参数。
 */
public class UiCallHelper {

    private static final String TAG = "XpWeChatCall";

    /** 微信聊天页类名 */
    private static final String CLS_CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI";

    /** 功能面板入口按钮的描述 */
    private static final String DESC_MORE = "更多功能";

    /** 面板中的通话入口文案 */
    private static final String TEXT_CALL_ENTRY = "视频通话";

    /** 待发起的通话 */
    private static volatile String sWxid;
    private static volatile boolean sVideo;

    /** 当前步骤与重试次数 */
    private static volatile int sStep;
    private static volatile int sRetry;

    private UiCallHelper() {
    }

    /**
     * 打开好友聊天页并发起通话
     */
    public static void startCall(Activity activity, String wxid, boolean video) {
        sWxid = wxid;
        sVideo = video;
        sStep = 0;
        sRetry = 0;
        try {
            var intent = new Intent();
            intent.setClassName("com.tencent.mm", CLS_CHATTING_UI);
            intent.putExtra("Chat_User", wxid);
            activity.startActivity(intent);
            log("open chat ok, wxid=" + wxid + " video=" + video);
        } catch (Throwable e) {
            log("open chat failed: " + e);
        }
    }

    /**
     * 聊天页 onResume 时调用，开始执行分步点击
     * 用户自己打开聊天页时 sWxid 为空，此时不做任何处理
     */
    public static void onChatResumed(Activity activity) {
        if (sWxid == null || sStep < 0) {
            return;
        }
        var step = sStep;
        sStep = -1;
        postDelayed(() -> nextStep(activity, step), 1500);
    }

    /**
     * 执行指定步骤，找不到目标时重试
     */
    private static void nextStep(Activity activity, int step) {
        if (sRetry > 12) {
            log("give up at step " + step);
            sStep = -1;
            return;
        }
        var root = activity.getWindow().getDecorView();
        var done = false;

        if (step == 0) {
            var more = findByDesc(root, DESC_MORE);
            if (more != null) {
                click(more);
                log("step0: clicked + button");
                done = true;
            }
        } else if (step == 1) {
            var entry = findByText(root, TEXT_CALL_ENTRY);
            if (entry != null) {
                click(entry);
                log("step1: clicked call entry");
                done = true;
            }
        } else {
            // 对话框是独立窗口，需跨窗口查找，避免误点面板中的同名项
            var label = sVideo ? "视频通话" : "语音通话";
            var option = findLastByText(label);
            if (option != null) {
                click(option);
                log("step2: clicked " + label);
                sStep = -1;
                return;
            }
        }

        sRetry = done ? 0 : sRetry + 1;
        var next = done ? step + 1 : step;
        postDelayed(() -> nextStep(activity, next), done ? 800 : 400);
    }

    /**
     * 点击元素：依次尝试自身、可点击祖先、列表条目
     */
    private static void click(View target) {
        try {
            if (target.isClickable() && target.performClick()) {
                return;
            }
            for (var current = target; current != null; ) {
                if (current.isClickable() && current.performClick()) {
                    return;
                }
                var parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            var adapterView = findAdapterAncestor(target);
            if (adapterView != null) {
                var child = findDirectChild(adapterView, target);
                if (child != null) {
                    var index = ((ViewGroup) adapterView).indexOfChild(child);
                    var adapter = ((AdapterView<?>) adapterView).getAdapter();
                    var id = adapter == null ? index : adapter.getItemId(index);
                    ((AdapterView<?>) adapterView).performItemClick(child, index, id);
                }
            }
        } catch (Throwable e) {
            log("click failed: " + e);
        }
    }

    /**
     * 按文字查找可见节点
     */
    private static View findByText(View view, String text) {
        if (view == null) {
            return null;
        }
        if (view instanceof TextView) {
            var value = ((TextView) view).getText();
            if (value != null && text.contentEquals(value) && isVisible(view)) {
                return view;
            }
        }
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (var i = 0; i < group.getChildCount(); i++) {
                var found = findByText(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 按描述查找可见节点
     */
    private static View findByDesc(View view, String desc) {
        if (view == null) {
            return null;
        }
        var value = view.getContentDescription();
        if (value != null && value.toString().contains(desc) && isVisible(view)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (var i = 0; i < group.getChildCount(); i++) {
                var found = findByDesc(group.getChildAt(i), desc);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 跨窗口查找最后一个匹配文字的节点（对话框为独立窗口，位于列表末端）
     */
    private static View findLastByText(String text) {
        View result = null;
        for (var root : allRootViews()) {
            var found = lastByText(root, text);
            if (found != null) {
                result = found;
            }
        }
        return result;
    }

    private static View lastByText(View view, String text) {
        if (view == null) {
            return null;
        }
        View result = null;
        if (view instanceof TextView) {
            var value = ((TextView) view).getText();
            if (value != null && text.contentEquals(value) && isVisible(view)) {
                result = view;
            }
        }
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (var i = 0; i < group.getChildCount(); i++) {
                var found = lastByText(group.getChildAt(i), text);
                if (found != null) {
                    result = found;
                }
            }
        }
        return result;
    }

    /**
     * 收集当前进程所有窗口的根视图
     */
    private static java.util.List<View> allRootViews() {
        var roots = new java.util.ArrayList<View>();
        try {
            var cls = Class.forName("android.view.WindowManagerGlobal");
            var instance = cls.getMethod("getInstance").invoke(null);
            var field = cls.getDeclaredField("mRoots");
            field.setAccessible(true);
            var list = (java.util.List<?>) field.get(instance);
            if (list != null) {
                for (var item : list) {
                    try {
                        var view = item.getClass().getMethod("getView").invoke(item);
                        if (view instanceof View) {
                            roots.add((View) view);
                        }
                    } catch (Throwable ignored) {
                        // 跳过该窗口
                    }
                }
            }
        } catch (Throwable e) {
            log("allRootViews failed: " + e);
        }
        return roots;
    }

    /**
     * 节点是否可见且在屏幕内
     */
    private static boolean isVisible(View view) {
        if (view.getVisibility() != View.VISIBLE) {
            return false;
        }
        var w = view.getWidth();
        var h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        var root = view.getRootView();
        var loc = new int[2];
        view.getLocationOnScreen(loc);
        var rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        var x = loc[0] - rootLoc[0];
        var y = loc[1] - rootLoc[1];
        return x + w > 0 && x < root.getWidth() && y + h > 0 && y < root.getHeight();
    }

    private static View findAdapterAncestor(View view) {
        for (var current = view; current != null; ) {
            if (current instanceof AdapterView) {
                return current;
            }
            var parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static View findDirectChild(View container, View target) {
        for (var current = target; current != null; ) {
            var parent = current.getParent();
            if (parent == container) {
                return current;
            }
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static void postDelayed(Runnable action, long delay) {
        new Handler(Looper.getMainLooper()).postDelayed(action, delay);
    }

    private static void log(String message) {
        LogHelp.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
