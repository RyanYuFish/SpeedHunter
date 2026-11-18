package com.github.speedhunter.diag;

import android.content.Context;

import java.io.IOException;
import java.util.List;

/**
 * 诊断链路统一接口（2026-11-18 车主令：诊断页双传输——官方 VCI SPP + BLE 透传模块）。
 *
 * <p>实现方：
 * <ul>
 *   <li>{@link VciSppLink} —— 官方 ZSCAN VCI（SPP RFCOMM，L2/L3 封装，支持 VCI 计算 ABS key）；</li>
 *   <li>{@link BleRs485Link} —— TD5322A 等国产 BLE-RS485 透传模块（GATT write/notify，
 *       无 L2/L3 封装，不支持 VCI key ⇒ ABS 排气等 key 依赖操作在 UI 层拒绝）。</li>
 * </ul>
 * 接口面 = DiagFragment 实际调用的方法集（2026-11-18 清点）+ supportsVciKey 门控。
 */
public interface DiagLink {

    /** 传输事件回调（两个后端共用同一组七方法）。 */
    interface Listener {
        void onConnected(String device);

        void onHandshake(String mode, String detail);

        void onL2(byte[] frame);

        void onFrame(VciLink.DataFrame frame, List<byte[]> cfFrames);

        void onRaw(byte[] chunk, int len);

        void onError(String message);

        void onDisconnected();
    }

    /** 传输层是否连通（SPP socket / GATT 连接）。 */
    boolean isConnected();

    /** 数据通道是否已验证（VCI=L3 有应答；BLE=透传就绪且总线探针有应答）。 */
    boolean isDataReady();

    /** 最近一次收到数据距今毫秒；从未收到返回 -1。 */
    long lastDataRxAgeMs();

    /** 是否支持 VCI 内部计算的 ABS 安全 key（fff9/fff7）。透传模块恒 false。 */
    boolean supportsVciKey();

    /** 连接（含各自握手/就绪验证）。withSessionSetup=true 时 VCI 发 L3 前导、BLE 发只读总线探针。 */
    void connectAndHandshake(Context context, String mac, boolean withSessionSetup) throws IOException;

    /** 操作期间的总线保活（VCI=0x69 轮询；BLE=无操作）。 */
    void startPolling();

    void stopPolling();

    /** 长流程窗口内挂起心跳（VCI=FFA；BLE=无操作）。 */
    void suspendKeepAlive();

    void resumeKeepAlive();

    /** 操作阶段开道（VCI=FFA×2；BLE=无操作）。 */
    void sendFfaPair() throws IOException;

    /** 兼容旧名：删卡/各操作阶段开道的 FFA×2。 */
    void sendDeleteEntryQueries() throws IOException;

    /** 发送命令（VCI=L3 组帧；BLE=cmd68 剥前缀后写透传，其余 cmd 忽略）。 */
    void send(int cmd, byte[] data) throws IOException;

    /** 同 {@link #send}，返回 L3 seq（透传恒 0）。 */
    int sendWithSeq(int cmd, byte[] data) throws IOException;

    /** 裸字节直写（VCI=SPP 流；BLE=透传特征写）。 */
    void sendRaw(byte[] bytes) throws IOException;

    /** 发送 RS485 CF/ED 帧（VCI=cmd68+010096 包装；BLE=帧字节直写）。 */
    void sendRs485(byte[] cfFrame) throws IOException;

    /** 发送 cmd68 总线裸负载（VCI=原样；BLE=剥 3 字节通道前缀后写透传）。 */
    void sendBus(byte[] cmd68Data) throws IOException;

    void close();
}
