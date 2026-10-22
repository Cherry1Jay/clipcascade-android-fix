package com.jay.ccbridge;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 合并进 ClipCascade 自身的启动器。
 *
 * 背景：HyperOS 会压制 App 后台的 OnPrimaryClipChangedListener，且
 * ClipboardService 拒绝后台进程读剪贴板（appops 的 allow 也不起作用）。
 * 之前靠 LSPosed 模块在 App 进程里做这些事，现在把逻辑直接编进 App：
 *
 *   ContentProvider.onCreate（进程一启动就执行）
 *     -> 等 ReactContext 就绪
 *     -> 常驻一个 uid=2000 的 app_process 子进程轮询剪贴板（SHELL_UID 被硬编码放行）
 *     -> 内容变化时 emit("onClipboardChange", {content,type}) 给 JS，
 *        交回 App 自己的加密上行通道
 *
 * 不依赖 LSPosed / adb / 悬浮窗，只要 App 进程活着就能后台同步。
 */
public class BridgeProvider extends ContentProvider {

    private static final String TAG = "CCBridge";
    private static final String TMP_DEX = "/data/local/tmp/clip.dex";
    private static final String MAIN_CLASS = "com.jay.readclip.Clip";
    private static final long POLL_MS = 800L;

    private static volatile boolean sStarted = false;
    private static volatile String sLast = null;

    @Override
    public boolean onCreate() {
        if (sStarted) {
            return true;
        }
        sStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boot();
                } catch (Throwable e) {
                    Log.e(TAG, "boot failed", e);
                }
            }
        }, "ccbridge");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void boot() {
        Context ctx = getContext();
        if (ctx != null) {
            try {
                deployDex(ctx);
            } catch (Throwable e) {
                Log.w(TAG, "deploy dex failed: " + e);
            }
        }
        // provider 早于 Application.onCreate，ReactContext 一定还没有，先等
        Object react = null;
        int waited = 0;
        while (react == null) {
            sleepQuiet(2000);
            waited += 2;
            react = reactContext();
            if (waited % 30 == 0) {
                Log.i(TAG, "still waiting for ReactContext (" + waited + "s)");
            }
        }
        Log.i(TAG, "react context ready, start polling");
        pollLoop(react);
    }

    /** 把 assets 里的 clip.dex 放到 shell 能读的位置（Su 由 KernelSU 授权）。 */
    private void deployDex(Context ctx) throws Exception {
        File local = new File(ctx.getFilesDir(), "clip.dex");
        InputStream in = ctx.getAssets().open("clip.dex");
        OutputStream out = new FileOutputStream(local);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
        String cmd = "su -c 'cp " + local.getAbsolutePath() + " " + TMP_DEX
                + " 2>/dev/null; chmod 644 " + TMP_DEX + "'";
        runShell(cmd);
        Log.i(TAG, "dex deployed (" + local.length() + " bytes)");
    }

    /**
     * 常驻轮询：一个 uid=2000 的子进程持续输出 T:<base64>，不反复 fork。
     */
    private void pollLoop(Object react) {
        while (true) {
            Process proc = null;
            try {
                String inner = "CLASSPATH=" + TMP_DEX
                        + " app_process /system/bin " + MAIN_CLASS + " poll " + POLL_MS;
                proc = new ProcessBuilder("/system/bin/sh", "-c",
                        "su 2000 -c \"" + inner + "\"").start();

                BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                boolean baseline = false;
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("T:")) {
                        continue;
                    }
                    String text = new String(
                            Base64.decode(line.substring(2), Base64.NO_WRAP),
                            StandardCharsets.UTF_8);
                    if (!baseline) {
                        // 刚连上时的现状只当基线，不覆盖桌面已有内容
                        sLast = text;
                        baseline = true;
                        Log.i(TAG, "baseline recorded (" + text.length() + " chars)");
                        continue;
                    }
                    if (text.equals(sLast)) {
                        continue;
                    }
                    sLast = text;
                    emit(react, text);
                }
            } catch (Throwable e) {
                Log.w(TAG, "poller restarting: " + e);
            } finally {
                if (proc != null) {
                    try {
                        proc.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
            sleepQuiet(3000);
        }
    }

    /** 反射拿 MainApplication.getReactNativeHost().getReactInstanceManager().getCurrentReactContext()。 */
    private static Object reactContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Application app = (Application) at.getMethod("currentApplication").invoke(null);
            if (app == null) {
                return null;
            }
            Object host = app.getClass().getMethod("getReactNativeHost").invoke(app);
            if (host == null) {
                return null;
            }
            Object im = host.getClass().getMethod("getReactInstanceManager").invoke(host);
            if (im == null) {
                return null;
            }
            return im.getClass().getMethod("getCurrentReactContext").invoke(im);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 等价于 App 自己的 sendEventToJS：emit("onClipboardChange", map)。 */
    private static void emit(Object react, String text) {
        try {
            Class<?> emitterCls = Class.forName(
                    "com.facebook.react.modules.core.DeviceEventManagerModule$RCTDeviceEventEmitter");
            Object emitter = react.getClass()
                    .getMethod("getJSModule", Class.class)
                    .invoke(react, emitterCls);
            Object map = Class.forName("com.facebook.react.bridge.Arguments")
                    .getMethod("createMap")
                    .invoke(null);
            map.getClass().getMethod("putString", String.class, String.class)
                    .invoke(map, "content", text);
            map.getClass().getMethod("putString", String.class, String.class)
                    .invoke(map, "type", "text");
            emitterCls.getMethod("emit", String.class, Object.class)
                    .invoke(emitter, "onClipboardChange", map);
            Log.i(TAG, "emitted onClipboardChange (" + text.length() + " chars)");
        } catch (Throwable e) {
            Log.e(TAG, "emit failed: " + e);
        }
    }

    private static void runShell(String cmd) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).start();
            p.waitFor();
            p.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ContentProvider 其余抽象方法：本 provider 只用来抢进程启动时机，不对外提供数据
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
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
