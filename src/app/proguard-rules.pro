# Xposed 相关
-keep class com.zgcwkj.xpwechatcall.XposedEntry { *; }
-keep class com.zgcwkj.xpwechatcall.hook.** { *; }
-keep class com.zgcwkj.xpwechatcall.voip.** { *; }

# 保留快捷方式入口
-keep class com.zgcwkj.xpwechatcall.ShortcutActivity { *; }

# 保留好友数据接收 Provider
-keep class com.zgcwkj.xpwechatcall.FriendProvider { *; }
