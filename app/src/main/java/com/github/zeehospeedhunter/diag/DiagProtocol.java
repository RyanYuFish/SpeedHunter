package com.github.zeehospeedhunter.diag;

/**
 * ZEEHO 车端诊断协议核心层 —— 忠实移植自 zeeho-diag {@code lib/protocol.js}
 * （还原自 ZeeCare 1.1.1 app-service.js）。
 *
 * <p>包含：DoIP(ISO 13400) PDU 构造/解析、诊断消息拆帧、UDS 响应解析、
 * 安全访问算法、CRC16-CCITT。纯静态、无 I/O，可被桌面端思路与 Android 端共用。</p>
 *
 * <h3>两种 UDS 组帧约定并存（移植时原样保留，勿擅自统一）</h3>
 * <ul>
 *   <li>身份类读取（{@link #UDS_READ_VIN} 等）沿用 ZeeCare 原样：首字节为长度前缀，
 *       如 {@code 03 22 F1 90}；</li>
 *   <li>数据流 / DID 扫描 / 原始 DID 读取走 {@code 22 <DIDH> <DIDL>}（无长度前缀），
 *       来自 docs/10 实证。两条路径由各自调用方决定，本类不做改写。</li>
 * </ul>
 */
public final class DiagProtocol {

    private DiagProtocol() {
    }

    // ---------- 字节工具 ----------

    public static int readUint8(byte[] data, int offset) {
        if (data == null || offset >= data.length) return 0;
        return data[offset] & 0xFF;
    }

    public static int readUint16BE(byte[] data, int offset) {
        if (data == null || offset + 1 >= data.length) return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    public static long readUint32BE(byte[] data, int offset) {
        if (data == null || offset + 3 >= data.length) return 0;
        return ((long) (data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    public static void writeUint8(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
    }

    public static void writeUint16BE(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    public static void writeUint32BE(byte[] data, int offset, long value) {
        data[offset] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    public static String bytesToHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xFF;
            sb.append(v < 16 ? "0" : "").append(Integer.toHexString(v).toUpperCase());
        }
        return sb.toString();
    }

    public static String bytesToHexSpaced(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            int v = b & 0xFF;
            sb.append(v < 16 ? "0" : "").append(Integer.toHexString(v).toUpperCase()).append(' ');
        }
        return sb.toString().trim();
    }

    public static byte[] hexToBytes(String hex) {
        String h = hex == null ? "" : hex.replaceAll("[^0-9A-Fa-f]", "");
        int n = h.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String bytesToAscii(byte[] data, int start, int len) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        int end = Math.min(start + len, data.length);
        for (int i = start; i < end; i++) {
            sb.append((char) (data[i] & 0xFF));
        }
        return sb.toString();
    }

    /** 拼接若干字节数组。 */
    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += (p == null ? 0 : p.length);
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    public static byte[] slice(byte[] data, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(data == null ? 0 : data.length, to);
        if (t <= f) return new byte[0];
        byte[] out = new byte[t - f];
        System.arraycopy(data, f, out, 0, t - f);
        return out;
    }

    // ---------- DoIP 常量 ----------

    public static final int DOIP_TARGET_ADDRESS_VCDM = 1;
    public static final int DOIP_TARGET_ADDRESS_MCU = 2;
    public static final int DOIP_TARGET_ADDRESS_ALL = 65535;
    public static final int DOIP_VERSION_ISO_2019 = 3;
    public static final int DOIP_VERSION_DEFAULT = DOIP_VERSION_ISO_2019;
    public static final int DOIP_VERSION_VID_REQUEST = 255;
    public static final int DOIP_HEADER_LENGTH = 8;
    public static final int PAYLOAD_TYPE_ROUTE_ACTIVE_REQ = 5;
    public static final int PAYLOAD_TYPE_TCP_ALIVE_CHECK_REQ = 7;
    public static final int PAYLOAD_TYPE_DIAGNOSTIC_MESSAGE = 32769; // 0x8001
    public static final int ROUTE_TYPE_CAN = 1;
    public static final int ROUTE_PRIORITY_NORMAL = 0;
    public static final int DOIP_LENGTH_ROUTE_ACTIVE_REQ = 12;
    public static final int DOIP_LENGTH_DIAGNOSTIC_MESSAGE_HEADER = 4;
    /** 诊断仪源地址（ZeeCare 实测：0x0E00 = 3584）。 */
    public static final int DOIP_SOURCE_ADDRESS = 3584;

    // ---------- UDS DID 与指令表 ----------

    public static final int UDS_SERVICE_READ_DATA_BY_IDENTIFIER_RESPONSE = 98; // 0x62
    public static final int UDS_DID_PART_NUMBER = 61831;    // 0xF187
    public static final int UDS_DID_VIN = 61840;            // 0xF190
    public static final int UDS_DID_SUPPLIER_NAME = 61834;  // 0xF18A
    public static final int UDS_DID_CLUSTER_UUID = 61837;   // 0xF18D
    public static final int UDS_DID_SOFTWARE_VERSION = 61845; // 0xF195
    public static final int UDS_DID_TOTAL_MILEAGE = 61953;  // 0xF201
    public static final int UDS_DID_CARD_COUNT = 1539;      // 0x0603

    /** 与 protocol.js 的 UDS 表逐字节一致（身份类带长度前缀，见类注释）。 */
    public static final byte[] UDS_READ_CLUSTER_UUID = {3, 34, (byte) (UDS_DID_CLUSTER_UUID >> 8), (byte) UDS_DID_CLUSTER_UUID};
    public static final byte[] UDS_READ_VIN = {3, 34, (byte) (UDS_DID_VIN >> 8), (byte) UDS_DID_VIN};
    public static final byte[] UDS_READ_SOFTWARE_VERSION = {3, 34, (byte) (UDS_DID_SOFTWARE_VERSION >> 8), (byte) UDS_DID_SOFTWARE_VERSION};
    public static final byte[] UDS_READ_SUPPLIER_NAME = {3, 34, (byte) (UDS_DID_SUPPLIER_NAME >> 8), (byte) UDS_DID_SUPPLIER_NAME};
    public static final byte[] UDS_READ_PART_NUMBER = {3, 34, (byte) (UDS_DID_PART_NUMBER >> 8), (byte) UDS_DID_PART_NUMBER};
    public static final byte[] UDS_READ_IMEI = {3, 34, 7, 0};
    public static final byte[] UDS_READ_CARD_COUNT = {3, 34, 6, 3};
    public static final byte[] UDS_READ_TOTAL_MILEAGE = {3, 34, (byte) (UDS_DID_TOTAL_MILEAGE >> 8), (byte) UDS_DID_TOTAL_MILEAGE};
    public static final byte[] UDS_ECU_RESET_HARD = {2, 17, 1};
    public static final byte[] UDS_CLEAR_FAULT_CODE_ALL = {4, 20, (byte) 255, (byte) 255, (byte) 255};
    public static final byte[] UDS_READ_FAULT_CODE_BY_STATUS_MASK = {3, 25, 2, 9};
    public static final byte[] UDS_RS485_READ_FAULT_CODE = {5, 49, 1, 81, 0, 5};
    public static final byte[] UDS_RS485_NODE_ONLINE_CHECK = {5, 1, 81, 0, 5};
    public static final byte[] UDS_RS485_NODE_ONLINE_CHECK_END = {4, 49, 2, 81, 0};
    public static final byte[] UDS_EXTENDED_SESSION = {16, 3};   // 0x10 0x03
    public static final byte[] UDS_SECURITY_SEED = {39, 1};      // 0x27 0x01

    // ---------- DoIP PDU 构造 ----------

    public static byte[] buildDoipHeader(int payloadType, long payloadLength, int version) {
        byte[] header = new byte[DOIP_HEADER_LENGTH];
        writeUint8(header, 0, version);
        writeUint8(header, 1, ~version & 0xFF);
        writeUint16BE(header, 2, payloadType);
        writeUint32BE(header, 4, payloadLength);
        return header;
    }

    public static byte[] buildDoipPdu(int payloadType, byte[] payloadBytes, int version) {
        byte[] header = buildDoipHeader(payloadType, payloadBytes == null ? 0 : payloadBytes.length, version);
        return concat(header, payloadBytes == null ? new byte[0] : payloadBytes);
    }

    public static byte[] buildDoipPdu(int payloadType, byte[] payloadBytes) {
        return buildDoipPdu(payloadType, payloadBytes, DOIP_VERSION_DEFAULT);
    }

    public static byte[] buildRouteActivationReqPdu(int sourceAddress, int destAddress,
                                                    int routeType, int routePriority, int routeTimeout) {
        byte[] payload = new byte[DOIP_LENGTH_ROUTE_ACTIVE_REQ];
        writeUint16BE(payload, 0, sourceAddress);
        writeUint16BE(payload, 2, destAddress);
        writeUint8(payload, 4, routeType);
        writeUint8(payload, 5, routePriority);
        writeUint16BE(payload, 10, routeTimeout);
        return buildDoipPdu(PAYLOAD_TYPE_ROUTE_ACTIVE_REQ, payload);
    }

    public static byte[] buildRouteActivationReqPdu() {
        return buildRouteActivationReqPdu(DOIP_SOURCE_ADDRESS, DOIP_TARGET_ADDRESS_VCDM,
                ROUTE_TYPE_CAN, ROUTE_PRIORITY_NORMAL, 30);
    }

    public static byte[] buildRouteAliveCheckReqPdu(int sourceAddress, int destAddress) {
        byte[] payload = new byte[DOIP_LENGTH_ROUTE_ACTIVE_REQ];
        writeUint16BE(payload, 0, sourceAddress);
        writeUint16BE(payload, 2, destAddress);
        writeUint8(payload, 4, ROUTE_TYPE_CAN);
        writeUint8(payload, 5, ROUTE_PRIORITY_NORMAL);
        writeUint16BE(payload, 10, 30);
        return buildDoipPdu(PAYLOAD_TYPE_TCP_ALIVE_CHECK_REQ, payload);
    }

    public static byte[] buildRouteAliveCheckReqPdu() {
        return buildRouteAliveCheckReqPdu(DOIP_SOURCE_ADDRESS, DOIP_TARGET_ADDRESS_VCDM);
    }

    public static byte[] buildDiagnosticMessagePdu(int sa, int ta, byte[] udsData) {
        byte[] payload = new byte[DOIP_LENGTH_DIAGNOSTIC_MESSAGE_HEADER];
        writeUint16BE(payload, 0, sa);
        writeUint16BE(payload, 2, ta);
        return buildDoipPdu(PAYLOAD_TYPE_DIAGNOSTIC_MESSAGE, concat(payload, udsData == null ? new byte[0] : udsData));
    }

    // ---------- DoIP 解析 ----------

    public static boolean isSupportedVersion(int version) {
        return version == 1 || version == 2 || version == 3 || version == DOIP_VERSION_VID_REQUEST;
    }

    public static final class DoipHeader {
        public boolean valid;
        public int version;
        public int inverseVersion;
        public int payloadType;
        public long payloadLength;
    }

    public static DoipHeader parseDoipHeader(byte[] data) {
        DoipHeader h = new DoipHeader();
        if (data == null || data.length < DOIP_HEADER_LENGTH) return h;
        h.version = readUint8(data, 0);
        h.inverseVersion = readUint8(data, 1);
        h.payloadType = readUint16BE(data, 2);
        h.payloadLength = readUint32BE(data, 4);
        h.valid = h.version == DOIP_VERSION_VID_REQUEST
                || (((h.version ^ h.inverseVersion) == 0xFF) && isSupportedVersion(h.version));
        return h;
    }

    /** 从字节流缓存中切出完整 DoIP PDU（移植 extractCompleteDoipPdus）。 */
    public static final class ExtractResult {
        public java.util.List<byte[]> packets = new java.util.ArrayList<>();
        public byte[] remaining = new byte[0];
    }

    public static ExtractResult extractCompleteDoipPdus(byte[] buffer) {
        ExtractResult r = new ExtractResult();
        int offset = 0;
        int n = buffer == null ? 0 : buffer.length;
        while (offset + DOIP_HEADER_LENGTH <= n) {
            DoipHeader h = parseDoipHeader(slice(buffer, offset, offset + DOIP_HEADER_LENGTH));
            if (!h.valid) {
                offset += 1;
                continue;
            }
            long packetLength = DOIP_HEADER_LENGTH + h.payloadLength;
            if (offset + packetLength > n) break;
            r.packets.add(slice(buffer, offset, (int) (offset + packetLength)));
            offset += (int) packetLength;
        }
        r.remaining = slice(buffer, offset, n);
        return r;
    }

    /** 解析诊断消息载荷（DoIP payload 部分，去掉 8 字节 DoIP 头之后的内容）。 */
    public static final class DiagData {
        public int sourceAddress;
        public int destAddress;
        public int fragmentFlag;
        public int fragmentNumber;
        public byte[] udsData = new byte[0];
    }

    public static DiagData parseDiagData(byte[] payload) {
        DiagData d = new DiagData();
        if (payload == null) return d;
        d.sourceAddress = readUint16BE(payload, 0);
        d.destAddress = readUint16BE(payload, 2);
        d.fragmentFlag = readUint8(payload, 4);
        d.fragmentNumber = readUint16BE(payload, 6);
        d.udsData = slice(payload, 8, payload.length);
        return d;
    }

    // ---------- UDS 响应解析 ----------

    public static int findReadDidResponseOffset(byte[] udsData, int did) {
        if (udsData == null || udsData.length < 3) return -1;
        int didHigh = (did >> 8) & 0xFF;
        int didLow = did & 0xFF;
        for (int offset = 0; offset <= udsData.length - 3; offset++) {
            if ((udsData[offset] & 0xFF) == UDS_SERVICE_READ_DATA_BY_IDENTIFIER_RESPONSE
                    && (udsData[offset + 1] & 0xFF) == didHigh
                    && (udsData[offset + 2] & 0xFF) == didLow) {
                return offset;
            }
        }
        return -1;
    }

    public static byte[] getReadDidResponseDataBytes(byte[] udsData, int did) {
        int offset = findReadDidResponseOffset(udsData, did);
        if (offset < 0) return new byte[0];
        int dataStart = offset + 3;
        if (udsData.length <= dataStart) return new byte[0];
        return slice(udsData, dataStart, udsData.length);
    }

    public static String parseReadDidAsciiValue(byte[] udsData, int did) {
        int offset = findReadDidResponseOffset(udsData, did);
        if (offset < 0) return "";
        int dataStart = offset + 3;
        if (udsData.length <= dataStart) return "";
        return bytesToAscii(udsData, dataStart, udsData.length - dataStart);
    }

    public static long parseReadDidUnsignedValue(byte[] udsData, int did) {
        byte[] dataBytes = getReadDidResponseDataBytes(udsData, did);
        if (dataBytes.length == 0) return -1;
        long value = 0;
        for (byte b : dataBytes) value = (value << 8) | (b & 0xFF);
        return value;
    }

    public static String parseVin(byte[] udsData) {
        String vin = parseReadDidAsciiValue(udsData, UDS_DID_VIN);
        return vin.length() != 17 ? "" : vin;
    }

    public static String parseSoftwareVersion(byte[] udsData) {
        return parseReadDidAsciiValue(udsData, UDS_DID_SOFTWARE_VERSION);
    }

    public static String parsePartNumber(byte[] udsData) {
        return parseReadDidAsciiValue(udsData, UDS_DID_PART_NUMBER).trim();
    }

    public static String parseSupplierName(byte[] udsData) {
        return parseReadDidAsciiValue(udsData, UDS_DID_SUPPLIER_NAME);
    }

    /** 总里程：原始无符号值 * 0.1（km）。 */
    public static double parseTotalMileage(byte[] udsData) {
        return parseReadDidUnsignedValue(udsData, UDS_DID_TOTAL_MILEAGE) * 0.1;
    }

    public static int parseCardCount(byte[] udsData) {
        int offset = findReadDidResponseOffset(udsData, UDS_DID_CARD_COUNT);
        if (offset < 0) return -1;
        int dataStart = offset + 3;
        if (udsData.length <= dataStart) return -1;
        int count = udsData[dataStart] & 0xFF;
        return (count < 0 || count > 2) ? -1 : count;
    }

    public static String parseImei(byte[] udsData) {
        return parseReadDidAsciiValue(udsData, 1792); // 0x0700
    }

    // ---------- 安全访问（Level 1） ----------

    public static final byte[] SECURITY_XOR = {0x34, 0x66, 0x2E, (byte) 0x8D};

    /** computeLevel1Key(seed, xor) —— 与 protocol.js 位运算逐位一致。 */
    public static byte[] computeLevel1Key(byte[] seed, byte[] xor) {
        int[] cal = new int[4];
        for (int i = 0; i < 4; i++) cal[i] = (seed[i] ^ xor[i]) & 0xFF;
        int key0 = (((cal[2] & 0xF0) << 4) | (cal[3] & 0xF0)) & 0xFF;
        int key1 = (((cal[3] & 0x2F) << 2) | (cal[1] & 0x03)) & 0xFF;
        int key2 = (((cal[1] & 0xFC) >> 2) | (cal[0] & 0xC0)) & 0xFF;
        int key3 = (((cal[0] & 0x0F) << 4) | (cal[2] & 0x0F)) & 0xFF;
        return new byte[]{(byte) key0, (byte) key1, (byte) key2, (byte) key3};
    }

    // ---------- CRC16-CCITT（MCP2 用，0x1021） ----------

    public static int crc16(byte[] buffer, int offset, int length) {
        if (buffer == null) return 0;
        int end = length >= 0 ? Math.min(offset + length, buffer.length) : buffer.length;
        int crc = 0xFFFF;
        for (int i = offset; i < end; i++) {
            crc ^= (buffer[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    public static int crc16(byte[] buffer) {
        return crc16(buffer, 0, -1);
    }
}
