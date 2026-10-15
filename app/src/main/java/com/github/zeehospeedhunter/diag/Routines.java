package com.github.zeehospeedhunter.diag;

/**
 * 写类维修操作 —— 忠实移植自 zeeho-diag {@code lib/routines.js}
 * （复刻本项目 tools/rs485·diagpc 的「写类硬禁」红线）。
 *
 * <h3>安全模型（与 AGENTS.md / docs 红线一致）</h3>
 * <ul>
 *   <li><b>默认禁止一切写操作</b>；</li>
 *   <li>每个写函数都必须显式传入 {@code allowWrite=true}，否则直接抛错 —— 绝不悄悄放行；</li>
 *   <li>不臆造 ABS 排气 / 胎压匹配所需的 routine ID；由调用方（实测抓取到的）传入；</li>
 *   <li>写操作会直接改变车辆状态，UI 必须弹确认、记录硬件/固件版本、遵循回滚指引。</li>
 * </ul>
 */
public final class Routines {

    private Routines() {
    }

    public static final class Result {
        public boolean ok;
        public String message;
        public String raw;
        public Result(boolean ok, String message, String raw) {
            this.ok = ok;
            this.message = message;
            this.raw = raw;
        }
    }

    public static void checkAllow(boolean allowWrite, String action) {
        if (!allowWrite) {
            throw new IllegalStateException("安全拦截：" + action
                    + " 需要 allowWrite=true。写操作会直接改变车辆状态，请在确认风险后显式开启。");
        }
    }

    /** routines.js 的 resolveDaOf：默认 VCDM=1。 */
    static Ecus.Resolved resolveDaOf(Object ecu, Integer da) {
        if (ecu != null) return Ecus.resolveDa(ecu);
        if (da != null) return Ecus.resolveDa(da);
        return Ecus.resolveDa(DiagProtocol.DOIP_TARGET_ADDRESS_VCDM);
    }

    /** 清除故障码（UDS 0x14 FF FF FF）。 */
    public static Result clearFaultCodes(DiagClient client, boolean allowWrite,
                                         Object ecu, Integer da, int timeoutMs,
                                         java.util.function.Consumer<String> log) throws Exception {
        checkAllow(allowWrite, "清除故障码");
        Ecus.Resolved info = resolveDaOf(ecu, da);
        if (info.warn != null && !info.warn.isEmpty()) log.accept("⚠ " + info.warn);
        client.connect();
        client.routerActivation();
        client.startHeartbeat();
        byte[] uds = DiagProtocol.UDS_CLEAR_FAULT_CODE_ALL;
        byte[] pdu = client.diagnosticMessage(info.da, uds, null, timeoutMs);
        if (pdu == null) return new Result(false, "无响应（超时）", "");
        DiagProtocol.DiagData diag = DiagProtocol.parseDiagData(
                DiagProtocol.slice(pdu, DiagProtocol.DOIP_HEADER_LENGTH, pdu.length));
        String hex = DiagProtocol.bytesToHex(diag.udsData);
        boolean ok = hex.startsWith("54"); // 0x54 = 0x14 正响应
        log.accept(ok ? "清除故障码：正响应" : "清除故障码：负响应 " + hex);
        return new Result(ok, ok ? "已清除" : "被拒绝 " + hex, hex);
    }

    /** ECU 复位（UDS 0x11 0x01 硬复位）。 */
    public static Result ecuReset(DiagClient client, boolean allowWrite,
                                  Object ecu, Integer da, int timeoutMs,
                                  java.util.function.Consumer<String> log) throws Exception {
        checkAllow(allowWrite, "ECU 复位");
        Ecus.Resolved info = resolveDaOf(ecu, da);
        if (info.warn != null && !info.warn.isEmpty()) log.accept("⚠ " + info.warn);
        client.connect();
        client.routerActivation();
        client.startHeartbeat();
        byte[] uds = DiagProtocol.UDS_ECU_RESET_HARD;
        byte[] pdu = client.diagnosticMessage(info.da, uds, null, timeoutMs);
        if (pdu == null) return new Result(false, "无响应（超时）", "");
        DiagProtocol.DiagData diag = DiagProtocol.parseDiagData(
                DiagProtocol.slice(pdu, DiagProtocol.DOIP_HEADER_LENGTH, pdu.length));
        String hex = DiagProtocol.bytesToHex(diag.udsData);
        boolean ok = hex.startsWith("51"); // 0x51 = 0x11 正响应
        log.accept(ok ? "ECU 复位：正响应" : "ECU 复位：负响应 " + hex);
        return new Result(ok, ok ? "已复位" : "被拒绝 " + hex, hex);
    }

    /**
     * 通用例程控制（UDS 0x31）。用于 ABS 排气 / 胎压匹配等 —— routine ID 必须由调用方提供
     * （来自实测抓包），本模块不臆造任何厂商私有 routine ID。
     *
     * @param routineId 4 位十六进制，如 "0201"
     * @param sub       "01"(start) / "02"(stop) / "03"(result)
     * @param data      附加数据字节（可空）
     */
    public static Result runRoutine(DiagClient client, boolean allowWrite, String routineId, String sub,
                                    Object ecu, Integer da, byte[] data, int timeoutMs,
                                    java.util.function.Consumer<String> log) throws Exception {
        checkAllow(allowWrite, "例程控制(0x31)");
        if (routineId == null || !routineId.trim().matches("[0-9a-fA-F]{4}")) {
            throw new IllegalArgumentException("必须提供 4 位十六进制 routine ID（来自实测抓包），例如 0201。");
        }
        int rid = Integer.parseInt(routineId.trim(), 16);
        int subByte = Integer.parseInt(sub.trim(), 16);
        Ecus.Resolved info = resolveDaOf(ecu, da);
        if (info.warn != null && !info.warn.isEmpty()) log.accept("⚠ " + info.warn);
        client.connect();
        client.routerActivation();
        client.startHeartbeat();
        byte[] head = new byte[]{0x31, (byte) subByte, (byte) ((rid >> 8) & 0xFF), (byte) (rid & 0xFF)};
        byte[] uds = data != null && data.length > 0 ? DiagProtocol.concat(head, data) : head;
        byte[] pdu = client.diagnosticMessage(info.da, uds, null, timeoutMs);
        if (pdu == null) return new Result(false, "无响应（超时）", "");
        DiagProtocol.DiagData diag = DiagProtocol.parseDiagData(
                DiagProtocol.slice(pdu, DiagProtocol.DOIP_HEADER_LENGTH, pdu.length));
        String hex = DiagProtocol.bytesToHex(diag.udsData);
        boolean ok = hex.startsWith("71"); // 0x71 = 0x31 正响应
        log.accept(ok ? ("例程 " + routineId + " (0x" + sub + "): 正响应")
                : ("例程 " + routineId + ": 负响应 " + hex));
        return new Result(ok, ok ? "例程响应" : "被拒绝 " + hex, hex);
    }
}
