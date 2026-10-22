package com.jay.readclip;

import android.content.ClipData;
import android.content.ClipDescription;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 以 shell/root 身份直接和 clipboard 服务对话。
 *   Clip read        读取当前剪贴板，输出 T:<base64> 或 N:<mime>
 *   Clip write TEXT  写入剪贴板
 *   Clip poll [ms]   长驻轮询，内容变化时按行输出 T:<base64>
 */
public class Clip {

    private static Object sIcb;
    private static Method sGet;
    private static Method sSet;
    private static Method sGetDescM;

    private static void init() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object binder = sm.getMethod("getService", String.class).invoke(null, "clipboard");
        if (binder == null) throw new IllegalStateException("no clipboard service");
        Class<?> ibinder = Class.forName("android.os.IBinder");
        Class<?> stub = Class.forName("android.content.IClipboard$Stub");
        sIcb = stub.getMethod("asInterface", ibinder).invoke(null, binder);

        Class<?>[] getArgs = {String.class, String.class, int.class, int.class};
        Class<?>[] setArgs = {ClipData.class, String.class, String.class, int.class, int.class};
        for (Method m : sIcb.getClass().getMethods()) {
            if (m.getName().equals("getPrimaryClip")
                    && Arrays.equals(m.getParameterTypes(), getArgs)) sGet = m;
            if (m.getName().equals("setPrimaryClip")
                    && Arrays.equals(m.getParameterTypes(), setArgs)) sSet = m;
        }
        for (Method m : sIcb.getClass().getMethods()) {
            if (m.getName().equals("getPrimaryClipDescription")
                    && Arrays.equals(m.getParameterTypes(), getArgs)) sGetDescM = m;
        }
        if (sGet == null || sSet == null || sGetDescM == null) throw new IllegalStateException("methods not found");
    }

    private static String pkg() {
        return "com.android.shell";
    }

    private static ClipData read() throws Exception {
        return (ClipData) sGet.invoke(sIcb, pkg(), null, 0, 0);
    }

    private static String sGetDesc() {
        try {
            Object d = sGetDescM.invoke(sIcb, pkg(), null, 0, 0);
            if (d == null) return "null";
            ClipDescription cd = (ClipDescription) d;
            StringBuilder sb = new StringBuilder(cd.getLabel() + " [");
            for (int i = 0; i < cd.getMimeTypeCount(); i++) {
                if (i > 0) sb.append(',');
                sb.append(cd.getMimeType(i));
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    private static String encode(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    /** 返回 "T:<b64>" 或 "N:<mime>" 或 "EMPTY" */
    private static String snapshot() {
        try {
            ClipData cd = read();
            if (cd == null || cd.getItemCount() == 0) return "EMPTY";
            CharSequence t = cd.getItemAt(0).getText();
            if (t != null) {
                return "T:" + encode(t.toString().getBytes(StandardCharsets.UTF_8));
            }
            ClipDescription d = cd.getDescription();
            String mime = d != null && d.getMimeTypeCount() > 0 ? d.getMimeType(0) : "unknown";
            return "N:" + mime;
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            return "E:" + c.getClass().getSimpleName() + ":" + c.getMessage();
        }
    }

    public static void main(String[] args) {
        String cmd = args.length > 0 ? args[0] : "read";
        try {
            init();
            if ("read".equals(cmd)) {
                System.out.println(snapshot());
            } else if ("write".equals(cmd)) {
                String text = args.length > 1 ? args[1] : "";
                ClipData cd = ClipData.newPlainText("bridge", text);
                sSet.invoke(sIcb, cd, pkg(), null, 0, 0);
                System.out.println("OK:" + text.length());
            } else if ("diag".equals(cmd)) {
                String text = args.length > 1 ? args[1] : "DIAG";
                System.out.println("uid=" + android.os.Process.myUid()
                        + " pid=" + android.os.Process.myPid());
                ClipData cd = ClipData.newPlainText("bridge", text);
                sSet.invoke(sIcb, cd, pkg(), null, 0, 0);
                System.out.println("write sent: " + text);
                Thread.sleep(400);
                System.out.println("read1: " + snapshot());
                System.out.println("desc: " + sGetDesc());
                Thread.sleep(400);
                System.out.println("read2: " + snapshot());
            } else if ("poll".equals(cmd)) {
                long interval = args.length > 1 ? Long.parseLong(args[1]) : 700L;
                String last = null;
                System.out.println("READY");
                System.out.flush();
                while (true) {
                    String cur = snapshot();
                    if (!cur.equals(last)) {
                        System.out.println(cur);
                        System.out.flush();
                        last = cur;
                    }
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            } else {
                System.out.println("usage: Clip read|write TEXT|poll [ms]");
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("ERR:" + c);
            System.out.flush();
        }
        System.exit(0);
    }
}
