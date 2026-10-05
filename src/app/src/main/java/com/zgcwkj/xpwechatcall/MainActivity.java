package com.zgcwkj.xpwechatcall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.zgcwkj.comm.ConfigHelp;
import com.zgcwkj.comm.Friend;
import com.zgcwkj.comm.FriendSync;
import com.zgcwkj.comm.LogHelp;
import com.zgcwkj.comm.ShortcutHelp;
import com.zgcwkj.xpwechatcall.widget.CallWidgetProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：管理好友列表并为好友创建一键通话桌面图标
 */
public class MainActivity extends Activity {

    /** 选择图标请求码 */
    private static final int REQ_PICK_ICON = 1001;

    /** 全部好友，与配置文件内容一致 */
    private final List<Friend> friends = new ArrayList<>();
    /** 按搜索关键字过滤后实际展示的好友 */
    private final List<Friend> shown = new ArrayList<>();
    /** 列表行操作回调 */
    private FriendRow.Listener rowListener;
    /** 好友行容器 */
    private LinearLayout listContainer;
    private View emptyView;
    private View searchEmptyView;
    private View pageHome;
    private View pageLog;
    private View tabHomeIcon;
    private View tabHomeText;
    private View tabLogIcon;
    private View tabLogText;
    private TextView logText;
    private View btnImport;
    private View btnAdd;
    private View btnLogRefresh;
    private View btnLogClear;
    private Switch logSwitch;
    private EditText searchInput;
    private View searchClear;
    /** 列表下边框与底部占位，随列表是否为空切换显示 */
    private View listBottomDivider;
    private View footerSpacer;

    /** 编辑弹窗中的图标预览与当前选中的图标路径 */
    private ImageView dialogIcon;
    private String pendingIconPath;
    private String pendingIconKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        var logDir = new File(getFilesDir(), "logs");
        LogHelp.init(logDir, ConfigHelp.isLogEnabled(this));
        // 沉浸式：内容延伸到状态栏与导航栏之下，再按实际系统栏尺寸给内部控件补内边距
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        applyWindowInsets();

        listContainer = findViewById(R.id.list_container);
        listBottomDivider = findViewById(R.id.list_bottom_divider);
        footerSpacer = findViewById(R.id.footer_spacer);
        emptyView = findViewById(R.id.empty_view);
        searchEmptyView = findViewById(R.id.search_empty_view);
        pageHome = findViewById(R.id.page_home);
        pageLog = findViewById(R.id.page_log);
        tabHomeIcon = findViewById(R.id.tab_home_icon);
        tabHomeText = findViewById(R.id.tab_home_text);
        tabLogIcon = findViewById(R.id.tab_log_icon);
        tabLogText = findViewById(R.id.tab_log_text);
        logText = findViewById(R.id.log_text);
        btnImport = findViewById(R.id.btn_import);
        btnAdd = findViewById(R.id.btn_add);
        btnLogRefresh = findViewById(R.id.btn_log_refresh);
        btnLogClear = findViewById(R.id.btn_log_clear);
        logSwitch = findViewById(R.id.log_switch);

        var textLogPath = (TextView) findViewById(R.id.log_path);
        textLogPath.setText(logDir.getAbsolutePath());

        // 搜索框：输入即过滤，右侧按钮一键清空
        searchInput = findViewById(R.id.search_input);
        searchClear = findViewById(R.id.search_clear);
        searchClear.setOnClickListener(v -> searchInput.setText(""));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh();
            }
        });

        btnAdd.setOnClickListener(v -> showFriendDialog(null));
        btnImport.setOnClickListener(v -> importFromWeChat());
        btnLogRefresh.setOnClickListener(v -> showLog());
        btnLogClear.setOnClickListener(v -> clearLog());
        findViewById(R.id.tab_home).setOnClickListener(v -> switchTab(true));
        findViewById(R.id.tab_log).setOnClickListener(v -> switchTab(false));

        // 文件日志开关：与配置保持一致，切换后立即生效
        logSwitch.setChecked(ConfigHelp.isLogEnabled(this));
        logSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigHelp.setLogEnabled(this, isChecked);
            LogHelp.setEnabled(isChecked);
            LogHelp.i("XpWeChatCall", "file log " + (isChecked ? "enabled" : "disabled"));
            showLog();
        });

        loadFriends();

        rowListener = new FriendRow.Listener() {
            @Override
            public void onShortcut(Friend friend) {
                // 创建前先让用户选择视频或语音通话
                showCallTypeDialog(friend);
            }

            @Override
            public void onEdit(Friend friend) {
                showFriendDialog(friend);
            }

            @Override
            public void onDelete(Friend friend) {
                confirmDelete(friend);
            }
        };
        // 底部版权信息：点击跳转作者主页
        findViewById(R.id.tv_footer).setOnClickListener(v -> startActivity(new Intent(
            Intent.ACTION_VIEW, Uri.parse(getString(R.string.author_url)))));

        switchTab(true);
        refresh();
        LogHelp.i("XpWeChatCall", "main page opened, friends=" + friends.size());
    }

    /**
     * 沉浸式布局
     *
     * 绿色标题栏与白色 Tab 栏铺满到屏幕边缘（含状态栏/导航栏区域），
     * 只有文字与列表内容按系统栏尺寸内缩，这样横屏时系统栏在左右两侧也不会遮住内容。
     */
    private void applyWindowInsets() {
        var root = findViewById(R.id.root);
        var spacer = findViewById(R.id.status_bar_spacer);
        var titleBar = findViewById(R.id.title_bar);
        var content = findViewById(R.id.content);
        var tabBar = findViewById(R.id.tab_bar);
        var tabHeight = getResources().getDimensionPixelSize(R.dimen.tab_bar_height);
        // 标题栏原有的左右内边距，系统栏内边距需叠加在它之上
        var titlePaddingStart = titleBar.getPaddingStart();
        var titlePaddingEnd = titleBar.getPaddingEnd();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // 刘海屏的挖孔区域也计入，避免内容被遮挡
            var bars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            var spacerParams = spacer.getLayoutParams();
            spacerParams.height = bars.top;
            spacer.setLayoutParams(spacerParams);
            titleBar.setPaddingRelative(titlePaddingStart + bars.left, 0,
                titlePaddingEnd + bars.right, 0);
            content.setPadding(bars.left, 0, bars.right, 0);
            tabBar.setPadding(bars.left, 0, bars.right, bars.bottom);
            var tabParams = tabBar.getLayoutParams();
            tabParams.height = tabHeight + bars.bottom;
            tabBar.setLayoutParams(tabParams);
            return insets;
        });
    }

    /**
     * 切换底部Tab
     *
     * @param home true=首页，false=日志
     */
    private void switchTab(boolean home) {
        pageHome.setVisibility(home ? View.VISIBLE : View.GONE);
        pageLog.setVisibility(home ? View.GONE : View.VISIBLE);
        var primary = getResources().getColor(R.color.primary);
        var disabled = getResources().getColor(R.color.text_disabled);
        ((TextView) tabHomeText).setTextColor(home ? primary : disabled);
        ((TextView) tabLogText).setTextColor(home ? disabled : primary);
        tabHomeIcon.setAlpha(home ? 1f : 0.5f);
        tabLogIcon.setAlpha(home ? 0.5f : 1f);

        // 右上角操作随 Tab 切换：首页=导入/添加，日志=刷新/清空
        btnImport.setVisibility(home ? View.VISIBLE : View.GONE);
        btnAdd.setVisibility(home ? View.VISIBLE : View.GONE);
        btnLogRefresh.setVisibility(home ? View.GONE : View.VISIBLE);
        btnLogClear.setVisibility(home ? View.GONE : View.VISIBLE);

        if (!home) {
            showLog();
        }
    }

    /**
     * 显示日志文件内容
     */
    private void showLog() {
        var text = LogHelp.readAll();
        logText.setText(text == null || text.isEmpty() ? getString(R.string.log_empty) : text);
    }

    /**
     * 清空日志
     */
    private void clearLog() {
        var dir = LogHelp.logDir();
        if (dir == null) {
            return;
        }
        var day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(new java.util.Date());
        var file = new java.io.File(dir, day + ".log");
        if (file.exists()) {
            file.delete();
        }
        showLog();
        toast(R.string.log_cleared);
    }

    /**
     * 从配置读取好友列表
     */
    private void loadFriends() {
        friends.clear();
        var array = ConfigHelp.getFriends(this);
        if (array != null) {
            for (var i = 0; i < array.length(); i++) {
                var friend = Friend.fromJson(array.optJSONObject(i));
                if (friend != null && !friend.wxid.isEmpty()) {
                    friends.add(friend);
                }
            }
        }
    }

    /**
     * 保存好友列表到配置
     */
    private void saveFriends() {
        var array = new JSONArray();
        for (var friend : friends) {
            array.put(friend.toJson());
        }
        ConfigHelp.setFriends(this, array);
        // 好友信息或图标变化后同步刷新桌面控件
        CallWidgetProvider.updateAll(this);
    }

    /**
     * 刷新列表显示和空状态
     */
    private void refresh() {
        applyFilter();
        // 复用已有行视图，避免每次输入关键字都重新创建
        for (var i = 0; i < shown.size(); i++) {
            var row = i < listContainer.getChildCount() ? listContainer.getChildAt(i) : null;
            if (row == null) {
                row = FriendRow.inflate(listContainer);
                listContainer.addView(row);
            }
            FriendRow.bind(row, shown.get(i), rowListener);
        }
        // 多出来的行不再展示
        for (var i = shown.size(); i < listContainer.getChildCount(); i++) {
            listContainer.getChildAt(i).setVisibility(View.GONE);
        }
        var noFriends = friends.isEmpty();
        var hasRows = !shown.isEmpty();
        emptyView.setVisibility(noFriends ? View.VISIBLE : View.GONE);
        searchEmptyView.setVisibility(!noFriends && !hasRows ? View.VISIBLE : View.GONE);
        // 没有好友行时隐藏列表与下边框，版权信息始终显示在内容最下面
        listContainer.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        listBottomDivider.setVisibility(hasRows ? View.VISIBLE : View.GONE);
        footerSpacer.setVisibility(hasRows ? View.VISIBLE : View.GONE);
    }

    /**
     * 按搜索框内容重建展示列表
     * 关键字同时匹配备注与 wxid，忽略大小写
     */
    private void applyFilter() {
        var keyword = searchInput.getText().toString().trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (var friend : friends) {
            if (keyword.isEmpty()
                || friend.nickname.toLowerCase(Locale.getDefault()).contains(keyword)
                || friend.wxid.toLowerCase(Locale.getDefault()).contains(keyword)) {
                shown.add(friend);
            }
        }
        searchClear.setVisibility(keyword.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * 选择通话类型后再创建桌面图标
     * 首页点击好友时弹出，用户选择视频或语音后按该类型创建图标
     */
    private void showCallTypeDialog(Friend friend) {
        var options = new CharSequence[]{
            getString(R.string.call_type_video),
            getString(R.string.call_type_voice)
        };
        new AlertDialog.Builder(this)
            .setTitle(friend.nickname)
            .setItems(options, (dialog, which) -> {
                friend.callType = (which == 0) ? ShortcutHelp.CALL_VIDEO : ShortcutHelp.CALL_VOICE;
                saveFriends();
                createShortcut(friend);
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    /**
     * 为好友创建桌面通话图标
     */
    private void createShortcut(Friend friend) {
        var ok = ShortcutHelp.requestShortcut(this, friend);
        toast(ok ? R.string.toast_shortcut_requested : R.string.toast_shortcut_unsupported);
    }

    /**
     * 删除好友前二次确认
     */
    private void confirmDelete(Friend friend) {
        new AlertDialog.Builder(this)
            .setTitle(friend.nickname)
            .setMessage(R.string.action_delete + "?")
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                friends.remove(friend);
                saveFriends();
                refresh();
                toast(R.string.toast_deleted);
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    /**
     * 新增或编辑好友弹窗
     *
     * @param existing 为 null 时表示新增，否则为编辑
     */
    private void showFriendDialog(Friend existing) {
        var view = LayoutInflater.from(this).inflate(R.layout.dialog_friend, null);
        var etNickname = (EditText) view.findViewById(R.id.et_nickname);
        var etWxid = (EditText) view.findViewById(R.id.et_wxid);
        dialogIcon = view.findViewById(R.id.iv_icon);

        // 通话类型不在此处选择，列表只保存好友；创建桌面图标时再选视频或语音
        pendingIconPath = "";
        pendingIconKey = "";
        if (existing != null) {
            etNickname.setText(existing.nickname);
            etWxid.setText(existing.wxid);
            pendingIconPath = existing.iconPath == null ? "" : existing.iconPath;
            pendingIconKey = existing.wxid;
        }
        showDialogIcon(etNickname.getText().toString(), etWxid.getText().toString());

        view.findViewById(R.id.btn_pick_icon).setOnClickListener(v -> pickIcon(etWxid));
        view.findViewById(R.id.btn_reset_icon).setOnClickListener(v -> {
            FriendSync.deleteIcon(pendingIconPath);
            pendingIconPath = "";
            showDialogIcon(etNickname.getText().toString(), etWxid.getText().toString());
        });

        var dialog = new AlertDialog.Builder(this)
            .setTitle(existing == null ? R.string.dialog_add_title : R.string.dialog_edit_title)
            .setView(view)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            var nickname = etNickname.getText().toString().trim();
            var wxid = etWxid.getText().toString().trim();
            if (nickname.isEmpty()) {
                toast(R.string.toast_nickname_required);
                return;
            }
            if (wxid.isEmpty()) {
                toast(R.string.toast_wxid_required);
                return;
            }
            if (existing == null) {
                var friend = new Friend(nickname, wxid, ShortcutHelp.CALL_VIDEO);
                friend.iconPath = pendingIconPath;
                friends.add(friend);
            } else {
                existing.nickname = nickname;
                existing.wxid = wxid;
                existing.iconPath = pendingIconPath;
            }
            saveFriends();
            refresh();
            dialog.dismiss();
        }));
        dialog.show();
    }

    /**
     * 选择自定义图标
     */
    private void pickIcon(EditText etWxid) {
        var key = etWxid.getText().toString().trim();
        pendingIconKey = key.isEmpty() ? "icon" : key;
        try {
            var intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_PICK_ICON);
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "pick icon failed: " + e.getMessage(), e);
            toast(R.string.toast_pick_icon_failed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ICON || resultCode != RESULT_OK || data == null) {
            return;
        }
        var path = FriendSync.saveIcon(this, pendingIconKey, data.getData());
        if (path == null) {
            toast(R.string.toast_pick_icon_failed);
            return;
        }
        FriendSync.deleteIcon(pendingIconPath);
        pendingIconPath = path;
        if (dialogIcon != null) {
            showDialogIcon("", "");
        }
    }

    /**
     * 刷新编辑弹窗中的图标预览
     */
    private void showDialogIcon(String nickname, String wxid) {
        if (dialogIcon == null) {
            return;
        }
        var friend = new Friend(nickname, wxid, ShortcutHelp.CALL_VIDEO);
        friend.iconPath = pendingIconPath;
        var bitmap = FriendSync.loadIcon(this, friend, 0);
        if (bitmap != null) {
            dialogIcon.setImageBitmap(bitmap);
        } else {
            dialogIcon.setImageDrawable(null);
        }
    }

    /**
     * 从微信导入好友
     * 好友数据由微信进程中的 Hook 读取 rcontact 表后回传。
     * MIUI 等系统会拦截微信拉起本应用，因此先由本应用（处于前台）主动拉起微信，
     * 触发 Hook 读取并回传，再轮询等待回传结果。
     */
    private void importFromWeChat() {
        var before = FriendSync.lastSyncTime(this);
        if (!launchWeChatForImport()) {
            toast(R.string.toast_wechat_missing);
            return;
        }
        toast(R.string.toast_import_waiting);
        pollFriendsForImport(before, 0);
    }

    /**
     * 拉起微信主界面，触发 Hook 读取好友并回传
     */
    private boolean launchWeChatForImport() {
        try {
            var intent = new Intent();
            intent.setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "launch wechat for import failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 轮询等待微信回传好友数据，超时后按现有数据导入
     */
    private void pollFriendsForImport(long before, int attempt) {
        if (attempt >= 20) {
            doImportFromWeChat();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (FriendSync.lastSyncTime(this) > before) {
                doImportFromWeChat();
            } else {
                pollFriendsForImport(before, attempt + 1);
            }
        }, 500);
    }

    /**
     * 镜像导入：清空本地好友后完全以微信数据为准
     *
     * 同一 wxid 已选过的通话类型会被保留，避免用户重复设置。
     */
    private void doImportFromWeChat() {
        var json = FriendSync.read(this);
        if (json == null) {
            toast(R.string.toast_import_none);
            return;
        }
        try {
            var array = new JSONObject(json).optJSONArray(FriendSync.KEY_FRIENDS);
            if (array == null || array.length() == 0) {
                toast(R.string.toast_import_none);
                return;
            }
            // 保留已选的通话类型，避免镜像导入后需要重新设置
            var callTypes = new HashMap<String, String>();
            for (var friend : friends) {
                callTypes.put(friend.wxid, friend.callType);
            }
            // 镜像导入：先清空，再以微信数据为准重建
            friends.clear();
            for (var i = 0; i < array.length(); i++) {
                var item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                var username = item.optString(FriendSync.KEY_USERNAME, "");
                if (username.isEmpty()) {
                    continue;
                }
                var name = item.optString(FriendSync.KEY_NAME, username);
                if (name.isEmpty()) {
                    name = username;
                }
                var callType = callTypes.get(username);
                if (callType == null) {
                    callType = ShortcutHelp.CALL_VIDEO;
                }
                friends.add(new Friend(name, username, callType));
            }
            saveFriends();
            refresh();
            toast(getString(R.string.toast_import_done, friends.size()));
        } catch (Exception e) {
            LogHelp.e("XpWeChatCall", "import friends failed: " + e.getMessage(), e);
            toast(R.string.toast_import_none);
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(CharSequence text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
