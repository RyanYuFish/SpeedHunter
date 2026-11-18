package com.github.speedhunter.diag;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * BLE 透传 RS485 链路（2026-11-18 车主令：适配 TD5322A 等国产 BLE-RS485 透传模块）。
 *
 * <p>与官方 VCI（{@link VciSppLink}）的差异：</p>
 * <ul>
 *   <li>传输：BLE GATT（write/notify 特征）而非 SPP RFCOMM；</li>
 *   <li>无 L2(55aa)/L3(56/ab) 封装 —— 模块对 RS485 字节透明双向透传；
 *       cmd68 的 3 字节通道前缀（01 00 &lt;ch&gt;）剥离后只把 CF/ED 帧写入串口；
 *       cmd 0x60/0x69（VCI 内部配置/轮询）安静忽略；</li>
 *   <li>ABS 安全 key（L2 fff9/fff7）由 VCI 内部计算，透传桥无法提供 ⇒
 *       {@link #supportsVciKey()} 恒 false，ABS 排气在 UI 层被拒绝；</li>
 *   <li>就绪判定 = GATT 订阅完成 + 只读探针（F4 50 电瓶电压）在 1.8s 内有任意 RX；
 *       无自动轮询（避免写里程等 F4 节点多帧序列被探针响应打乱，时序零风险）。</li>
 * </ul>
 *
 * <p>特征解析顺序：① 用户自定义 UUID（高级覆盖）② NUS 6E400001/02/03
 * ③ FFE0 服务 FFE1 单特征双用（HM-10 式）④ FFF0/FFF2/FFF1 ⑤ 自动发现
 * （同服务内恰好一个可写特征 + 一个可通知特征，允许同一特征）。</p>
 */
public final class BleRs485Link implements DiagLink {

    // ===== 常见透传模块 UUID profile =====
    private static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID FFE0_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID FFE1_CHAR = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID FFF0_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID FFF2_WRITE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID FFF1_NOTIFY = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 高级覆盖：诊断页「选择…」对话框里手填的 UUID（创建链路前设置，null=自动）。 */
    public static volatile UUID overrideService;
    public static volatile UUID overrideWrite;
    public static volatile UUID overrideNotify;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int PROBE_TIMEOUT_MS = 1800;
    private static final int WRITE_ACK_TIMEOUT_MS = 1500;

    private final DiagLink.Listener listener;
    private final CfStream decoder = new CfStream();
    private final Object lock = new Object();
    private final Object writeLock = new Object();
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(64);

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    private volatile boolean running;
    private volatile boolean closing;
    private volatile boolean subscribed;
    private volatile boolean sawDataReply;
    private volatile long lastDataRxAt;
    private volatile int effMtu = 23;
    private String failMsg;
    private Thread writer;
    private boolean writeDone;
    private int lastWriteStatus;

    public BleRs485Link(DiagLink.Listener listener) {
        this.listener = listener;
    }

    // ===== 状态查询 =====

    @Override
    public boolean isConnected() {
        return gatt != null && (subscribed || running);
    }

    @Override
    public boolean isDataReady() {
        return running && sawDataReply;
    }

    @Override
    public long lastDataRxAgeMs() {
        long t = lastDataRxAt;
        return t <= 0 ? -1L : System.currentTimeMillis() - t;
    }

    @Override
    public boolean supportsVciKey() {
        return false;
    }

    // ===== 连接 =====

    @SuppressLint("MissingPermission")
    @Override
    public void connectAndHandshake(Context context, String mac, boolean withSessionSetup) throws IOException {
        close();
        Context app = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("蓝牙未开启");
        if (mac == null || !mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) throw new IOException("BLE 地址无效");
        BluetoothDevice device = adapter.getRemoteDevice(mac);
        synchronized (lock) {
            failMsg = null;
            closing = false;
            subscribed = false;
            sawDataReply = false;
            lastDataRxAt = 0L;
        }
        listener.onHandshake("BLE", "连接 " + mac + "（GATT，MTU 185）…");
        gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("connectGatt 返回 null（模块不可达或已休眠）");
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        synchronized (lock) {
            while (failMsg == null && !subscribed && System.currentTimeMillis() < deadline) {
                try { lock.wait(200); } catch (InterruptedException e) { break; }
            }
        }
        if (failMsg != null) {
            String m = failMsg;
            close();
            throw new IOException(m);
        }
        if (!subscribed) {
            close();
            throw new IOException("BLE 连接超时（" + CONNECT_TIMEOUT_MS / 1000 + "s）：模块未上电/不在范围/非透传模块");
        }
        running = true;
        writer = new Thread(this::writeLoop, "ble-rs485-writer");
        writer.setDaemon(true);
        writer.start();
        listener.onConnected(mac);
        if (withSessionSetup) {
            listener.onHandshake("BLE", "透传通道已就绪，发只读探针 F4 50（电瓶电压）验证总线…");
            try {
                enqueueAll(ZeehoRs485Ops.eolBatteryVoltage());
                long end = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
                while (System.currentTimeMillis() < end && !sawDataReply) sleepQuiet(30);
                if (sawDataReply) {
                    listener.onHandshake("BLE", "总线探针有应答（车辆 RS485 在线）");
                } else {
                    listener.onHandshake("warn", "探针无应答——车辆总线可能未上电或 A/B 未接；数据通道判未就绪");
                }
            } catch (Exception e) {
                listener.onHandshake("warn", "探针发送失败: " + e.getMessage());
            }
        } else {
            sawDataReply = true; // 纯连接模式（无探针）直接视为就绪
        }
    }

    private void fail(String msg) {
        synchronized (lock) {
            if (failMsg == null) failMsg = msg;
            lock.notifyAll();
        }
        listener.onError(msg);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    g.requestMtu(185);
                } catch (Throwable t) {
                    fail("请求 MTU 失败: " + t.getMessage());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean was = running;
                running = false;
                synchronized (lock) {
                    lock.notifyAll();
                    if (failMsg == null && !closing) {
                        failMsg = "BLE 连接已断开（模块掉电/距离过远/被其他主机抢占）";
                    }
                }
                if (was && !closing) listener.onDisconnected();
                if (!closing) listener.onError("BLE 连接断开");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) effMtu = mtu;
            try {
                g.discoverServices();
            } catch (Throwable t) {
                fail("服务发现失败: " + t.getMessage());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败 status=" + status);
                return;
            }
            try {
                resolveChars(g);
            } catch (IOException e) {
                fail(e.getMessage());
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("订阅通知失败（CCCD write status=" + status + "）");
                return;
            }
            subscribed = true;
            synchronized (lock) { lock.notifyAll(); }
            listener.onHandshake("BLE", "通知已订阅，透传通道建立（MTU=" + effMtu + "）");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (writeLock) {
                writeDone = true;
                lastWriteStatus = status;
                writeLock.notifyAll();
            }
        }

        @Override
        @Deprecated
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) handleNotify(c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (Build.VERSION.SDK_INT >= 33) handleNotify(value);
        }
    };

    /** 特征解析：自定义 UUID → NUS → FFE0/FFE1 → FFF0 族 → 自动发现。 */
    @SuppressLint("MissingPermission")
    private void resolveChars(BluetoothGatt g) throws IOException {
        // ① 用户自定义 UUID（高级覆盖，三者必须齐配）
        if (overrideService != null && overrideWrite != null && overrideNotify != null) {
            BluetoothGattCharacteristic w = findChar(g, overrideService, overrideWrite);
            BluetoothGattCharacteristic n = findChar(g, overrideService, overrideNotify);
            if (w == null || n == null) {
                throw new IOException("自定义 UUID 在模块上未找到（检查 service/write/notify 是否写反）");
            }
            applyChars(w, n);
            listener.onHandshake("BLE", "使用自定义 UUID");
            subscribe(g);
            return;
        }
        // ② NUS
        BluetoothGattCharacteristic w = findChar(g, NUS_SERVICE, NUS_WRITE);
        BluetoothGattCharacteristic n = findChar(g, NUS_SERVICE, NUS_NOTIFY);
        if (w != null && n != null) {
            applyChars(w, n);
            listener.onHandshake("BLE", "特征=NUS（6E400001 服务）");
            subscribe(g);
            return;
        }
        // ③ FFE0 服务 FFE1 单特征双用（HM-10 式，写与通知同特征）
        BluetoothGattCharacteristic c1 = findChar(g, FFE0_SERVICE, FFE1_CHAR);
        if (c1 != null && writable(c1) && notifiable(c1)) {
            applyChars(c1, c1);
            listener.onHandshake("BLE", "特征=FFE0/FFE1 单特征双用（HM-10 式）");
            subscribe(g);
            return;
        }
        // ④ FFF0 族
        w = findChar(g, FFF0_SERVICE, FFF2_WRITE);
        n = findChar(g, FFF0_SERVICE, FFF1_NOTIFY);
        if (w != null && n != null) {
            applyChars(w, n);
            listener.onHandshake("BLE", "特征=FFF0/FFF2 写 + FFF1 通知");
            subscribe(g);
            return;
        }
        // ⑤ 自动发现：恰好一个服务内含一个可写特征与一个可通知特征（允许同一特征）
        int hits = 0;
        for (BluetoothGattService s : g.getServices()) {
            BluetoothGattCharacteristic wc = null, nc = null;
            int wCount = 0, nCount = 0;
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if (writable(c)) { wc = c; wCount++; }
                if (notifiable(c)) { nc = c; nCount++; }
            }
            if (wCount == 1 && nCount == 1 && wc != null && nc != null) {
                hits++;
                w = wc;
                n = nc;
            }
        }
        if (hits == 1) {
            applyChars(w, n);
            listener.onHandshake("BLE", "特征=自动发现（服务 " + shortUuid(w.getService().getUuid()) + "）");
            subscribe(g);
            return;
        }
        throw new IOException(hits > 1
                ? "自动发现到 " + hits + " 个候选服务，无法确定透传特征；请在高级设置手填 UUID"
                : "未找到透传特征（既无可写又无可通知的特征）；模块可能非串口透传型");
    }

    private static BluetoothGattCharacteristic findChar(BluetoothGatt g, UUID service, UUID ch) {
        BluetoothGattService s = g.getService(service);
        return s == null ? null : s.getCharacteristic(ch);
    }

    private static boolean writable(BluetoothGattCharacteristic c) {
        int p = c.getProperties();
        return (p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    private static boolean notifiable(BluetoothGattCharacteristic c) {
        int p = c.getProperties();
        return (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
    }

    private void applyChars(BluetoothGattCharacteristic w, BluetoothGattCharacteristic n) {
        writeChar = w;
        notifyChar = n;
        writeType = (w.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    }

    @SuppressLint("MissingPermission")
    private void subscribe(BluetoothGatt g) throws IOException {
        if (!g.setCharacteristicNotification(notifyChar, true)) {
            throw new IOException("setCharacteristicNotification 失败");
        }
        BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
        if (d == null) throw new IOException("特征无 CCCD 描述符（0x2902），无法订阅通知");
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            byte[] v = (notifyChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            ok = g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS;
        } else {
            d.setValue((notifyChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            ok = g.writeDescriptor(d);
        }
        if (!ok) throw new IOException("写 CCCD 订阅请求被拒");
    }

    // ===== 接收 =====

    private void handleNotify(byte[] value) {
        if (value == null || value.length == 0) return;
        lastDataRxAt = System.currentTimeMillis();
        sawDataReply = true;
        listener.onRaw(value, value.length);
        List<byte[]> frames = decoder.feed(value, value.length);
        for (byte[] f : frames) {
            VciLink.DataFrame df = new VciLink.DataFrame(true, 0, VciLink.CMD_RS485_WRITE, f);
            listener.onFrame(df, Collections.singletonList(f));
        }
    }

    // ===== 发送 =====

    /**
     * BLE 透传语义：cmd68 剥 3 字节通道前缀（01 00 ch）后写总线；
     * cmd 0x60/0x69/0x6A 是 VCI 内部命令，安静忽略（上层会话前导/轮询复用同一流程）。
     */
    @Override
    public void send(int cmd, byte[] data) throws IOException {
        if (cmd == VciLink.CMD_RS485_WRITE) {
            enqueueAll(stripChannelPrefix(data));
        } else if (cmd == VciLink.CMD_POLL || cmd == VciLink.CMD_CONFIG || cmd == VciLink.CMD_TRIGGER) {
            // VCI 内部命令，透传桥无此概念 —— 忽略
        }
    }

    @Override
    public int sendWithSeq(int cmd, byte[] data) throws IOException {
        send(cmd, data);
        return 0;
    }

    @Override
    public void sendRs485(byte[] cfFrame) throws IOException {
        enqueueAll(cfFrame);
    }

    @Override
    public void sendBus(byte[] cmd68Data) throws IOException {
        send(VciLink.CMD_RS485_WRITE, cmd68Data);
    }

    @Override
    public void sendRaw(byte[] bytes) throws IOException {
        enqueueAll(bytes);
    }

    @Override
    public void sendFfaPair() {
        // L2 概念在透传桥不存在 —— 无操作
    }

    @Override
    public void sendDeleteEntryQueries() {
        // 同上
    }

    private static byte[] stripChannelPrefix(byte[] data) {
        if (data != null && data.length >= 3 && (data[0] & 0xFF) == 0x01 && (data[1] & 0xFF) == 0x00) {
            return Arrays.copyOfRange(data, 3, data.length);
        }
        return data;
    }

    private void enqueueAll(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) return;
        if (!running) throw new IOException("BLE 未连接");
        int chunkMax = Math.max(20, effMtu - 3);
        for (int off = 0; off < payload.length; off += chunkMax) {
            int n = Math.min(chunkMax, payload.length - off);
            byte[] chunk = Arrays.copyOfRange(payload, off, off + n);
            if (!queue.offer(chunk)) throw new IOException("发送队列已满（模块写入无进展？）");
        }
    }

    /** 单写线程串行化：DEFAULT 写等 onCharacteristicWrite；NO_RESPONSE 固定 15ms 间隔防模块溢出。 */
    private void writeLoop() {
        while (running) {
            byte[] chunk;
            try {
                chunk = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (chunk == null) continue;
            try {
                writeChunk(chunk);
            } catch (Exception e) {
                listener.onError("写透传失败: " + e.getMessage());
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void writeChunk(byte[] chunk) throws IOException, InterruptedException {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = writeChar;
        if (g == null || c == null || !running) throw new IOException("BLE 未连接");
        synchronized (writeLock) {
            writeDone = false;
            lastWriteStatus = 0;
        }
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = g.writeCharacteristic(c, chunk, writeType) == BluetoothStatusCodes.SUCCESS;
        } else {
            c.setValue(chunk);
            c.setWriteType(writeType);
            ok = g.writeCharacteristic(c);
        }
        if (!ok) throw new IOException("writeCharacteristic 被拒（连接断开/操作堆积）");
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            sleepQuiet(15);
            return;
        }
        long deadline = System.currentTimeMillis() + WRITE_ACK_TIMEOUT_MS;
        synchronized (writeLock) {
            while (!writeDone && System.currentTimeMillis() < deadline) {
                writeLock.wait(50);
            }
        }
        if (!writeDone) throw new IOException("写超时（" + WRITE_ACK_TIMEOUT_MS + "ms 无 ACK）");
        if (lastWriteStatus != BluetoothStatusCodes.SUCCESS) {
            throw new IOException("写失败 status=" + lastWriteStatus);
        }
    }

    // ===== 保活（透传桥无 VCI 心跳/轮询概念，全部无操作） =====

    @Override
    public void startPolling() {
        // 无自动轮询：避免 F4 探针响应打乱写里程等 F4 节点多帧序列（时序零风险）
    }

    @Override
    public void stopPolling() {
    }

    @Override
    public void suspendKeepAlive() {
    }

    @Override
    public void resumeKeepAlive() {
    }

    // ===== 关闭 =====

    @SuppressLint("MissingPermission")
    @Override
    public void close() {
        running = false;
        closing = true;
        queue.clear();
        if (writer != null) writer.interrupt();
        writer = null;
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (Throwable ignored) {
            }
        }
        subscribed = false;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** 16 位短 UUID 展示（0000XXXX-0000-1000-… → 0xXXXX）。 */
    private static String shortUuid(UUID u) {
        String s = u.toString().toUpperCase();
        return s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805F9B34FB") && s.length() == 36
                ? "0x" + s.substring(4, 8) : s;
    }
}
