package com.jay.cchook;

import android.content.ClipData;
import android.content.ClipDescription;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * 让 ClipCascade 在后台也能同步剪贴板（modern libxposed API 102，注入 App 进程）。
 *
 * 排查结论（LSPosed 2.2.1 + HyperOS / Android 17）：
 *   1. legacy(api 82) 模块进不了 system_server；modern 模块在我们的模块上也没被注入进去，
 *      因此不走 system_server 路线；
 *   2. App 前台能读（ClipboardManager.getPrimaryClip），后台读会被 ClipboardService 拒绝；
 *   3. 更关键的是后台连"剪贴板变了"的事件都没有：HyperOS 抑制了后台的
 *      OnPrimaryClipChangedListener 回调，所以监听形同虚设（官方靠 READ_LOGS 读 logcat，
 *      Android 15+ 已失效）。
 *
 * 做法：
 *   - 等 App 的 ClipboardListenerModule 实例出现后，起一个自己的轮询线程；
 *   - 每 800ms 用 root 起 uid=2000 的 app_process 读剪贴板（SHELL_UID 被硬编码放行）；
 *   - 内容变了就用反射构造 {"content": 文本, "type": "text"}，直接调 App 自己的
 *     sendEventToJS(...)，让 JS 层当作原生回调处理，走它原有的加密上行。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "CCHook";
    private static final String TARGET = "com.clipcascade";
    private static final String SU = "/system/bin/su";
    private static final String REMOTE_READ =
            "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip read";
    private static final long POLL_MS = 800L;

    private static volatile Object sModule = null;
    private static volatile Method sSendEvent = null;
    private static volatile String sLastEmitted = null;
    private static volatile boolean sThreadStarted = false;
    private static volatile ClassLoader sLoader = null;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET.equals(param.getPackageName())) {
            return;
        }
        sLoader = param.getDefaultClassLoader();
        log(4, TAG, "target loaded, installing hooks");
        hookListenerModule();
        hookClipboardManager();
        startPoller();
    }

    /** 抓住模块实例与 sendEventToJS 方法；顺便把 stopListening 拦掉。 */
    private void hookListenerModule() {
        try {
            Class<?> cls = Class.forName("com.clipcascade.ClipboardListenerModule", false, sLoader);
            for (Method m : cls.getDeclaredMethods()) {
                String name = m.getName();
                if ("sendEventToJS".equals(name)) {
                    m.setAccessible(true);
                    sSendEvent = m;
                } else if ("stopListening".equals(name)) {
                    m.setAccessible(true);
                    hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) {
                            log(4, TAG, "blocked stopListening()");
                            return null;
                        }
                    });
                }
            }
            for (java.lang.reflect.Constructor<?> c : cls.getDeclaredConstructors()) {
                c.setAccessible(true);
                hook(c).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        sModule = chain.getThisObject();
                        log(4, TAG, "module instance captured");
                        return r;
                    }
                });
            }
            log(4, TAG, "listener module hooked, sendEventToJS=" + sSendEvent);
        } catch (Throwable t) {
            log(6, TAG, "listener module hook failed: " + t);
        }
    }

    /** 读取入口：原生读不到就用 root 代读。 */
    private void hookClipboardManager() {
        try {
            Class<?> cls = Class.forName("android.content.ClipboardManager", false, sLoader);
            for (Method m : cls.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.startsWith("getPrimaryClip")) {
                    continue;
                }
                hook(m).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String mn = chain.getExecutable().getName();
                        try {
                            Object r = chain.proceed();
                            if (r != null) {
                                return r;
                            }
                        } catch (Throwable t) {
                            log(4, TAG, mn + " denied: " + t);
                        }
                        String text = readViaRoot();
                        if (text == null || text.isEmpty()) {
                            return chain.proceed();
                        }
                        if (mn.endsWith("Description")) {
                            return new ClipDescription("clipcascade",
                                    new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN});
                        }
                        return ClipData.newPlainText("clipcascade", text);
                    }
                });
            }
        } catch (Throwable t) {
            log(6, TAG, "clipboard manager hook failed: " + t);
        }
    }

    /** 常驻一个 uid=2000 的轮询进程，逐行读它的输出，代替被系统压制的监听回调。 */
    private void startPoller() {
        if (sThreadStarted) {
            return;
        }
        sThreadStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Process p = null;
                    try {
                        p = Runtime.getRuntime().exec(new String[]{SU, "2000", "-c",
                                "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin "
                                        + "com.jay.readclip.Clip poll " + POLL_MS});
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                        boolean baselineDone = false;
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!line.startsWith("T:")) {
                                continue;
                            }
                            String text = new String(android.util.Base64.decode(line.substring(2),
                                    android.util.Base64.NO_WRAP), StandardCharsets.UTF_8);
                            if (!baselineDone) {
                                // 刚连上时的现状只当基线，不推给桌面
                                sLastEmitted = text;
                                baselineDone = true;
                                log(4, TAG, "baseline recorded (" + text.length() + " chars)");
                                continue;
                            }
                            if (text.equals(sLastEmitted)) {
                                continue;
                            }
                            if (sModule != null && sSendEvent != null) {
                                sSendEvent.invoke(sModule, buildEvent(text));
                                sLastEmitted = text;
                                log(4, TAG, "emitted onClipboardChange (" + text.length() + " chars)");
                            }
                        }
                    } catch (Throwable e) {
                        log(6, TAG, "poller restarting: " + e);
                    } finally {
                        if (p != null) {
                            p.destroy();
                        }
                    }
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "cchook-poller");
        t.setDaemon(true);
        t.start();
        log(4, TAG, "poller started");
    }

    /** 反射构造 {"content": text, "type": "text"}，不依赖 RN 的编译期类型。 */
    private static Object buildEvent(String text) throws Exception {
        Class<?> argsCls = Class.forName("com.facebook.react.bridge.Arguments", true, sLoader);
        Object map = argsCls.getMethod("createMap").invoke(null);
        Class<?> writable = Class.forName("com.facebook.react.bridge.WritableMap", false, sLoader);
        Method putString = writable.getMethod("putString", String.class, String.class);
        putString.invoke(map, "content", text);
        putString.invoke(map, "type", "text");
        return map;
    }

    /** 用 root 起 uid=2000 的 app_process 读剪贴板，返回文本。 */
    private static String readViaRoot() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{SU, "2000", "-c", REMOTE_READ});
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroy();
                return null;
            }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line != null && line.startsWith("T:")) {
                return new String(android.util.Base64.decode(line.substring(2),
                        android.util.Base64.NO_WRAP), StandardCharsets.UTF_8);
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }
}
