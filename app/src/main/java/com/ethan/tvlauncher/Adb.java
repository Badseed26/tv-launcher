package com.ethan.tvlauncher;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A small ADB client (22/11), plain Java. The TV app uses it to talk to the TV's own debugging service on
 * 127.0.0.1, the same way AirHQ does from Ethan's phone, so it can start the Shell helper (real remote keys and the
 * live picture). Just what that needs: connect, sign in with our key, open a shell stream and read it.
 * The first time, the TV shows its "Allow USB debugging?" box and Ethan ticks Always allow.
 */
final class Adb implements Closeable {

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257, STLS = 0x534c5453;
    // SHA-1 DigestInfo: adbd checks the token as if it were a SHA-1 hash
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    interface Note {
        /** The TV's showing its allow box, waiting on Ethan. */
        void waiting();
    }

    private final Socket s;
    private final InputStream in;
    private final OutputStream out;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private int nextId = 1;
    private volatile boolean closed;
    String banner = "";

    private Adb(Socket s) throws Exception {
        this.s = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    /** Connects and signs in. allowWaitMs is how long to wait on the TV's allow box when our key's new to it. */
    static Adb connect(String host, int port, KeyPair key, String name, int allowWaitMs, Note note) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setTcpNoDelay(true);
        final Adb a = new Adb(s);
        try {
            s.setSoTimeout(10000);
            a.send(CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false, sentKey = false;
            while (true) {
                Msg m = a.read();
                if (m.cmd == CNXN) {
                    a.banner = new String(m.data, StandardCharsets.UTF_8);
                    break;
                }
                if (m.cmd == STLS) throw new Exception("the TV's debugging wants a secure pairing this app doesn't do");
                if (m.cmd != AUTH) continue;
                if (m.arg0 != 1) throw new Exception("the TV's debugging sent a sign in step this app doesn't know");
                if (!signed) {
                    signed = true;
                    a.send(AUTH, 2, 0, sign(key, m.data));
                } else if (!sentKey) {
                    // our key's new to the TV: offer it, the TV asks Ethan
                    sentKey = true;
                    a.send(AUTH, 3, 0, (publicKey((RSAPublicKey) key.getPublic(), name) + "\0").getBytes(StandardCharsets.UTF_8));
                    if (note != null) note.waiting();
                    s.setSoTimeout(Math.max(1000, allowWaitMs));
                } else throw new Exception("the TV didn't allow this app's debugging key");
            }
            s.setSoTimeout(0);
        } catch (java.net.SocketTimeoutException e) {
            a.close();
            throw new Exception("nobody ticked Always allow on the TV's debugging box in time");
        } catch (Exception e) {
            a.close();
            throw e;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                a.pump();
            }
        }, "adb-read");
        t.setDaemon(true);
        t.start();
        return a;
    }

    /** One open stream (a shell command's output). */
    final class Stream {
        final int local;
        volatile int remote;
        volatile boolean open, done;
        private final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        private final byte[] END = new byte[0];

        Stream(int local) {
            this.local = local;
        }

        /** The next lump of output, null once the stream's finished. Throws if nothing came in time (0 waits forever). */
        byte[] read(int timeoutMs) throws Exception {
            byte[] b = timeoutMs <= 0 ? q.take() : q.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (b == null) throw new Exception("timed out");
            if (b == END) {
                q.add(END);
                return null;
            }
            return b;
        }

        void close() {
            if (done) return;
            done = true;
            try {
                send(CLSE, local, remote, new byte[0]);
            } catch (Exception ignored) {
            }
            streams.remove(local);
            q.add(END);
        }
    }

    /** Opens a service, like "shell:ls". */
    Stream open(String service) throws Exception {
        Stream st;
        synchronized (this) {
            st = new Stream(nextId++);
        }
        streams.put(st.local, st);
        send(OPEN, st.local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        long until = System.currentTimeMillis() + 10000;
        while (!st.open && !st.done && !closed && System.currentTimeMillis() < until) Thread.sleep(20);
        if (!st.open) {
            streams.remove(st.local);
            throw new Exception(closed ? "the debugging link dropped" : "the TV wouldn't open " + service.split(":")[0]);
        }
        return st;
    }

    /** Runs a short shell command and gives back what it printed. */
    String shell(String cmd, int timeoutMs) throws Exception {
        Stream st = open("shell:" + cmd);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        long until = System.currentTimeMillis() + timeoutMs;
        try {
            byte[] d;
            while ((d = st.read((int) Math.max(1, until - System.currentTimeMillis()))) != null) b.write(d);
        } finally {
            st.close();
        }
        return b.toString("UTF-8");
    }

    boolean alive() {
        return !closed;
    }

    private void pump() {
        try {
            while (!closed) {
                Msg m = read();
                Stream st = streams.get(m.arg1);
                if (m.cmd == OKAY && st != null) {
                    st.remote = m.arg0;
                    st.open = true;
                } else if (m.cmd == WRTE) {
                    if (st != null) st.q.add(m.data);
                    send(OKAY, m.arg1, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE && st != null) {
                    st.remote = m.arg0;
                    st.close();
                }
            }
        } catch (Exception ignored) {
        }
        close();
    }

    @Override
    public void close() {
        closed = true;
        for (Stream st : streams.values()) {
            st.done = true;
            st.q.add(st.END);
        }
        streams.clear();
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- messages

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws Exception {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        h.put(data);
        out.write(h.array());
        out.flush();
    }

    private Msg read() throws Exception {
        byte[] h = new byte[24];
        full(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1 << 20) throw new Exception("bad message from the TV's debugging");
        m.data = new byte[len];
        full(m.data);
        return m;
    }

    private void full(byte[] b) throws Exception {
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) throw new EOFException("the debugging link closed");
            got += n;
        }
    }

    // ---------------------------------------------------------------- the key

    /** The token signed the way adbd checks it: RSA, PKCS1 padding, the token standing in for a SHA-1 hash. */
    static byte[] sign(KeyPair key, byte[] token) throws Exception {
        Signature sg = Signature.getInstance("NONEwithRSA");
        sg.initSign(key.getPrivate());
        sg.update(SHA1_INFO);
        sg.update(token);
        return sg.sign();
    }

    /** Our public key in adb's own layout (word count, n0inv, n, rr, exponent, all little-endian), base64, then a name. */
    static String publicKey(RSAPublicKey k, String name) {
        BigInteger n = k.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        little(b, n, words);
        little(b, rr, words);
        b.putInt(k.getPublicExponent().intValue());
        return java.util.Base64.getEncoder().encodeToString(b.array()) + " " + name;
    }

    private static void little(ByteBuffer b, BigInteger v, int words) {
        byte[] big = v.toByteArray();
        for (int i = 0; i < words * 4; i++) {
            int at = big.length - 1 - i;
            b.put(at >= 0 ? big[at] : 0);
        }
    }
}
