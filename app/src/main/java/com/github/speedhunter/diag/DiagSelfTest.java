package com.github.speedhunter.diag;

import java.util.List;

/**
 * 诊断协议内核自检：纯逻辑、无硬件/无传输依赖，用来在「车辆诊断」页证明离线内核可用，
 * 并作为后续接入真实传输层（VCI 硬件 BLE/SPP 或原厂 App 通道）前的回归基准。
 *
 * <p>移植自 obd-diag/ZeehoDiag 的 ProtocolTest 覆盖项：ISO-TP 往返、
 * UDS 等级 1 已知向量、RS485 帧、DTC 解码、UDS 应答解析。</p>
 */
public final class DiagSelfTest {
    private DiagSelfTest() {}

    public static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append("诊断协议内核自检  (obd-diag 离线核心 v0.1)\n");
        sb.append("========================================\n");
        int pass = 0, fail = 0;

        // 1) ISO-TP 多帧往返
        try {
            byte[] payload = new byte[20];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
            List<byte[]> frames = IsoTp.encode(payload, (byte) 0x00);
            byte[] back = IsoTp.decode(frames);
            if (java.util.Arrays.equals(payload, back)) {
                sb.append("[PASS] ISO-TP 多帧往返：20 字节 → ").append(frames.size()).append(" 帧 → 还原\n");
                pass++;
            } else { sb.append("[FAIL] ISO-TP 多帧往返：还原不一致\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] ISO-TP: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 2) UDS 等级 1 seed-key 已知向量
        try {
            int key = Uds.keyLevel1(0x00000000);
            if (key == 0x8036194E) {
                sb.append(String.format("[PASS] UDS L1 seed-key：seed=0 → key=0x%08X\n", key));
                pass++;
            } else {
                sb.append(String.format("[FAIL] UDS L1 seed-key：期望 0x8036194E 实得 0x%08X\n", key));
                fail++;
            }
        } catch (Throwable t) {
            sb.append("[FAIL] UDS L1: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 3) RS485 CF/ED 帧往返
        try {
            byte[] payload = Hex.parse("01 02 03 A5");
            byte[] frame = Rs485.encode(0x59, payload);
            Rs485.Frame back = Rs485.decode(frame);
            if (back.node == 0x59 && java.util.Arrays.equals(back.payload, payload)) {
                sb.append("[PASS] RS485 帧：node=0x59 len=4 往返一致\n");
                pass++;
            } else { sb.append("[FAIL] RS485 帧：往返不一致\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] RS485: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 4) DTC 解码
        try {
            byte[] resp = Hex.parse("59 02 09 01 23 45 19 06 78 90 24");
            List<DtcCodec.DecodedDtc> dtcs = DtcCodec.decode(resp);
            if (dtcs.size() == 2
                    && "012345".equals(dtcs.get(0).code)
                    && "067890".equals(dtcs.get(1).code)) {
                sb.append("[PASS] DTC 解码：2 条 → ").append(dtcs.get(0)).append(", ").append(dtcs.get(1)).append('\n');
                pass++;
            } else { sb.append("[FAIL] DTC 解码：期望 2 条\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] DTC: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 5) UDS 应答解析（正/负响应）
        try {
            Uds.Response pos = Uds.parseResponse(0x22, Hex.parse("62 F190 563358"));
            Uds.Response neg = Uds.parseResponse(0x22, Hex.parse("7F 22 78"));
            if (pos.positive && pos.data.length == 5
                    && !neg.positive && neg.code == 0x78) {
                sb.append("[PASS] UDS 应答解析：正响应 DID 数据 3 字节 / 负响应 NRC=0x78(pending)\n");
                pass++;
            } else { sb.append("[FAIL] UDS 应答解析：语义不符\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] UDS parse: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 6) EOL 认证帧向量(2026-11-06 实抓逐字节 + ×2023 数学复核)
        try {
            byte[] a1 = ZeehoRs485Ops.EOL_AUTH_1;
            byte[] a2 = ZeehoRs485Ops.EOL_AUTH_2;
            boolean vec = Hex.format(a1).replace(" ", "").equalsIgnoreCase("4346F40901E33A2D47D5576C769D4544")
                    && Hex.format(a2).replace(" ", "").equalsIgnoreCase("4346F40902AFC04B0FE9A69FDFD54544");
            boolean math = ZeehoRs485Ops.eolProof(0x472D3AE3) == 0x766C57D5
                    && ZeehoRs485Ops.eolProof(0x0F4BC0AF) == (int) 0xDF9FA6E9L
                    && ZeehoRs485Ops.eolProof(0) == 0;
            if (vec && math) {
                sb.append("[PASS] EOL 认证：两帧常量=实抓,×2023 公式双向量+零向量复核\n");
                pass++;
            } else { sb.append("[FAIL] EOL 认证向量/公式\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] EOL 认证: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 7) NFC 学卡/删卡帧(EOL 0x39/0x3A)与序列
        try {
            byte[] on = ZeehoRs485Ops.nfcLearnSwitch(0);
            byte[] del = ZeehoRs485Ops.nfcDelete(0);
            List<byte[]> study = ZeehoRs485Ops.nfcStudySequence(0);
            List<byte[]> delSeq = ZeehoRs485Ops.nfcDeleteSequence();
            boolean ok = Hex.format(on).replace(" ", "").equalsIgnoreCase("4346F4023900 2F4544".replace(" ", ""))
                    && Hex.format(del).replace(" ", "").equalsIgnoreCase("4346F4023A00304544")
                    && study.size() == 2 && delSeq.size() == 3;
            if (ok) {
                sb.append("[PASS] NFC 帧：学习开关(3900)/删卡(3A00);删卡请求序列=认证两帧+删卡，不含主从握手\n");
                pass++;
            } else { sb.append("[FAIL] NFC 帧不符\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] NFC 帧: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 8) VciLink L3 解析（真实抓包流片段）
        try {
            byte[] stream = Hex.parse("56 00 05 00 17 69 01 01 2D");
            List<VciLink.DataFrame> fs = VciLink.parseStream(stream);
            boolean ok = fs.size() == 1 && !fs.get(0).fromVehicle
                    && fs.get(0).cmd == VciLink.CMD_POLL
                    && Hex.format(fs.get(0).data).replace(" ", "").equalsIgnoreCase("0101");
            if (ok) {
                sb.append("[PASS] VCI L3 解析：69 轮询帧 seq/cmd/data 提取一致\n");
                pass++;
            } else { sb.append("[FAIL] VCI L3 解析不符\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] VCI L3: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 9) VciLink 从 cmd68 帧提取 CF/ED
        try {
            byte[] l3stream = Hex.parse("56 00 16 00 42 68 01 00 96"
                    + " 43 46 F4 09 01 E3 3A 2D 47 D5 57 6C 76 9D 45 44 B3");
            List<VciLink.DataFrame> fs = VciLink.parseStream(l3stream);
            List<byte[]> cfs = fs.isEmpty() ? java.util.Collections.emptyList() : VciLink.extractCfFrames(fs.get(0));
            boolean ok = cfs.size() == 1
                    && Hex.format(cfs.get(0)).replace(" ", "").equalsIgnoreCase("4346F40901E33A2D47D5576C769D4544");
            if (ok) {
                sb.append("[PASS] VCI cmd68 → CF/ED 提取：厂钥块1 完整还原\n");
                pass++;
            } else { sb.append("[FAIL] CF/ED 提取不符\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] CF提取: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 10) 里程双路线帧(RS485 DID 直写 + EOL 0x4F)
        try {
            byte[] did = ZeehoRs485Ops.mileageWriteDid(0x030324);
            byte[] eol = ZeehoRs485Ops.mileageWriteEol(0x00030201);
            String didHex = Hex.format(did).replace(" ", "");
            boolean ok2 = didHex.startsWith("434614062E030903 0324".replace(" ", "")) && didHex.endsWith("4544") && didHex.length() == 26;
            String eolHex = Hex.format(eol).replace(" ", "");
            boolean ok3 = eolHex.startsWith("4346F4064F00") && eolHex.endsWith("4544");
            if (ok2 && ok3) {
                sb.append("[PASS] 里程帧：DID 0309 大端 + EOL 0x4F 小端(社区文档 3.2.2)\n");
                pass++;
            } else { sb.append("[FAIL] 里程帧不符: ").append(didHex).append(' ').append(eolHex).append('\n'); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] 里程帧: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 11) L3 组帧 + XOR crc(对照实抓帧)
        try {
            byte[] f = VciLink.buildFrame(0x17, VciLink.CMD_POLL, Hex.parse("01 01"));
            String h = Hex.format(f).replace(" ", "");
            boolean ok = h.equalsIgnoreCase("560005001769010 12D".replace(" ", ""));
            if (ok) {
                sb.append("[PASS] L3 组帧：56/69 轮询帧与实抓逐字节一致(XOR crc)\n");
                pass++;
            } else { sb.append("[FAIL] L3 组帧: ").append(h).append('\n'); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] L3 组帧: ").append(t.getMessage()).append('\n'); fail++;
        }

        // 12) wrapRs485ForVci + DTC 帧
        try {
            byte[] wrapped = ZeehoRs485Ops.wrapRs485ForVci(ZeehoRs485Ops.EOL_AUTH_1);
            // 期望负载: 00 seq + 68 + 010096 + CF(16B)
            byte[] expect = Hex.parse("01 00 96 43 46 F4 09 01 E3 3A 2D 47 D5 57 6C 76 9D 45 44");
            boolean okW = java.util.Arrays.equals(wrapped, expect);
            String rd = Hex.format(ZeehoRs485Ops.dtcReadEol()).replace(" ", "");
            List<byte[]> cl = ZeehoRs485Ops.dtcClearRs485Sequence();
            boolean okD = rd.startsWith("4346F4025100") && rd.endsWith("4544") && cl.size() == 5; // 认证2+写零3
            if (okW && okD) {
                sb.append("[PASS] VCI 包装(010096 前缀) + DTC 读(51)/清码序列(33/36/38 写零)\n");
                pass++;
            } else { sb.append("[FAIL] wrap/DTC: w=").append(okW).append(" d=").append(okD).append('\n'); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] wrap/DTC: ").append(t.getMessage()).append('\n'); fail++;
        }

        try {
            byte[] stream = Hex.parse("AA 55 00 0E 00 05 FF FA 00 00 02 00 00 F3 56 00 05 00 17 69 01 01 2D AA 55 00 0F 00 05 FF FA 01 00 00 01 02 F2");
            final int[] counts = new int[2];
            VciLink.StreamDecoder decoder = new VciLink.StreamDecoder();
            VciLink.StreamDecoder.Sink sink = new VciLink.StreamDecoder.Sink() {
                public void onL2(byte[] frame) { counts[0]++; }
                public void onData(VciLink.DataFrame frame) { if (frame.cmd == 0x69) counts[1]++; }
            };
            for (byte b : stream) decoder.accept(new byte[]{b}, 1, sink);
            if (counts[0] == 2 && counts[1] == 1) {
                sb.append("[PASS] VCI 混合流逐字节拆包与粘包\n"); pass++;
            } else { sb.append("[FAIL] VCI 混合流计数\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] VCI 混合流: ").append(t.getMessage()).append('\n'); fail++;
        }
        // 13) 官方主从切换帧 + ABS fff7 key 解析(2026-11-10 官方抓取向量)
        try {
            String exp89 = "4346894015000000000020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000FE4544";
            boolean ok89 = Hex.format(ZeehoRs485Ops.MASTER_SLAVE_89).replace(" ", "").equalsIgnoreCase(exp89);
            boolean ok71 = Hex.format(ZeehoRs485Ops.MASTER_SLAVE_CONFIRM_71).replace(" ", "")
                    .equalsIgnoreCase("4346714000000000000020000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000D14544")
                    && Hex.format(ZeehoRs485Ops.MASTER_SLAVE_EXIT_71).replace(" ", "")
                    .equalsIgnoreCase("4346714000000000000004000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000B54544");
            byte[] fff7 = Hex.parse("AA55000B0008FFF701000430A9D7932C");
            boolean okKey = java.util.Arrays.equals(ZeehoRs485Ops.parseFff7Key(fff7), Hex.parse("30A9D793"))
                    && ZeehoRs485Ops.parseFff7Key(Hex.parse("AA55000B0005FFFA0000020000F3")) == null;
            if (ok89 && ok71 && okKey) {
                sb.append("[PASS] 主从切换帧 0x89(15…20)/0x71(20/04) + fff7 key 解析 = 官方抓取向量\n");
                pass++;
            } else { sb.append("[FAIL] 主从切换/fff7 向量\n"); fail++; }
        } catch (Throwable t) {
            sb.append("[FAIL] 主从切换向量: ").append(t.getMessage()).append('\n'); fail++;
        }

        sb.append("----------------------------------------\n");
        sb.append("结果：").append(pass).append(" 通过 / ").append(fail).append(" 失败\n");
        if (fail == 0) sb.append("协议内核就绪，可接入传输层。\n");
        else sb.append("存在失败项，检查上述 [FAIL]。\n");
        return sb.toString();
    }
}
