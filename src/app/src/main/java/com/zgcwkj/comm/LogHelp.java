package com.zgcwkj.comm;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一处理应用日志输出
 * 始终输出到系统 logcat，并始终追加写入应用私有目录日志文件（仅保留最近 100 行）。
 */
public class LogHelp {

    private static volatile boolean sEnabled = false;
    private static volatile File sLogDir = null;

    /** 日志文件保留的最大行数 */
    private static final int MAX_LINES = 100;

    private LogHelp() {
    }

    /**
     * 初始化日志目录和文件日志开关
     * 由主界面和快捷方式入口在应用进程内调用；Hook 进程不写文件，仅输出 logcat
     */
    public static void init(File logDir, boolean enabled) {
        sLogDir = logDir;
        sEnabled = enabled;
    }

    /**
     * 动态切换文件日志开关
     */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static void i(String tag, String message) {
        log(Log.INFO, tag, message, null);
    }

    public static void w(String tag, String message) {
        log(Log.WARN, tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        log(Log.ERROR, tag, message, throwable);
    }

    /**
     * 按日志类型输出到系统日志，并在开关开启时写入本地文件
     */
    public static void log(int priority, String tag, String message, Throwable throwable) {
        if (throwable == null) {
            Log.println(priority, tag, message);
        } else {
            Log.println(priority, tag, message + "\n" + Log.getStackTraceString(throwable));
        }
        writeFileLog(priority, tag, message, throwable);
    }

    /**
     * 追加写入当日日志文件，任何异常都静默忽略，避免日志本身影响主流程
     */
    private static void writeFileLog(int priority, String tag, String message, Throwable throwable) {
        var dir = sLogDir;
        if (!sEnabled || dir == null) {
            return;
        }
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            var day = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
            var file = new File(dir, day + ".log");
            var time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            var sb = new StringBuilder();
            sb.append(time).append(" ").append(levelName(priority)).append("/").append(tag).append(": ").append(message);
            if (throwable != null) {
                sb.append("\n").append(Log.getStackTraceString(throwable));
            }
            sb.append("\n");
            try (var writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                writer.write(sb.toString());
            }
            trim(file);
        } catch (Throwable ignored) {
            // 日志失败不影响业务
        }
    }

    /**
     * 只保留最近 MAX_LINES 行，避免日志无限增长
     */
    private static void trim(File file) {
        try {
            var lines = new java.util.ArrayList<String>();
            try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            if (lines.size() <= MAX_LINES) {
                return;
            }
            var keep = lines.subList(lines.size() - MAX_LINES, lines.size());
            try (var writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                for (var line : keep) {
                    writer.write(line);
                    writer.write("\n");
                }
            }
        } catch (Throwable ignored) {
            // 截断失败不影响业务
        }
    }

    /**
     * 读取当前日志文件内容，供日志页面展示
     */
    public static String readAll() {
        var dir = sLogDir;
        if (dir == null) {
            return "";
        }
        try {
            var day = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
            var file = new File(dir, day + ".log");
            if (!file.exists()) {
                return "";
            }
            var sb = new StringBuilder();
            try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 日志文件目录，供日志页面显示路径
     */
    public static File logDir() {
        return sLogDir;
    }

    private static String levelName(int priority) {
        switch (priority) {
            case Log.INFO:
                return "I";
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            default:
                return "?";
        }
    }
}
