// 生成器：把 zeeho-diag/lib/datastreams.js 转成 Android 端 diag/DataStreams.java
// 用法：node tools/gen_datastreams_java.js <datastreams.js> <DataStreams.java>
const fs = require("fs");
const path = require("path");

const src = process.argv[2];
const out = process.argv[3];
const mod = require(path.resolve(src));
const groups = mod.groups;

const TITLES = {
  bms: "BMS 动力电池",
  bms2: "BMS2 副电池",
  mcu: "MCU 电机",
  vcu: "VCU 整车",
  mcuLite: "MCU(精简)",
};

function javaStr(s) {
  return JSON.stringify(String(s));
}

function emitGroup(key) {
  const items = groups[key];
  const lines = [];
  lines.push(`        new Group(${javaStr(key)}, ${javaStr(TITLES[key] || key)}, new Item[] {`);
  for (const it of items) {
    const idx = (it.idx || []).join(", ");
    const args = (it.args || []).map(javaStr).join(", ");
    lines.push(`            new Item(${javaStr(it.name)}, ${javaStr(it.unit || "")}, ${javaStr(it.did)},`
        + ` new int[]{${idx}}, new String[]{${args}}, ${javaStr(it.expr)}, ${javaStr(it.fmt || "%d")}),`);
  }
  lines.push(`        }),`);
  return lines.join("\n");
}

const order = ["bms", "bms2", "mcu", "vcu", "mcuLite"].filter(k => groups[k]);
// 防御：若 js 里还有别的组，一并带上
for (const k of Object.keys(groups)) if (!order.includes(k)) order.push(k);

const groupBlocks = order.map(emitGroup).join("\n");

const java = `package com.github.zeehospeedhunter.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ZEEHO 实时数据（数据流，「看数据流」）—— 由 zeeho-diag {@code lib/datastreams.js}
 * 经 {@code tools/gen_datastreams_java.js} 生成（${order.length} 组 / ${order.reduce((n, k) => n + groups[k].length, 0)} 项）。
 *
 * <p>来源：ZSCAN-Mb DP 数据包（CN_DIAG.lib，sqlite3mc 解密）中 ZEEHO 各 ECU 的
 * 「数值数据流」定义（UDS 0x22 读 DID + 字节位置 + 解析表达式 + 显示格式）。</p>
 *
 * <p>桌面端用 {@code new Function(...)} 直接 eval 表达式；Android 端没有 JS 引擎，
 * 这里用一个**安全的自研递归下降算术求值器**（只支持 + - * / 、括号、小数、变量 x/x1/x2/x3，
 * 以及可选的 {@code y=} 赋值前缀），语义与 JS 一致，且不引入任何代码执行风险。</p>
 */
public final class DataStreams {

    private DataStreams() {
    }

    public static final class Item {
        public final String name;
        public final String unit;
        public final String did;      // hex，如 0D37
        public final int[] idx;       // 响应字节下标（相对 62 DIDH DIDL 起点）
        public final String[] args;   // 脚本变量名，与 idx 位置对应
        public final String expr;     // 算术表达式
        public final String fmt;      // 显示格式 %d / %.1f / %.2f

        public Item(String name, String unit, String did, int[] idx, String[] args, String expr, String fmt) {
            this.name = name;
            this.unit = unit;
            this.did = did;
            this.idx = idx;
            this.args = args;
            this.expr = expr;
            this.fmt = fmt;
        }
    }

    public static final class Group {
        public final String key;
        public final String title;
        public final Item[] items;

        public Group(String key, String title, Item[] items) {
            this.key = key;
            this.title = title;
            this.items = items;
        }
    }

    public static final Group[] GROUPS = {
${groupBlocks}
    };

    public static Group byKey(String key) {
        for (Group g : GROUPS) if (g.key.equalsIgnoreCase(key)) return g;
        return GROUPS.length > 0 ? GROUPS[0] : null;
    }

    public static String[] groupTitles() {
        String[] t = new String[GROUPS.length];
        for (int i = 0; i < GROUPS.length; i++) t[i] = GROUPS[i].title + "（" + GROUPS[i].items.length + "）";
        return t;
    }

    public static String[] groupKeys() {
        String[] t = new String[GROUPS.length];
        for (int i = 0; i < GROUPS.length; i++) t[i] = GROUPS[i].key;
        return t;
    }

    // ---------- 读取结果 ----------

    public static final class Entry {
        public String name;
        public String unit;
        public String did;
        public boolean ok;
        public String value = "";   // 格式化后的显示值
        public String raw = "";     // 响应 udsData hex
        public long ms;
    }

    public interface OnItem { void item(Entry e); }

    /**
     * 读取一组数据流：对定义表中每一项发 UDS 0x22 读 DID，按脚本解析。
     * 与 vehicleService.js#readDataStreams 语义一致（阻塞，放 worker 线程）。
     */
    public static List<Entry> readDataStreams(DiagClient client, Group group, int da, int timeoutMs,
                                              OnItem onItem, Consumer<String> log) throws Exception {
        List<Entry> results = new ArrayList<>();
        if (group == null) return results;
        client.connect();
        client.routerActivation();
        client.startHeartbeat();

        for (Item it : group.items) {
            int did = Integer.parseInt(it.did, 16);
            byte[] uds = new byte[]{0x22, (byte) ((did >> 8) & 0xFF), (byte) (did & 0xFF)};
            Entry entry = new Entry();
            entry.name = it.name;
            entry.unit = it.unit;
            entry.did = it.did;
            entry.ok = false;
            long t0 = System.currentTimeMillis();
            try {
                byte[] pdu = client.diagnosticMessage(da, uds, "62" + it.did, timeoutMs);
                if (pdu != null) {
                    DiagProtocol.DiagData diag = DiagProtocol.parseDiagData(
                            DiagProtocol.slice(pdu, DiagProtocol.DOIP_HEADER_LENGTH, pdu.length));
                    int off = DiagProtocol.findReadDidResponseOffset(diag.udsData, did);
                    if (off >= 0) {
                        entry.raw = DiagProtocol.bytesToHex(diag.udsData);
                        Map<String, Integer> vars = new LinkedHashMap<>();
                        for (int k = 0; k < it.idx.length; k++) {
                            int p = off + it.idx[k];
                            int v = (p >= 0 && p < diag.udsData.length) ? (diag.udsData[p] & 0xFF) : 0;
                            String argName = k < it.args.length ? it.args[k] : ("x" + (k + 1));
                            vars.put(argName, v);
                        }
                        double raw = Eval.eval(stripAssign(it.expr), vars);
                        entry.value = formatValue(it.expr, it.fmt, raw);
                        entry.ok = true;
                    }
                }
            } catch (Exception e) {
                if (log != null) log.accept("解析失败 " + it.name + ": " + e.getMessage());
            }
            entry.ms = System.currentTimeMillis() - t0;
            results.add(entry);
            if (onItem != null) onItem.item(entry);
        }
        return results;
    }

    /** 去掉可选的 {@code y=} 赋值前缀（JS 里 {@code return (y=EXPR)} 的值就是 EXPR）。 */
    static String stripAssign(String expr) {
        String s = expr.trim();
        if (s.startsWith("y")) {
            int eq = s.indexOf('=');
            if (eq > 0) {
                String head = s.substring(0, eq).trim();
                if (head.equals("y")) return s.substring(eq + 1);
            }
        }
        return s;
    }

    /**
     * 复刻 vehicleService.js 的取整逻辑：表达式含小数、或 fmt 含小数位 → 保留 1 位；
     * 否则取整。再用 fmt 输出。
     */
    static String formatValue(String expr, String fmt, double v) {
        boolean dec = expr.matches(".*\\\\.[0-9].*")
                || (fmt != null && fmt.matches(".*%\\\\.[0-9].*"));
        double val = dec ? Math.round(v * 10.0) / 10.0 : (double) Math.round(v);
        String f = (fmt == null || fmt.isEmpty()) ? "%d" : fmt;
        if (f.indexOf('f') < 0) {
            // 整型格式（%d 等）——必须先转 long，直接把 double 交给 %d 会抛
            // IllegalFormatConversionException（d != java.lang.Double）
            return String.format(java.util.Locale.US, f, (long) Math.round(val));
        }
        try {
            return String.format(java.util.Locale.US, f, val);
        } catch (Exception e) {
            return String.valueOf(val);
        }
    }

    // ---------- 安全的算术求值器（递归下降） ----------

    /**
     * 只做四则运算的极小求值器：支持 + - * / 、括号、小数、变量（查表，缺省 0）。
     * 变量值按整数代入（响应字节），运算用 double。
     */
    static final class Eval {
        private final String s;
        private int pos;
        private final Map<String, Integer> vars;

        private Eval(String s, Map<String, Integer> vars) {
            this.s = s;
            this.vars = vars;
        }

        static double eval(String expr, Map<String, Integer> vars) {
            Eval e = new Eval(expr, vars);
            double v = e.parseExpr();
            e.skipWs();
            if (e.pos != e.s.length()) {
                throw new IllegalArgumentException("表达式解析残留：'" + expr + "' @" + e.pos);
            }
            return v;
        }

        private double parseExpr() {
            double v = parseTerm();
            for (;;) {
                skipWs();
                if (pos < s.length() && s.charAt(pos) == '+') { pos++; v += parseTerm(); }
                else if (pos < s.length() && s.charAt(pos) == '-') { pos++; v -= parseTerm(); }
                else break;
            }
            return v;
        }

        private double parseTerm() {
            double v = parseFactor();
            for (;;) {
                skipWs();
                if (pos < s.length() && s.charAt(pos) == '*') { pos++; v *= parseFactor(); }
                else if (pos < s.length() && s.charAt(pos) == '/') { pos++; double d = parseFactor(); v /= d; }
                else break;
            }
            return v;
        }

        private double parseFactor() {
            skipWs();
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                char sign = s.charAt(pos);
                pos++;
                double v = parseFactor();
                return sign == '-' ? -v : v;
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            skipWs();
            if (pos >= s.length()) throw new IllegalArgumentException("表达式意外结束");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                double v = parseExpr();
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ')') pos++;
                return v;
            }
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
                return Double.parseDouble(s.substring(start, pos));
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
                String name = s.substring(start, pos);
                Integer val = vars.get(name);
                return val == null ? 0.0 : (double) val;
            }
            throw new IllegalArgumentException("无法解析字符 '" + c + "' @" + pos);
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
`;

fs.writeFileSync(out, java);
console.log("wrote", out, "groups:", order.length, "items:", order.reduce((n, k) => n + groups[k].length, 0));
