# XPWeChatCall - 微信快捷通话

[![Android](https://img.shields.io/badge/Android-11+-blue)](https://www.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-supported-green)](https://modules.lsposed.org)
[![XposedModule](https://img.shields.io/badge/XposedModule-repo-green)](https://github.com/Xposed-Modules-Repo)

通过 Xposed 模块，在桌面为好友创建独立图标，点击图标一步直达微信视频/语音通话

## 原理

模块为每个好友生成一个桌面快捷方式，快捷方式点击后由模块的透明入口 `ShortcutActivity` 接收，
再通过微信主界面 `LauncherUI` 把「好友 wxid + 通话类型」透传给微信进程；
注入到 `com.tencent.mm` 的 Hook 拦截 `LauncherUI` 的 `onCreate` / `onNewIntent`，
读取参数后打开该好友聊天页，并按界面文案模拟点击完成通话

```text
桌面好友图标
  -> ShortcutActivity（模块进程，读取好友配置）
  -> 拉起 com.tencent.mm/.ui.LauncherUI，附带 wxid 与通话参数
  -> WeChatHook 拦截 LauncherUI onCreate/onNewIntent
  -> UiCallHelper 打开聊天页，依次点击「+」与「视频通话」
  -> 微信弹出选择框，按需选择视频/语音通话
```

Hook 边界选择稳定且可被外部拉起的 `com.tencent.mm.ui.LauncherUI`，不依赖混淆的业务函数；
发起通话采用模拟点击，只依赖界面文案，跨版本适应性更好

## 功能

- 在模块内维护好友列表（备注、wxid、视频/语音）
- **从微信直接读取好友列表，无需手动填写 wxid**
- **导入为镜像导入：完全以微信好友为准，替换本地列表**
- **首页支持搜索好友，关键字同时匹配备注与 wxid**
- 为每个好友一键创建桌面图标，图标默认使用微信好友头像，也可在编辑弹窗中自选图片
- 点击桌面图标即对好友发起视频通话或语音通话
- **支持桌面控件（1×1 / 2×2 / 4×4），绑定好友后点击直接通话**
- 支持快捷方式固定（`ShortcutManager`）与旧版广播两种创建方式
- 支持写入运行日志，便于排查

## 图片预览

![001](imgs/001.jpg?20260711)

![002](imgs/002.jpg?20260711)

更多图片：[imgs](imgs/README.md)

## 环境要求

- Android 11+（minSdk 30）
- 已安装 Xposed 框架（LSPosed / EdXposed 等）
- 已安装微信
- 支持的 Xposed 作用域：`com.tencent.mm`

## 使用步骤

1. 安装并激活模块，在 LSPosed 中勾选作用域 `com.tencent.mm`，重启微信
2. 打开「微信快捷通话」App，点击右上角「添加好友」
3. 填写好友备注与好友 wxid，选择视频通话或语音通话，保存
4. 点击好友右侧「创建图标」，在桌面确认添加
5. 之后点击桌面图标即可直接对该好友发起通话

### 如何获取好友 wxid

点击主界面右上角「导入好友」即可自动读取微信好友列表，无需手动填写 wxid。

导入原理：微信使用 WCDB 访问 `EnMicroMsg.db`，好友数据存放在 `rcontact` 表。
模块挂钩 WCDB 的数据库构造函数捕获已打开的主库连接（内部已持有密钥），
再用其 `rawQuery` 查询 `rcontact`，按 `type` 位标志与用户名过滤出真实好友，
最后通过 `ContentProvider` 回传本应用。该方式不依赖解密数据库、无需 root。

使用步骤：

1. 确认模块已在 LSPosed 中对 `com.tencent.mm` 启用，并已重启微信
2. 打开本应用，点击「导入好友」
3. 应用会自动拉起微信触发读取，等待片刻后完成镜像导入

若仍需要手动获取 wxid（例如导入失败时），可通过以下方式：

- 使用微信数据库导出/查看类工具（如 WeChatMsg 等）从聊天记录中获取
- 若好友已设置微信号，部分工具会同时显示其 wxid

## 项目结构

```text
app/src/main/java/com/zgcwkj/
  comm/
    ConfigHelp.java       配置读写（应用私有目录 config.json）
    LogHelp.java          日志输出（logcat + 可选文件）
    Friend.java           好友数据模型
    FriendSync.java       好友数据回传通道常量与落盘
    ShortcutHelp.java     桌面快捷方式创建与图标生成
  xpwechatcall/
    XposedEntry.java      Xposed 入口
    MainActivity.java     好友列表管理界面
    FriendAdapter.java    好友列表适配器
    FriendProvider.java   好友数据接收 Provider
    ShortcutActivity.java 快捷方式入口，向微信转发通话请求
    hook/
      WeChatHook.java     拦截微信主界面，触发通话
      ContactHook.java    捕获 WCDB 主库连接，读取微信好友列表
    voip/
      UiCallHelper.java   打开聊天页并模拟点击发起通话
    widget/
      CallWidgetProvider.java 桌面控件基类，绑定好友并刷新显示
      Widget1x1Provider.java  1×1 尺寸控件
      Widget2x2Provider.java  2×2 尺寸控件
      Widget4x4Provider.java  4×4 尺寸控件
      WidgetConfigActivity.java 控件配置页（选择好友与通话类型）
app/src/main/res/
  layout/                 界面布局
  values/                 字符串、颜色、Xposed 作用域
  xml/                    桌面控件描述文件
```

## 实现说明

| 模块 | 作用 |
| --- | --- |
| `ShortcutHelp` | 生成带好友信息的桌面快捷方式，图标优先使用好友头像或自定义图片 |
| `ShortcutActivity` | 透明入口，读取好友配置后拉起微信主界面并透传参数 |
| `WeChatHook` | 拦截 `LauncherUI` 生命周期，识别通话参数并清理标记 |
| `ContactHook` | 捕获 WCDB 主库连接，从 `rcontact` 表读取并过滤好友列表 |
| `UiCallHelper` | 打开好友聊天页，按界面文案模拟点击「+」与通话按钮 |
| `CallWidgetProvider` | 桌面控件，显示好友头像与名字，点击直接发起通话 |

## 兼容性说明

模块**不限定微信版本**，代码中没有任何版本判断。依赖项按风险从低到高：

| 风险 | 依赖项 | 失效表现 |
| --- | --- | --- |
| 低 | `com.tencent.wcdb.database.SQLiteDatabase`、`rcontact` 表及列名、`LauncherUI` / `ChattingUI` 类名与 `Chat_User` 参数 | 日志记录查询或挂钩失败 |
| 中 | 头像缓存路径 `MicroMsg/<用户哈希>/avatar/...`、`type` 位标志（`0x1` 好友 / `0x8` 公众号 / `0x20` 服务号） | 头像回退为首字图标；好友过滤数量异常 |
| 较高 | 界面文案「更多功能」「视频通话」「语音通话」 | 日志记录 `give up at step N` |

- `type` 位标志按微信 8.0.77 实测得出，换版本可能需要重新确认
- 界面文案匹配同时意味着微信语言须为中文
- 排查时开启日志查看 `/data/data/com.zgcwkj.xpwechatcall/files/logs/` 中的运行记录

## 编译

需要 JDK 17 和 Android SDK（compileSdk 36）。

```bash
cd src
gradlew assembleDebug
```

调试 APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装后在 Xposed/LSPosed 中启用模块，作用域勾选 `com.tencent.mm`，并重启微信

## 许可证

[Apache License 2.0](LICENSE)
