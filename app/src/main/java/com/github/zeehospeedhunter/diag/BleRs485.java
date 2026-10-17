package com.github.zeehospeedhunter.diag;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RS485 / CAN 蓝牙透传模块桥 —— 移植自 zeeho-diag iOS 版 {@code App/Sources/RS485BLE.swift}。
 *
 * <p><b>为什么需要这一层</b>：iOS 版与桌面版的关键差异就在这里 —— 它把诊断主通道做成了
 * <b>BLE 透传</b>（HC-02 / I6328A-485 / ESP32-CANE 这类串口透传模块），手机直连车辆的
 * RS485 针脚或 CAN 总线，<b>不经过车机、不依赖 Wi-Fi、不依赖官方 App</b>。
 * 桌面版的 DoIP 是「连车机热点」，iOS 版多了这条「直连车辆总线」的独立链路。</p>
 *
 * <h3>默认 GATT 参数（与 iOS 版一致）</h3>
 * <pre>
 *   Service 0xFFE0
 *     0xFFE1  Write / WriteWithoutResponse   APP → 模块（下行）
 *     0xFFE2  Notify / Indicate               模块 → APP（上行）
 * </pre>
 * <p>串口默认 <b>115200 8N1</b>，与车辆 RS485 诊断波特率一致（模块出厂默认）。</p>
 *
 * <h3>特征选择策略（与 iOS 版 canSend/pickCharacteristics 同策略）</h3>
 * <ol>
 *   <li>第一趟：找 UUID 含 {@code FFE1} 且可写的、含 {@code FFE2} 且可通知的；</li>
 *   <li>第二趟：回退到第一个可写 / 第一个可通知特征（兼容非 FFE 系列的模块）。</li>
 * </ol>
 *
 * <h3>长帧分片</h3>
 * <p>{@link #sendHex} 按 {@code maximumWriteValueLength} 分片，片间延迟 30ms
 * （与 iOS 版 {@code sendChunks} 的 0.03s 一致；RS485 一帧最长可到 60+ 字节，
 * BLE 单包装不下，不分片会被静默截断）。</p>
 *
 * <p>事件通过 {@link Listener} 回调：{@link #emit} 统一转成字符串（与 iOS 版 JSON 事件流同构），
 * 便于 UI 与日志复用同一条通路。</p>
 */
public final class BleRs485 {

    // ---------- GATT 常量（与 iOS 版一致）----------
    private static final UUID SERVICE_FFE0 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_FFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_FFE2 = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 分片间隔（ms），与 iOS 版 0.03s 对齐。 */
    private static final int CHUNK_DELAY_MS = 30;
    /** 单片最小长度（Android 下限保守取 20）。 */
    private static final int MIN_CHUNK = 20;
    /** 单片上限：iOS 版取 180，这里对齐。 */
    private static final int MAX_CHUNK_CAP = 180;

    /** 一键连接时优先匹配的名字前缀（与 iOS 版 autoConnect 的 pick 规则一致）。 */
    private static final String[] PREFERRED_NAME_HINTS = {"ZEEHO-CAN", "6328"};

    /** 事件类型，与 iOS 版 emit 的 type 字段同构。 */
    public static final String EV_STATE = "state";
    public static final String EV_SCAN_STARTED = "scanStarted";
    public static final String EV_SCAN_STOPPED = "scanStopped";
    public static final String EV_DEVICE = "device";
    public static final String EV_CONNECTED = "connected";
    public static final String EV_CONNECT_FAILED = "connectFailed";
    public static final String EV_DISCONNECTED = "disconnected";
    public static final String EV_SERVICES = "services";
    public static final String EV_READY = "ready";
    public static final String EV_DATA = "data";
    public static final String EV_TX = "tx";
    public static final String EV_ERROR = "error";

    /** 事件回调。 */
    public interface Listener {
        void onBleEvent(String type, String payload);
    }

    /** 一个扫描到的设备。 */
    public static final class Device {
        public final String id;      // MAC 地址
        public final String name;
        public final int rssi;
        /** 名称里是否命中优先前缀（ZEEHO-CAN / 6328）。 */
        public final boolean preferred;

        Device(String id, String name, int rssi) {
            this.id = id;
            this.name = name;
            this.rssi = rssi;
            String up = name == null ? "" : name.toUpperCase();
            boolean p = false;
            for (String h : PREFERRED_NAME_HINTS) {
                if (up.contains(h)) { p = true; break; }
            }
            this.preferred = p;
        }
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    /** 扫描发现的设备，按发现顺序（保证「最强信号」等 tie-break 行为可复现）。 */
    private final List<String> discoveredOrder = new ArrayList<>();
    private final Map<String, Device> discovered = new LinkedHashMap<>();

    /** 名称过滤（可空 = 不过滤）。 */
    private String scanFilter;
    /** 扫描是否在进行。 */
    private volatile boolean scanning;
    /** 设备是否已连且有可写特征（iOS 版 bleIsConnected 的等价判定）。 */
    private volatile boolean ready;
    private volatile int mtChunk = MIN_CHUNK;

    public BleRs485(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm == null ? null : bm.getAdapter();
    }

    // ==================== 权限 ====================

    /**
     * Android 12+ 把 BLE 归入「附近设备」权限族（BLUETOOTH_SCAN / BLUETOOTH_CONNECT），
     * Android 11 及以下走 legacy 的 BLUETOOTH / BLUETOOTH_ADMIN + 定位。
     *
     * @return true 表示已具备扫描+连接所需的全部权限
     */
    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 需要申请的运行时权限（Android 12+ 返回两项，低版本返回定位）。 */
    public static String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    // ==================== 状态 ====================

    /** 蓝牙开关状态：poweredOn / poweredOff / unsupported / unauthorized / unknown。 */
    public String stateName() {
        if (adapter == null) return "unsupported";
        try {
            switch (adapter.getState()) {
                case BluetoothAdapter.STATE_ON: return "poweredOn";
                case BluetoothAdapter.STATE_OFF: return "poweredOff";
                default: return "unknown";
            }
        } catch (SecurityException e) {
            return "unauthorized";
        }
    }

    public boolean isReady() {
        return ready && gatt != null;
    }

    public boolean isConnected() {
        BluetoothGatt g = gatt;
        if (g == null) return false;
        BluetoothDevice dev = g.getDevice();
        if (dev == null) return false;
        try {
            // 公开签名是 getConnectionState(BluetoothDevice)；无参版是隐藏 API，编译不过
            return g.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED;
        } catch (SecurityException e) {
            return false;
        }
    }

    public int discoveredCount() {
        return discovered.size();
    }

    /** 供一键连接挑选设备：优先名含 ZEEHO-CAN/6328，否则最强 RSSI（与 iOS 版 autoConnect 同规则）。 */
    public Device pickBest() {
        Device best = null;
        for (String id : discoveredOrder) {
            Device d = discovered.get(id);
            if (d == null) continue;
            if (d.preferred) return d;              // 命中优先前缀，取第一个
            if (best == null || d.rssi > best.rssi) best = d;
        }
        return best;
    }

    public List<Device> devices() {
        return new ArrayList<>(discovered.values());
    }

    // ==================== 扫描 ====================

    public void startScan(String nameFilter) {
        scanFilter = (nameFilter == null || nameFilter.trim().isEmpty()) ? null : nameFilter.trim();
        if (!"poweredOn".equals(stateName())) {
            emit(EV_ERROR, "蓝牙不可用：" + stateName());
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            emit(EV_ERROR, "拿不到 BLE 扫描器（蓝牙可能已被其他 App 占用）");
            return;
        }
        discovered.clear();
        discoveredOrder.clear();
        // 按名称过滤用本地筛而非 ScanFilter：Android 的 ScanFilter 只支持精确/前缀 UUID，
        // 不支持「名字包含」，与 iOS 版 didDiscover 里判断的行为一致。
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        // Android 的 stopScan(scanCallback) 要求传入「启动时的同一个实例」，否则静默不停止。
        // 所以这里必须把回调存成字段，不能用匿名局部变量。
        mScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                onFound(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results != null) for (ScanResult r : results) onFound(r);
            }

            @Override
            public void onScanFailed(int errorCode) {
                emit(EV_ERROR, "扫描失败 code=" + errorCode);
            }
        };
        try {
            scanner.startScan(new ArrayList<ScanFilter>(), settings, mScanCallback);
            scanning = true;
            emit(EV_SCAN_STARTED, "");
        } catch (SecurityException e) {
            emit(EV_ERROR, "扫描被拒（缺附近设备权限）" + e.getMessage());
        }
    }

    private void onFound(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
        if (name == null) name = result.getDevice().getName();
        if (name == null) name = "";
        if (scanFilter != null && !name.toLowerCase().contains(scanFilter.toLowerCase())) return;

        String id = result.getDevice().getAddress();
        Device d = new Device(id, name, result.getRssi());
        if (!discovered.containsKey(id)) discoveredOrder.add(id);
        discovered.put(id, d);
        emit(EV_DEVICE, id + "|" + name + "|" + d.rssi + (d.preferred ? "|★" : ""));
    }

    public void stopScan() {
        if (!scanning) return;
        try {
            BluetoothLeScanner s = adapter.getBluetoothLeScanner();
            if (s != null) s.stopScan(mScanCallback);
        } catch (SecurityException ignored) {
        }
        scanning = false;
        emit(EV_SCAN_STOPPED, "");
    }

    /** 保存回调引用以便 stopScan（Android 的 stopScan 需要传同一个 ScanCallback 实例）。 */
    private ScanCallback mScanCallback;

    // ==================== 连接 ====================

    public void connect(String mac) {
        stopScan();
        if (!hasPermission(ctx)) {
            emit(EV_ERROR, "缺少蓝牙权限（Android 12+ 需「附近设备」）");
            return;
        }
        BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            emit(EV_ERROR, "地址格式不对：" + mac);
            return;
        }
        try {
            gatt = dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            emit(EV_CONNECTED, mac + "|connecting");
        } catch (SecurityException e) {
            emit(EV_ERROR, "连接被拒（缺 BLUETOOTH_CONNECT 权限）");
        }
    }

    public void disconnect() {
        BluetoothGatt g = gatt;
        gatt = null;
        writeChar = null;
        notifyChar = null;
        ready = false;
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (SecurityException ignored) {
            }
        }
    }

    // ==================== 发送 ====================

    /**
     * 选写入类型：特征同时支持 write 与 writeWithoutResponse 时<b>优先 noResponse</b>
     * （透传模块的 FFE1 两者都有，noResponse 吞吐更高、不等 ATT 确认）。
     */
    private static int pickWriteType(BluetoothGattCharacteristic c) {
        if (c == null) return BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        int p = c.getProperties();
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            return BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        }
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            return BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        }
        return BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    }

    /**
     * 协商单片长度：取框架算出的 {@code maximumWriteValueLength}（= ATT_MTU-3），
     * 上限压到 {@link #MAX_CHUNK_CAP}（180，与 iOS 版一致）——RS485 一帧最长 60+ 字节，
     * 透传模块固件普遍支持长包；MTU 协商失败时退回 20（最保守，保证任何设备都能发）。
     */
    /**
     * 协商单片长度。
     *
     * <p>⚠️ {@code BluetoothDevice.getMaximumWriteValueLength(int)} 在 SDK 33 起被移除
     * （迁移到 {@code BluetoothGattCharacteristic} 的写入长度语义），直接调用编译不过。
     * 这里用<b>反射探测</b>，能拿到就按协商值算，拿不到（老系统/厂商裁剪）退回 180 ——
     * 180 是 iOS 版的上限，透传模块固件普遍支持长包。</p>
     */
    private int negotiateChunk(BluetoothGatt g, BluetoothGattCharacteristic w) {
        int fallback = 180;
        if (g == null || w == null || g.getDevice() == null) return MIN_CHUNK;
        try {
            java.lang.reflect.Method m = android.bluetooth.BluetoothDevice.class
                    .getMethod("getMaximumWriteValueLength", int.class);
            Object v = m.invoke(g.getDevice(), pickWriteType(w));
            if (v instanceof Integer) {
                return Math.max(MIN_CHUNK, Math.min((Integer) v, MAX_CHUNK_CAP));
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * 发送一行 hex（自动过滤非 hex 字符、自动分片）。
     *
     * @return true 表示已进入发送流程；false 表示未连接或特征不可用
     */
    public boolean sendHex(String hex) {
        if (!isReady() || writeChar == null) {
            emit(EV_ERROR, "未连接设备或没有可写特征");
            return false;
        }
        String clean = hex == null ? "" : hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (clean.length() < 2) return false;
        byte[] data = hexToBytes(clean);
        if (data.length == 0) return false;
        sendChunks(data, pickWriteType(writeChar));
        emit(EV_TX, clean + "|" + data.length);
        return true;
    }

    private void sendChunks(byte[] data, int type) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic ch = writeChar;
        if (g == null || ch == null) return;
        // 分片全部走 write()，它在内部按 max 递进，避免两处各切一遍导致长度不一致
        write(g, ch, data, 0, data.length, type);
    }

    /** 从 off 起写 len 字节；超过单片上限则按片递归，片间延迟 {@link #CHUNK_DELAY_MS}。 */
    private void write(BluetoothGatt g, BluetoothGattCharacteristic ch,
                       byte[] data, int off, int len, int type) {
        try {
            int max = Math.max(MIN_CHUNK, Math.min(mtChunk, MAX_CHUNK_CAP));
            int n = Math.min(len, max);
            if (n <= 0) return;
            // ★ 统一走「切片 + setValue + 单参 writeCharacteristic」：
            //   SDK 33 曾引入 writeCharacteristic(char, data, off, size, type) 五参重载，
            //   但它在 SDK 34/35 又被移除（写入长度改由 characteristic 语义决定），
            //   五参写法在新 compileSdk 下直接编译不过。切片写法跨版本都成立。
            byte[] slice = new byte[n];
            System.arraycopy(data, off, slice, 0, n);
            ch.setValue(slice);
            ch.setWriteType(type);
            g.writeCharacteristic(ch);
            final int next = off + n;
            if (next < data.length) {
                main.postDelayed(() -> write(g, ch, data, next, data.length - next, type), CHUNK_DELAY_MS);
            }
        } catch (SecurityException e) {
            emit(EV_ERROR, "写入被拒：" + e.getMessage());
        }
    }

    // ==================== GATT 回调 ====================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emit(EV_CONNECTED, "connected");
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    emit(EV_ERROR, "服务发现被拒：" + e.getMessage());
                }
            } else {
                writeChar = null;
                notifyChar = null;
                ready = false;
                emit(EV_DISCONNECTED, status == 0 ? "" : ("status=" + status));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            List<BluetoothGattService> services = g.getServices();
            if (services == null || services.isEmpty()) {
                emit(EV_ERROR, "未发现任何 GATT 服务（这可能不是透传模块）");
                return;
            }
            StringBuilder sb = new StringBuilder();
            BluetoothGattCharacteristic w = null, n = null;
            // 第一趟：FFE1/FFE2
            for (BluetoothGattService s : services) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    String u = c.getUuid().toString().toUpperCase();
                    int p = c.getProperties();
                    if (u.contains("FFE1") && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) w = c;
                    if (u.contains("FFE2") && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) n = c;
                }
            }
            // 第二趟：回退到第一个可写/第一个可通知
            if (w == null || n == null) {
                for (BluetoothGattService s : services) {
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        int p = c.getProperties();
                        if (w == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) w = c;
                        if (n == null && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                                | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) n = c;
                    }
                }
            }
            writeChar = w;
            notifyChar = n;
            if (n != null) {
                try {
                    g.setCharacteristicNotification(n, true);
                    BluetoothGattDescriptor d = n.getDescriptor(CCCD);
                    if (d != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        } else {
                            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(d);
                        }
                    }
                } catch (SecurityException e) {
                    emit(EV_ERROR, "开通知被拒：" + e.getMessage());
                }
            }
            for (BluetoothGattService s : services) {
                sb.append(s.getUuid()).append(':');
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    sb.append(c.getUuid().toString().substring(0, 8)).append(' ');
                }
                sb.append(';');
            }
            emit(EV_SERVICES, sb.toString());
            ready = (w != null && n != null);
            mtChunk = negotiateChunk(g, w);
            emit(EV_READY, (w == null ? "-" : w.getUuid().toString()) + "|"
                    + (n == null ? "-" : n.getUuid().toString()) + "|" + ready);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (value == null || value.length == 0) return;
            emit(EV_DATA, bytesToHex(value) + "|" + value.length);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null || v.length == 0) return;
            emit(EV_DATA, bytesToHex(v) + "|" + v.length);
        }
    };

    // ==================== 工具 ====================

    private void emit(String type, String payload) {
        if (listener == null) return;
        main.post(() -> listener.onBleEvent(type, payload));
    }

    public static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        int n = clean.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
}
