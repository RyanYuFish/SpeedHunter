package com.github.zeehospeedhunter.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 车辆信息 / 故障码 / DID 扫描 / ECU 身份 读取服务 —— 忠实移植自 zeeho-diag
 * {@code lib/vehicleService.js}（还原自 ZeeCare WifiVehicleInfoService/WifiBaseService）。
 *
 * <p>全部为阻塞调用，调用方放在 worker 线程里串行执行（与桌面端一致）。
 * 只读，安全；不会向车辆下发任何写类指令。</p>
 */
public final class VehicleService {

    private VehicleService() {
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把 opts 的 ecu / da 统一解析成 DoIP 目标地址（默认 MCU=2，与 vehicleService.js 一致）。 */
    public static Ecus.Resolved resolveDaOf(Object ecu, Integer da) {
        if (ecu != null) return Ecus.resolveDa(ecu);
        if (da != null) return Ecus.resolveDa(da);
        return Ecus.resolveDa(DiagProtocol.DOIP_TARGET_ADDRESS_MCU);
    }

    public static final class VehicleInfo {
        public String vin = "";
        public String softwareVersion = "";
        public String partNumber = "";
        public String supplierName = "";
        public Double totalMileage;   // km，null = 读失败
        public String imei = "";
        public Integer cardCount;     // null = 读失败
        public String clusterUuid = "";
    }

    /** 完整读取车辆信息（只读）：路由激活 → 心跳 → 逐条读 DID。 */
    public static VehicleInfo readVehicleInfo(DiagClient client, Consumer<String> log) throws Exception {
        client.connect();
        client.routerActivation();
        client.startHeartbeat();

        VehicleInfo info = new VehicleInfo();
        int vcdm = DiagProtocol.DOIP_TARGET_ADDRESS_VCDM;

        try {
            DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(vcdm, DiagProtocol.UDS_READ_VIN);
            info.vin = DiagProtocol.parseVin(r.udsData);
            log.accept("VIN: " + info.vin);
        } catch (Exception e) { log.accept("读 VIN 失败: " + e.getMessage()); }
        sleep(200);

        try {
            DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(vcdm, DiagProtocol.UDS_READ_SOFTWARE_VERSION);
            info.softwareVersion = DiagProtocol.parseSoftwareVersion(r.udsData);
            log.accept("软件版本: " + info.softwareVersion);
        } catch (Exception e) { log.accept("读软件版本失败: " + e.getMessage()); }
        sleep(200);

        try {
            DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(vcdm, DiagProtocol.UDS_READ_PART_NUMBER);
            info.partNumber = DiagProtocol.parsePartNumber(r.udsData);
            log.accept("零件号: " + info.partNumber);
        } catch (Exception e) { log.accept("读零件号失败: " + e.getMessage()); }
        sleep(200);

        try {
            DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(vcdm, DiagProtocol.UDS_READ_SUPPLIER_NAME);
            info.supplierName = DiagProtocol.parseSupplierName(r.udsData);
            log.accept("供应商: " + info.supplierName);
        } catch (Exception e) { log.accept("读供应商失败: " + e.getMessage()); }
        sleep(200);

        try {
            DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(vcdm, DiagProtocol.UDS_READ_TOTAL_MILEAGE);
            double m = DiagProtocol.parseTotalMileage(r.udsData);
            info.totalMileage = m >= 0 ? m : null;
            log.accept("总里程: " + info.totalMileage);
        } catch (Exception e) { log.accept("读总里程失败: " + e.getMessage()); }
        sleep(200);

        try {
            DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(vcdm, DiagProtocol.UDS_READ_IMEI);
            info.imei = DiagProtocol.parseImei(r.udsData);
            log.accept("IMEI: " + info.imei);
        } catch (Exception e) { log.accept("读 IMEI 失败: " + e.getMessage()); }
        sleep(200);

        try {
            DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(vcdm, DiagProtocol.UDS_READ_CARD_COUNT);
            int c = DiagProtocol.parseCardCount(r.udsData);
            info.cardCount = c >= 0 ? c : null;
            log.accept("NFC 卡数: " + info.cardCount);
        } catch (Exception e) { log.accept("读 NFC 卡数失败: " + e.getMessage()); }

        return info;
    }

    /** 读取故障码（19 02 09，按状态掩码），返回原始 UDS 响应字节。 */
    public static byte[] readFaultCodes(DiagClient client, Consumer<String> log) {
        try {
            client.connect();
            client.routerActivation();
            client.startHeartbeat();
            DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(
                    DiagProtocol.DOIP_TARGET_ADDRESS_VCDM, DiagProtocol.UDS_READ_FAULT_CODE_BY_STATUS_MASK);
            return r.udsData;
        } catch (Exception e) {
            log.accept("读故障码失败: " + e.getMessage());
            return new byte[0];
        }
    }

    public static final class RawDid {
        public boolean ok;
        public String message = "";
        public String hex = "";
        public byte[] udsData = new byte[0];
    }

    /** 原始 DID 读取：UDS 22 &lt;DID&gt;，返回完整 UDS 响应 hex。 */
    public static RawDid readRawDid(DiagClient client, String didHex, int da, int timeoutMs) {
        RawDid out = new RawDid();
        try {
            int did = Integer.parseInt(didHex.replaceAll("[^0-9a-fA-F]", "").substring(0, Math.min(4, didHex.replaceAll("[^0-9a-fA-F]", "").length())), 16);
            client.connect();
            client.routerActivation();
            client.startHeartbeat();
            byte[] uds = new byte[]{0x22, (byte) ((did >> 8) & 0xFF), (byte) (did & 0xFF)};
            byte[] pdu = client.diagnosticMessage(da, uds, null, timeoutMs);
            if (pdu == null) {
                out.ok = false;
                out.message = "无响应（超时）";
                return out;
            }
            DiagProtocol.DiagData diag = DiagProtocol.parseDiagData(
                    DiagProtocol.slice(pdu, DiagProtocol.DOIP_HEADER_LENGTH, pdu.length));
            out.ok = true;
            out.hex = DiagProtocol.bytesToHex(diag.udsData);
            out.udsData = diag.udsData;
            return out;
        } catch (Exception e) {
            out.ok = false;
            out.message = e.getMessage();
            return out;
        }
    }

    public static final class Found {
        public final String did;
        public final String hex;
        public final int da;
        public Found(String did, String hex, int da) {
            this.did = did;
            this.hex = hex;
            this.da = da;
        }
    }

    public interface OnFound { void found(Found f); }
    public interface OnProgress { void progress(int scanned, int total, int found); }

    /**
     * DID 扫描（移植 doip.py --scan-dids 的研究方法）：在 [startHex, endHex] 范围内
     * 逐个发 UDS 0x22 &lt;DID&gt;，收集返回 0x62 正响应的 DID。
     */
    public static List<Found> scanDids(DiagClient client, String startHex, String endHex,
                                       Object ecu, Integer daOverride, int step, int timeoutMs,
                                       OnFound onFound, OnProgress onProgress, Consumer<String> log) throws Exception {
        Ecus.Resolved daInfo = resolveDaOf(ecu, daOverride);
        int da = daInfo.da;
        int start = Integer.parseInt(startHex.replaceAll("[^0-9a-fA-F]", ""), 16);
        int end = Integer.parseInt(endHex.replaceAll("[^0-9a-fA-F]", ""), 16);
        List<Found> found = new ArrayList<>();
        if (end < start) return found;
        if (daInfo.warn != null && !daInfo.warn.isEmpty()) log.accept("⚠ " + daInfo.warn);

        client.connect();
        client.routerActivation();
        client.startHeartbeat();

        int scanned = 0;
        int total = (end - start) / Math.max(1, step) + 1;
        for (int did = start; did <= end; did += Math.max(1, step)) {
            scanned++;
            byte[] uds = new byte[]{0x22, (byte) ((did >> 8) & 0xFF), (byte) (did & 0xFF)};
            String didStr = String.format("%04X", did);
            try {
                byte[] pdu = client.diagnosticMessage(da, uds, null, timeoutMs);
                if (pdu != null) {
                    DiagProtocol.DiagData diag = DiagProtocol.parseDiagData(
                            DiagProtocol.slice(pdu, DiagProtocol.DOIP_HEADER_LENGTH, pdu.length));
                    String hex = DiagProtocol.bytesToHex(diag.udsData);
                    if (hex.startsWith("62")) {
                        Found f = new Found(didStr, hex, da);
                        found.add(f);
                        if (onFound != null) onFound.found(f);
                    }
                }
            } catch (Exception ignored) {
            }
            if (onProgress != null && (scanned % 32 == 0 || scanned == total)) {
                onProgress.progress(scanned, total, found.size());
            }
        }
        log.accept("DID 扫描完成：扫 " + scanned + " 个，命中 " + found.size() + " 个（目标 DA=" + da + "）");
        return found;
    }

    public static final class EcuInfo {
        public String label;
        public String key;
        public int da;
        public boolean verified;
        public String warn = "";
        public String vin = "";
        public String partNumber = "";
        public String softwareVersion = "";
        public String supplierName = "";
    }

    /** 读取某 ECU 的身份 DID（VIN/零件号/软件版本/供应商），用于「多 ECU 探查」。 */
    public static EcuInfo readEcuInfo(DiagClient client, Object ecuName, Consumer<String> log) throws Exception {
        Ecus.Resolved info = resolveDaOf(ecuName, null);
        int da = info.da;
        if (info.warn != null && !info.warn.isEmpty()) log.accept("⚠ " + info.warn);
        client.connect();
        client.routerActivation();
        client.startHeartbeat();

        EcuInfo out = new EcuInfo();
        out.label = info.label;
        out.key = info.key;
        out.da = da;
        out.verified = info.verified;
        out.warn = info.warn == null ? "" : info.warn;

        Object[][] probes = {
                {"vin", DiagProtocol.UDS_READ_VIN},
                {"partNumber", DiagProtocol.UDS_READ_PART_NUMBER},
                {"softwareVersion", DiagProtocol.UDS_READ_SOFTWARE_VERSION},
                {"supplierName", DiagProtocol.UDS_READ_SUPPLIER_NAME},
        };
        for (Object[] p : probes) {
            String field = (String) p[0];
            byte[] uds = (byte[]) p[1];
            try {
                DiagProtocol.DiagData r = client.diagnosticMessageWithRetry(da, uds, null, 800, 4);
                String v;
                switch (field) {
                    case "vin": v = DiagProtocol.parseVin(r.udsData); break;
                    case "partNumber": v = DiagProtocol.parsePartNumber(r.udsData); break;
                    case "softwareVersion": v = DiagProtocol.parseSoftwareVersion(r.udsData); break;
                    default: v = DiagProtocol.parseSupplierName(r.udsData); break;
                }
                if (v != null && !v.trim().isEmpty()) {
                    switch (field) {
                        case "vin": out.vin = v; break;
                        case "partNumber": out.partNumber = v; break;
                        case "softwareVersion": out.softwareVersion = v; break;
                        default: out.supplierName = v; break;
                    }
                }
            } catch (Exception ignored) {
            }
            sleep(120);
        }
        return out;
    }
}
