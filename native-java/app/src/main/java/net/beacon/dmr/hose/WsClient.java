package net.beacon.dmr.hose;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebSocket 客户端（RFC 6455），只做这个工程需要的事。
 *
 * <p><b>为什么不用 OkHttp</b>：这个工程零依赖。OkHttp 4.x 会拖进整个
 * kotlin-stdlib（约 2 MB），而我们只需要「连上、收二进制帧、发一个小数组」。
 * 一个 400 行的客户端换来 2 MB 的体积和一条能自己读懂的 TLS 链路，值得。
 *
 * <p>实现范围：
 * <ul>
 *   <li>wss（TLS + SNI + 主机名校验）与 ws</li>
 *   <li>握手时带 {@code Sec-WebSocket-Protocol}（HOSE 要求 {@code spotter}）</li>
 *   <li>收发单帧文本 / 二进制；分片帧（continuation）也支持 —— 服务端偶尔会把
 *       大一点的报文拆成两帧，不处理就会解码失败</li>
 *   <li>ping → 自动回 pong</li>
 * </ul>
 *
 * <p>线程模型：一个读线程，回调都在读线程上发生。调用方要切到主线程自己切。
 */
public final class WsClient {

    public interface Listener {
        void onOpen(WsClient c);
        void onBinary(byte[] data);
        void onText(String text);
        /** 连接结束（正常关闭或出错都走这里，只调一次）。 */
        void onClosed(String reason);
    }

    private static final int CONNECT_TIMEOUT_MS = 12000;
    /** 读超时。HOSE 正常情况下每秒都有报文；连续几次都读不到就说明链路死了。 */
    private static final int READ_TIMEOUT_MS = 45000;
    private static final int MAX_IDLE_TIMEOUTS = 3;
    /** 单帧上限。HOSE 的音频帧只有几百字节，8 MB 足够防住畸形长度字段。 */
    private static final int MAX_FRAME = 8 * 1024 * 1024;

    private final URI uri;
    private final String subProtocol;
    /**
     * 回调监听器。<b>可以为 null</b> —— {@link #setListener} 允许主动摘掉。
     *
     * <p>为什么需要「摘掉」：主动关闭连接时，如果 onClosed 还挂着，
     * 库里自己的重连逻辑会被自己的关闭动作触发一次（「断开 → 立刻重连 →
     * 又断开」的抖动）。WebView 版在三个地方都踩过这个坑，
     * 那边的做法是 {@code ws.onclose=null} 再 {@code close()}。
     */
    private volatile Listener listener;

    private Socket raw;
    private InputStream in;
    private OutputStream out;
    private volatile Thread reader;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    public WsClient(String url, String subProtocol, Listener l) {
        this.uri = URI.create(url);
        this.subProtocol = subProtocol;
        this.listener = l;
    }

    public boolean isOpen() { return open.get(); }

    /** 换 / 摘掉回调。传 null = 之后不再有任何回调。 */
    public void setListener(Listener l) { this.listener = l; }

    /** 阻塞连接（不要在主线程调）。成功返回 true。 */
    public boolean connect() {
        try {
            String scheme = uri.getScheme() == null ? "wss" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean tls = scheme.equals("wss") || scheme.equals("https");
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);

            Socket s;
            if (tls) {
                SSLSocket ss = (SSLSocket) SSLSocketFactory.getDefault()
                        .createSocket();
                ss.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                /* SNI 和主机名校验都得显式打开：
                 * 用 createSocket() 拿到的裸 SSLSocket 默认不做这两件事，
                 * 而 BrandMeister 挂在 Cloudflare 后面，没有 SNI 会直接拿到
                 * 一张不匹配的证书。 */
                SSLParameters p = ss.getSSLParameters();
                p.setServerNames(List.of(new SNIHostName(host)));
                p.setEndpointIdentificationAlgorithm("HTTPS");
                ss.setSSLParameters(p);
                ss.setSoTimeout(READ_TIMEOUT_MS);
                ss.startHandshake();
                s = ss;
            } else {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(READ_TIMEOUT_MS);
            }
            raw = s;
            in = new BufferedInputStream(s.getInputStream(), 16384);
            out = s.getOutputStream();

            handshake(host, port);
            open.set(true);
            Listener l0 = listener;
            if (l0 != null) l0.onOpen(this);

            Thread t = new Thread(this::readLoop, "ws-reader");
            t.setDaemon(true);
            reader = t;
            t.start();
            return true;
        } catch (Exception e) {
            closeQuietly("连接失败：" + brief(e));
            return false;
        }
    }

    private static String brief(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    /* ==================== 握手 ==================== */

    private void handshake(String host, int port) throws IOException {
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        String key = Base64.getEncoder().encodeToString(rnd);

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        boolean defaultPort = (port == 443 || port == 80);
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(defaultPort ? "" : ":" + port).append("\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        sb.append("Sec-WebSocket-Version: 13\r\n");
        if (subProtocol != null && !subProtocol.isEmpty()) {
            sb.append("Sec-WebSocket-Protocol: ").append(subProtocol).append("\r\n");
        }
        /* 浏览器一定会带 Origin，服务端（和它前面的 Cloudflare 规则）可能校验。
         * 不带的话有概率被 403 —— 这里照浏览器的样子补上。 */
        sb.append("Origin: https://").append(host).append("\r\n");
        sb.append("User-Agent: BeaconDMR-Java/3.0\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        String head = readHttpHead();
        if (head == null) throw new EOFException("握手期间连接被关闭");
        String[] lines = head.split("\r\n");
        if (lines.length == 0) throw new IOException("握手响应为空");
        String status = lines[0];
        if (!status.contains(" 101")) {
            throw new IOException("握手被拒：" + status
                    + (head.length() > 200 ? "" : " / " + head.replace("\r\n", " | ")));
        }
        /* 校验 Sec-WebSocket-Accept：服务端必须回 SHA1(key + GUID) 的 base64。
         * 不校验的话，中间人返回任意 101 都能把连接接下去。 */
        String expect = acceptFor(key);
        boolean acceptOk = false;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            int c = l.indexOf(':');
            if (c <= 0) continue;
            if (l.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                acceptOk = l.substring(c + 1).trim().equals(expect);
            }
        }
        if (!acceptOk) throw new IOException("Sec-WebSocket-Accept 不匹配");
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static String acceptFor(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String readHttpHead() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0;                       // 匹配 \r\n\r\n 的进度
        while (true) {
            int b = in.read();
            if (b < 0) return buf.size() == 0 ? null : buf.toString("UTF-8");
            buf.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') return buf.toString("UTF-8");
            else state = (b == '\r') ? 1 : 0;
            if (buf.size() > 65536) throw new IOException("握手响应过长");
        }
    }

    /* ==================== 读 ==================== */

    private void readLoop() {
        String why = "正常关闭";
        try {
            int idle = 0;
            ByteArrayOutputStream frag = null;
            int fragOp = 0;
            while (!closed.get()) {
                int b0;
                try {
                    b0 = in.read();
                } catch (SocketTimeoutException te) {
                    if (++idle >= MAX_IDLE_TIMEOUTS) { why = "长时间无数据"; break; }
                    continue;
                }
                if (b0 < 0) { why = "对端关闭"; break; }
                idle = 0;

                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;

                int b1 = readByte();
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = (readByte() << 8) | readByte();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                if (len < 0 || len > MAX_FRAME) { why = "帧长度非法 " + len; break; }

                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask, 4);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload, (int) len);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }

                switch (opcode) {
                    case 0x0: {                       // continuation
                        if (frag == null) { why = "无头分片"; break; }
                        frag.write(payload);
                        if (fin) {
                            byte[] all = frag.toByteArray();
                            frag = null;
                            dispatch(fragOp, all);
                        }
                        break;
                    }
                    case 0x1: case 0x2: {
                        if (fin) dispatch(opcode, payload);
                        else { frag = new ByteArrayOutputStream(); frag.write(payload); fragOp = opcode; }
                        break;
                    }
                    case 0x8:                         // close
                        why = "服务端关闭";
                        try { sendFrame(0x8, payload); } catch (Exception ignore) {}
                        break;
                    case 0x9:                          // ping → pong
                        try { sendFrame(0xA, payload); } catch (Exception ignore) {}
                        break;
                    case 0xA:                          // pong，忽略
                        break;
                    default:
                        /* 未知控制帧一律忽略。RFC 允许接收方丢弃不认识的操作码，
                         * 直接断开会让服务端加一个新帧类型就把我们踢下线。 */
                        break;
                }
                if (opcode == 0x8) break;
            }
        } catch (Exception e) {
            why = "读线程异常：" + brief(e);
        }
        closeQuietly(why);
    }

    private void dispatch(int opcode, byte[] payload) {
        if (closed.get()) return;
        try {
            Listener l1 = listener;
            if (l1 == null) return;
            if (opcode == 0x1) l1.onText(new String(payload, StandardCharsets.UTF_8));
            else l1.onBinary(payload);
        } catch (Exception e) {
            /* 回调抛异常不能把读线程带走 —— 一帧解析失败只该丢这一帧。 */
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private void readFully(byte[] dst, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(dst, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

    /* ==================== 写 ==================== */

    /** 发二进制帧。客户端发出的帧**必须**加掩码（RFC 6455 §5.3）。 */
    public void send(byte[] data) throws IOException {
        sendFrame(0x2, data);
    }

    public void sendText(String s) throws IOException {
        sendFrame(0x1, s.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("未连接");
        synchronized (writeLock) {
            ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
            f.write(0x80 | opcode);                       // FIN + opcode
            int n = payload.length;
            if (n <= 125) {
                f.write(0x80 | n);
            } else if (n <= 0xFFFF) {
                f.write(0x80 | 126);
                f.write((n >> 8) & 0xFF);
                f.write(n & 0xFF);
            } else {
                f.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) f.write((int) (((long) n >> (8 * i)) & 0xFF));
            }
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            f.write(mask, 0, 4);
            byte[] body = new byte[n];
            for (int i = 0; i < n; i++) body[i] = (byte) (payload[i] ^ mask[i & 3]);
            f.write(body, 0, n);
            o.write(f.toByteArray());
            o.flush();
        }
    }

    /* ==================== 关闭 ==================== */

    public void close() { closeQuietly("主动关闭"); }

    private void closeQuietly(String why) {
        if (!closed.compareAndSet(false, true)) return;
        boolean wasOpen = open.getAndSet(false);
        try { if (raw != null) raw.close(); } catch (Exception ignore) {}
        raw = null; in = null; out = null;
        Thread t = reader;
        reader = null;
        /* 不要在读线程自己身上 join 自己。 */
        if (t != null && t != Thread.currentThread()) {
            try { t.join(300); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        }
        Listener l2 = listener;
        if (wasOpen && l2 != null) {
            try { l2.onClosed(why); } catch (Exception ignore) {}
        }
    }
}
