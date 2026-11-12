package com.uploader.app;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Receives files sent to the app from other apps (Share / Open with),
 * copies them to the app cache and hands them to the web layer.
 */
@CapacitorPlugin(name = "ShareReceiver")
public class ShareReceiverPlugin extends Plugin {
    private static final Object LOCK = new Object();
    private static ShareReceiverPlugin instance;
    private static JSObject pending;

    @Override
    public void load() {
        instance = this;
    }

    @PluginMethod
    public void getPending(PluginCall call) {
        JSObject r = new JSObject();
        synchronized (LOCK) {
            if (pending != null) r.put("file", pending);
        }
        call.resolve(r);
    }

    @PluginMethod
    public void clear(PluginCall call) {
        synchronized (LOCK) {
            pending = null;
        }
        call.resolve();
    }

    /** Fallback when the WebView cannot fetch the file directly. */
    @PluginMethod
    public void readBase64(PluginCall call) {
        try {
            String path = call.getString("path", "");
            if (path.startsWith("file://")) path = path.substring(7);
            File f = new File(path).getCanonicalFile();
            File dir = new File(getContext().getCacheDir(), "shared").getCanonicalFile();
            if (!f.getPath().startsWith(dir.getPath() + File.separator) || !f.isFile()) {
                call.reject("not allowed");
                return;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            JSObject r = new JSObject();
            r.put("data", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    public static void handleIntent(final Context ctx, final Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action)) return;
        final Intent copy = new Intent(intent);
        intent.setAction(Intent.ACTION_MAIN); // never process the same intent twice
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSObject f;
                try {
                    f = importIntent(app, copy);
                    if (f == null) {
                        f = new JSObject();
                        f.put("id", String.valueOf(System.nanoTime()));
                        f.put("error", "unsupported");
                    }
                } catch (Exception e) {
                    f = new JSObject();
                    f.put("id", String.valueOf(System.nanoTime()));
                    f.put("error", String.valueOf(e.getMessage()));
                }
                deliver(f);
            }
        }).start();
    }

    private static void deliver(JSObject f) {
        synchronized (LOCK) {
            pending = f;
        }
        ShareReceiverPlugin p = instance;
        if (p != null) p.notifyListeners("shared", f);
    }

    @SuppressWarnings("deprecation")
    private static JSObject importIntent(Context ctx, Intent intent) throws Exception {
        Uri uri = null;
        String text = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else {
            if (Build.VERSION.SDK_INT >= 33) {
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (uri == null) {
                ClipData cd = intent.getClipData();
                if (cd != null && cd.getItemCount() > 0) uri = cd.getItemAt(0).getUri();
            }
            if (uri == null) text = intent.getStringExtra(Intent.EXTRA_TEXT);
        }

        File dir = new File(ctx.getCacheDir(), "shared");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File o : old) o.delete();

        String name;
        String mime = "";
        File out;
        if (uri != null) {
            name = cleanName(queryName(ctx, uri));
            String m = ctx.getContentResolver().getType(uri);
            if (m != null) mime = m;
            out = new File(dir, name);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) throw new Exception("cannot open file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
        } else if (text != null && text.toLowerCase().matches("(?s).*(<!doctype\\s+html|<html|<body|<head).*")) {
            name = "shared.html";
            mime = "text/html";
            out = new File(dir, name);
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            return null;
        }

        JSObject r = new JSObject();
        r.put("id", String.valueOf(System.nanoTime()));
        r.put("name", name);
        r.put("path", "file://" + out.getAbsolutePath());
        r.put("size", out.length());
        r.put("mime", mime);
        return r;
    }

    private static String queryName(Context ctx, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        return name;
    }

    private static String cleanName(String n) {
        if (n == null) n = "";
        int s = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (s >= 0) n = n.substring(s + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty()) n = "shared";
        if (n.length() > 100) n = n.substring(n.length() - 100);
        return n;
    }
}
