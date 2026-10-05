package com.zgcwkj.comm;

import org.json.JSONObject;

/**
 * 好友信息模型
 */
public class Friend {

    public String nickname;
    public String wxid;
    public String callType;
    /** 自定义图标文件路径，为空时使用微信头像 */
    public String iconPath;

    public Friend(String nickname, String wxid, String callType) {
        this.nickname = nickname == null ? "" : nickname;
        this.wxid = wxid == null ? "" : wxid;
        this.callType = (callType == null || callType.isEmpty()) ? ShortcutHelp.CALL_VIDEO : callType;
    }

    /**
     * 是否视频通话，非语音均按视频处理
     */
    public boolean isVideo() {
        return !ShortcutHelp.CALL_VOICE.equals(callType);
    }

    public JSONObject toJson() {
        var json = new JSONObject();
        try {
            json.put("nickname", nickname);
            json.put("wxid", wxid);
            json.put("call_type", callType);
            json.put("icon", iconPath == null ? "" : iconPath);
        } catch (Exception ignored) {
            // 忽略
        }
        return json;
    }

    public static Friend fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        var friend = new Friend(
            json.optString("nickname", ""),
            json.optString("wxid", ""),
            json.optString("call_type", ShortcutHelp.CALL_VIDEO));
        friend.iconPath = json.optString("icon", "");
        return friend;
    }
}
