package com.ethan.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The Shell helper (22/11). Not part of the app when it runs: the app starts it through the TV's debugging service
 * (Adb.java) with app_process, so it runs as the shell user, like a command typed over ADB. That's what lets it press
 * real remote keys (YouTube and Netflix draw their own screens and ignore the accessibility helper's presses) and
 * mirror the screen for the phone page's live picture. It only ever talks to the app, over a local socket the app
 * opened, and stops when that socket closes.
 *
 * From the app, one line each:  key <code> | tap <fx> <fy> | text <base64> | video <width> <fps> <quality> | video 0
 * To the app, [type byte][length][bytes]:  T token (first), F a JPEG frame, L a log line.
 */
public final class ShellMain {

    private ShellMain() {}

    private static DataOutputStream out;
    private static Object inputManager;
    private static Method inject;
    private static int dispW = 1920, dispH = 1080, layerStack = 0;

    public static void main(String[] args) {
        try {
            try {
                Looper.prepareMainLooper();
            } catch (Throwable ignored) {
            }
            Socket s = new Socket(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), Integer.parseInt(args[0]));
            s.setTcpNoDelay(true);
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
            send('T', args[1].getBytes(StandardCharsets.UTF_8));
            log("helper up, uid " + android.os.Process.myUid() + ", sdk " + android.os.Build.VERSION.SDK_INT);
            try {
                Class<?> im = Class.forName("android.hardware.input.InputManager");
                inputManager = im.getDeclaredMethod("getInstance").invoke(null);
                inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
            } catch (Throwable e) {
                log("no key injection: " + e);
            }
            readDisplay();
            watchdog();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handle(line.trim());
                } catch (Throwable e) {
                    log("failed on " + line.split(" ")[0] + ": " + e);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        System.exit(0);
    }

    private static void handle(String line) throws Exception {
        String[] p = line.split(" ");
        switch (p[0]) {
            case "key": {
                key(Integer.parseInt(p[1]));
                break;
            }
            case "tap": {
                float x = Float.parseFloat(p[1]) * dispW, y = Float.parseFloat(p[2]) * dispH;
                long t = SystemClock.uptimeMillis();
                touch(MotionEvent.ACTION_DOWN, t, t, x, y);
                touch(MotionEvent.ACTION_UP, t, t + 60, x, y);
                break;
            }
            case "text": {
                String text = new String(android.util.Base64.decode(p[1], android.util.Base64.NO_WRAP), StandardCharsets.UTF_8);
                KeyEvent[] evs = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
                if (evs == null) {
                    log("can't type that text as keys");
                    break;
                }
                for (KeyEvent e : evs) {
                    e.setSource(InputDevice.SOURCE_KEYBOARD);
                    send(e);
                }
                break;
            }
            case "video": {
                final int w = Integer.parseInt(p[1]);
                if (w <= 0) {
                    stopVideo();
                    break;
                }
                lastAsk = SystemClock.uptimeMillis();
                startVideo(w, p.length > 2 ? Integer.parseInt(p[2]) : 8, p.length > 3 ? Integer.parseInt(p[3]) : 55);
                break;
            }
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- keys and taps

    private static void key(int code) throws Exception {
        long t = SystemClock.uptimeMillis();
        send(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        send(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private static void touch(int action, long down, long when, float x, float y) throws Exception {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        send(e);
        e.recycle();
    }

    private static void send(InputEvent e) throws Exception {
        if (inject == null) throw new Exception("no key injection");
        inject.invoke(inputManager, e, 0); // 0: don't wait for the app to handle it
    }

    // ---------------------------------------------------------------- the live picture

    private static HandlerThread thread;
    private static Handler handler;
    private static ImageReader reader;
    private static IBinder display;
    private static int vidW, vidH, gap = 125, quality = 55;
    private static volatile long lastAsk;
    private static long lastFrame;
    private static boolean pending;
    private static Bitmap bmp;
    private static ByteBuffer rows;

    private static void readDisplay() {
        try {
            Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object di = g.getMethod("getDisplayInfo", int.class).invoke(g.getMethod("getInstance").invoke(null), 0);
            dispW = di.getClass().getField("logicalWidth").getInt(di);
            dispH = di.getClass().getField("logicalHeight").getInt(di);
            layerStack = di.getClass().getField("layerStack").getInt(di);
            log("display " + dispW + "x" + dispH + ", layer stack " + layerStack);
        } catch (Throwable e) {
            log("couldn't read the display size, going with 1920x1080: " + e);
        }
    }

    private static synchronized void startVideo(final int w, int fps, int q) {
        gap = 1000 / Math.max(1, Math.min(15, fps));
        quality = Math.max(20, Math.min(90, q));
        if (display != null && w == vidW) return;
        stopVideo();
        try {
            if (thread == null) {
                thread = new HandlerThread("video");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
            vidW = w;
            vidH = Math.round(w * (float) dispH / dispW) & ~1;
            reader = ImageReader.newInstance(vidW, vidH, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    // at most one frame per gap, and the newest one always gets sent in the end
                    if (pending) return;
                    pending = true;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            pending = false;
                            frame();
                        }
                    }, Math.max(0, lastFrame + gap - SystemClock.uptimeMillis()));
                }
            }, handler);
            // a second, mirror display that draws into our reader (not secure, so protected video stays black)
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            display = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class).invoke(null, "ectv", false);
            sc.getMethod("openTransaction").invoke(null);
            try {
                sc.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, display, reader.getSurface());
                sc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                        .invoke(null, display, 0, new Rect(0, 0, dispW, dispH), new Rect(0, 0, vidW, vidH));
                sc.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, display, layerStack);
            } finally {
                sc.getMethod("closeTransaction").invoke(null);
            }
            log("video on " + vidW + "x" + vidH);
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            log("video failed: " + c);
            stopVideo();
        }
    }

    private static synchronized void stopVideo() {
        if (display != null) {
            try {
                Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay", IBinder.class).invoke(null, display);
            } catch (Throwable ignored) {
            }
            display = null;
            log("video off");
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
            reader = null;
        }
        vidW = 0;
    }

    /** On the video thread: the newest picture off the mirror, as a JPEG, to the app. */
    private static void frame() {
        ImageReader r = reader;
        if (r == null) return;
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            lastFrame = SystemClock.uptimeMillis();
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int stride = pl.getRowStride();
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (stride == w * 4) {
                buf.rewind();
                bmp.copyPixelsFromBuffer(buf);
            } else {
                // rows come padded: copy just the picture part of each
                if (rows == null || rows.capacity() != w * 4 * h) rows = ByteBuffer.allocate(w * 4 * h);
                rows.clear();
                byte[] row = new byte[w * 4];
                for (int y = 0; y < h; y++) {
                    buf.position(y * stride);
                    buf.get(row, 0, w * 4);
                    rows.put(row);
                }
                rows.rewind();
                bmp.copyPixelsFromBuffer(rows);
            }
            ByteArrayOutputStream jpg = new ByteArrayOutputStream(1 << 16);
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, jpg);
            send('F', jpg.toByteArray());
        } catch (Throwable e) {
            log("frame failed: " + e);
        } finally {
            if (img != null) img.close();
        }
    }

    /** Nobody's asked for the picture in 5 s: stop mirroring. The app asks again every second while a phone's watching. */
    private static void watchdog() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (display != null && SystemClock.uptimeMillis() - lastAsk > 5000) stopVideo();
                }
            }
        }, "watchdog");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- to the app

    private static void log(String s) {
        try {
            send('L', s.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static void send(char type, byte[] b) throws java.io.IOException {
        synchronized (ShellMain.class) {
            out.writeByte(type);
            out.writeInt(b.length);
            out.write(b);
            out.flush();
        }
    }
}
