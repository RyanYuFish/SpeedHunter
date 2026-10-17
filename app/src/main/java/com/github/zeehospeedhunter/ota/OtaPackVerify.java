package com.github.zeehospeedhunter.ota;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <b>整车升级包的完整性自检</b> —— 在手机上把 U-Boot 真正会验的那几道校验<b>原样跑一遍</b>。
 *
 * <h3>为什么必须有这个类（这是刷自制固件唯一的护栏）</h3>
 * <p>2026-10-06 实机刷写<b>变砖</b>：协议层全通（版本协商✔ 数据 100% ✔ 结束帧 ✔ 校验 ack ✔），
 * 车机确实进了刷写阶段，但<b>刷到一半停了</b> —— 因为包本身不自洽。
 * U-Boot 逐分区 {@code nand erase → fatload → nand write → 回读 md5}，
 * 每写完一个分区回读 md5，<b>不符即 {@code exit -1}</b> ⇒ 停在半刷状态，主机起不来。</p>
 *
 * <p>★ <b>关键教训</b>：车机侧「校验失败」提示<b>只覆盖最前面那一道</b>（{@code MD5SUM*} 总校验）。
 * 它过了之后剩下的三道（脚本 CRC、分区表、脚本内 74 个硬编码 MD5）由 U-Boot 执行，
 * <b>失败时仪表只有进度条停住、没有任何错误信息</b>。所以必须在推送<b>之前</b>在手机上验完。</p>
 *
 * <h3>六项检查（顺序即 U-Boot 的执行顺序）</h3>
 * <ol>
 *   <li><b>内层 {@code MD5SUM*}</b>（pack 偏移 {@code 0x00EA}）= {@code md5(pack[0x400:])}。
 *       ⇒ 车机侧「升级包校验失败」说的就是这一道。</li>
 *   <li><b>main 脚本 uImage 的 dataCRC</b>（header {@code +0x18}）与 <b>headerCRC</b>（{@code +0x04}）。
 *       ⇒ dataCRC 错 ⇒ <b>U-Boot 拒执行 main 脚本</b>：init 脚本照跑（它 CRC 是对的）
 *       把分区擦了，main 不执行 ⇒ <b>只擦不写</b>，正是 10/06 的现场。</li>
 *   <li><b>分区表 MD5</b>（表头 {@code 0xC80}，每项 {@code 0x80}，MD5 在项内 {@code +0x20}）。
 *       ★ 镜像起点是 {@code off + 0x400}，而长度用 {@code len}（不是 {@code len-0x400}）。</li>
 *   <li><b>main 脚本里 74 个硬编码 MD5</b> —— 每个分区块写完后回读校验，
 *       公式 {@code md5(pack[0x400+OFF : 0x400+OFF+SIZE])}。</li>
 * </ol>
 *
 * <h3>★ 已验证的事实：原厂也会出不自洽的包</h3>
 * <p>{@code 1787815351970919959.zip}（OSS 私有桶下来的整车包）<b>出厂状态下第 1、2 道就是错的</b>：
 * {@code MD5SUM*} 存 {@code fd2c3809…} 而应为 {@code 3b8ac91a…}；
 * main 脚本 dataCRC 存 {@code b171d4d2} 而实际 {@code 67b45acf}。
 * 它 74 个脚本内 MD5 与 19 项分区表倒是全对的。</p>
 * <p>与之相对，{@code OTA_zip_ORIG.zip} 的内层与原厂恢复基准
 * {@code firmware/gemini_pack/GEMINI_PACK.BIN} <b>逐字节完全相同</b>，六项全对。</p>
 * <p>⇒ <b>不要相信「原厂包」这个标签，要相信本类跑出来的结果。</b></p>
 *
 * <p>本类只读不写，不改任何字节；结论仅用于放行/拦截推送。</p>
 */
public final class OtaPackVerify {

    /** pack 内布局常量（全部由 {@code tools/flashpack/*.py} 与固件实证坐实）。 */
    private static final String MAGIC = "GEMINI";
    private static final int MD5SUM_VAL_OFF = 0x00EA;
    private static final int TABLE_START = 0xC80;
    private static final int ENTRY_SIZE = 0x80;
    private static final int NAME_LEN = 16;
    private static final int ENTRY_MD5_OFF = 0x20;
    private static final int ENTRY_OFF_OFF = 0x44;
    private static final int ENTRY_LEN_OFF = 0x48;
    /** ★ 槽位前缀：真正镜像起点是 off + 0x400。 */
    private static final int IMAGE_SKIP = 0x400;
    private static final int UIMAGE_SIZE_OFF = 0x0C;
    private static final int UIMAGE_HCRC_OFF = 0x04;
    private static final int UIMAGE_DCRC_OFF = 0x18;
    private static final int UIMAGE_NAME_OFF = 0x20;
    private static final int UIMAGE_NAME_LEN = 32;
    private static final String SCRIPT_NAME = "main update script";
    private static final String EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e";

    /** 整车包标称大小（10/06、10/08、10/17 三次实测一致）。 */
    public static final long EXPECT_PACK_SIZE = 128094208L;

    /**
     * 期望识别到的<b>分区表项数</b>与<b>脚本内 MD5 校验点数</b>。
     *
     * <p>★ 这两个数字是<b>护栏，不是参考</b>。docs/54 开头就警告：
     * 「19 分区、74 脚本校验点等数量不应直接套用于其他版本」——
     * 也就是说，数量对不上有可能是<b>换版本了</b>，也有可能是<b>包本身残缺</b>。
     * 本实现的取舍是<b>宁可拦住，不误放</b>：数量不符即判负并提示「包结构与已知版本不符，
     * 不能按本规则判定」，让人去用 Mac 侧工具逐项看，而不是放一个说不清来源的包上车。
     * 确认是合法新版本后，把常量改掉即可。</p>
     */
/**
     * SquashFS {@code flags} 位掩码。
     *
     * <p>★ 注意：{@code squashfs_fs.h} 里这些常量写的是<b>位序号</b>（0、1、2…），
     * 用的时候必须左移成掩码。原厂 SPAPP 实测 {@code flags=0x0440}，
     * 即 {@code NO_XATTRS|UNCOMPRESSED_IDS}，与 {@code unsquashfs -s} 输出的
     * 「not exportable via NFS / Number of xattr ids 0」一致。</p>
     */
    private static final int SQUASHFS_EXPORTABLE = 0x0100;       // bit 8
    private static final int SQUASHFS_NO_XATTRS = 0x0400;         // bit 10
    private static final int SQUASHFS_UNCOMPRESSED_IDS = 0x1000; // bit 12

    private static final int EXPECT_PARTITIONS = 19;
    private static final int EXPECT_MD5_POINTS = 74;

    private OtaPackVerify() {
    }

    /** 单项检查的结果。 */
    public static final class Check {
        public final String name;
        public boolean ok;
        public String detail = "";

        Check(String name) {
            this.name = name;
        }

        public String line() {
            return (ok ? "  ✅ " : "  ❌ ") + name + (detail.isEmpty() ? "" : "  " + detail);
        }
    }

    /** 整体结论。 */
    public static final class Result {
        public String packMd5 = "";
        public long packSize;
        public final List<Check> checks = new ArrayList<>();
        /** 结论要点（中文，可直接进日志/弹窗）。 */
        public final List<String> notes = new ArrayList<>();

        public boolean allOk() {
            for (Check c : checks) {
                if (!c.ok) {
                    return false;
                }
            }
            return !checks.isEmpty();
        }

        public int failedCount() {
            int n = 0;
            for (Check c : checks) {
                if (!c.ok) {
                    n++;
                }
            }
            return n;
        }

        /**
         * 会不会<b>半刷</b>（把车机刷到起不来）。
         *
         * <p>★ 这是唯一真正要拦的事：只要有一道校验不过，U-Boot 就会在写完若干分区后
         * {@code exit -1}，留下半刷状态 —— 10/06 就是这个下场。
         * 「MD5SUM* 不对」车机还会给个提示（停在最前面，压根没开始刷）；
         * 后三道不对则是<b>刷到一半才停</b>，性质更糟。</p>
         */
        public boolean willHalfBrick() {
            return !allOk();
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("内层 GEMINI_PACK.BIN  ").append(packSize).append(" 字节\n");
            sb.append("内层 MD5  ").append(packMd5).append('\n');
            for (Check c : checks) {
                sb.append(c.line()).append('\n');
            }
            if (allOk()) {
                sb.append("\n✔ ").append(checks.size())
                        .append(" 项检查全过 —— 包内部自洽且结构正常，U-Boot 能逐分区回读通过");
            } else {
                sb.append("\n✖ ").append(failedCount()).append(" 项检查不通过 ⇒ ")
                        .append("U-Boot 会在写完若干分区后 exit -1，留下半刷状态（10/06 变砖同款）");
            }
            for (String n : notes) {
                sb.append('\n').append(n);
            }
            return sb.toString();
        }
    }

    /**
     * 校验一个升级<b>zip</b>（自动解出内层 {@code GEMINI_PACK.BIN} 后验）。
     *
     * @param progress 可为 null；每步回调一行（用于界面进度提示）
     * @throws Exception zip 打不开 / 内层缺失 / 大小异常
     */
    public static Result verifyZip(File zip, Progress progress) throws Exception {
        Result r = new Result();
        File pack = extractPack(zip);
        try {
            return verifyPack(pack, progress);
        } finally {
            if (pack != null) {
                //noinspection ResultOfMethodCallIgnored
                pack.delete();
            }
        }
    }

    /** 进度回调。 */
    public interface Progress {
        void onStage(String line);
    }

    /**
     * 解出内层包，并做<b>ZIP 容器检查</b>（docs/54 §1）。
     *
     * <p>约定：外层 ZIP <b>只能有一个</b>文件条目，且必须<b>逐字</b>叫
     * {@code GEMINI_PACK.BIN}。这里不按「后缀匹配」挑一个交差 ——
     * 多条目、带路径别名（{@code sub/GEMINI_PACK.BIN}）、重复条目一律拒绝，
     * 因为「挑一个能读的」等于替包背书，而刷写是不可逆的。</p>
     */
    /**
     * 对外暴露：从升级 zip 解出内层裸 {@code GEMINI_PACK.BIN}。
     *
     * <p>10086 通道（{@link OtaP2pToolPusher}）需要裸包，而 10950 用整个 zip，
     * 两者格式不同，所以这里把容器检查过的解包结果直接给出去，避免各写一套。</p>
     */
    public static File extractInnerPack(File zip) throws Exception {
        return extractPack(zip);
    }

    private static File extractPack(File zip) throws Exception {
        ZipFile zf = new ZipFile(zip);
        try {
            ZipEntry pack = null;
            int fileEntries = 0;
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                fileEntries++;
                // U-Boot 只认官方那种「单条目」形状。多余载荷、路径别名都拒绝，
                // 而不是静默挑一个名字匹配的就用。
                if (!"GEMINI_PACK.BIN".equals(e.getName())) {
                    throw new IllegalArgumentException("zip 含有非 GEMINI_PACK.BIN 文件：" + e.getName());
                }
                if (pack != null) {
                    throw new IllegalArgumentException("zip 含有重复的 GEMINI_PACK.BIN 条目");
                }
                if (e.getSize() < 0) {
                    throw new IllegalArgumentException("GEMINI_PACK.BIN 未提供有效的解压大小");
                }
                pack = e;
            }
            if (pack == null) {
                throw new IllegalArgumentException("zip 里没有 GEMINI_PACK.BIN —— 这不是整车升级包");
            }
            if (fileEntries != 1) {
                throw new IllegalArgumentException("zip 必须只有一个文件条目，实际 " + fileEntries + " 个");
            }
            if (pack.getSize() != EXPECT_PACK_SIZE) {
                throw new IllegalArgumentException("内层 GEMINI_PACK.BIN 是 " + pack.getSize()
                        + " 字节，期望 " + EXPECT_PACK_SIZE + " —— 包被截断或不是整车包");
            }
            File out = File.createTempFile("ota_pack_", ".bin");
            InputStream in = zf.getInputStream(pack);
            java.io.OutputStream os = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                closeQuietly(os);
                closeQuietly(in);
            }
            return out;
        } finally {
            try {
                zf.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 直接校验一个已解出的 {@code GEMINI_PACK.BIN}。
     *
     * <p>128MB 全量读进内存在手机上不可行（App 堆上限 256MB），
     * 所以按需随机访问：先 {@code RandomAccessFile} 定位，只把需要的区间读进内存。</p>
     */
    public static Result verifyPack(File pack, Progress progress) throws Exception {
        Result r = new Result();
        r.packSize = pack.length();
        java.io.RandomAccessFile f = new java.io.RandomAccessFile(pack, "r");
        try {
            // ---- 头部基本检查 ----
            byte[] head = new byte[0x400];
            f.seek(0);
            f.readFully(head);
            String magic = new String(head, 0, 6, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!MAGIC.equals(magic)) {
                throw new IllegalArgumentException("开头不是 GEMINI 魔数（读到「" + magic
                        + "」），这不是 GEMINI_PACK.BIN");
            }
            if (progress != null) {
                progress.onStage("算内层 MD5 …");
            }
            r.packMd5 = md5OfRange(f, 0, r.packSize);

            // ---- ① 内层 MD5SUM* ----
            if (progress != null) {
                progress.onStage("① 校验内层 MD5SUM* …");
            }
            Check c1 = new Check("① 内层 MD5SUM*（车机「升级包校验失败」查的就是它）");
            String storedSum = ascii(head, MD5SUM_VAL_OFF, 32);
            r.checks.add(c1);
            if (progress != null) {
                progress.onStage("① 校验内层 MD5SUM* …（要读 128MB）");
            }
            String wantSum = md5OfRange(f, IMAGE_SKIP, r.packSize - IMAGE_SKIP);
            c1.ok = wantSum.equals(storedSum);
            c1.detail = c1.ok ? "OK " + storedSum : "存 " + storedSum + "  应为 " + wantSum;

            // ---- ② main 脚本 uImage 的两个 CRC ----
            if (progress != null) {
                progress.onStage("② 校验 main 脚本 uImage CRC …");
            }
            Check c2 = new Check("② main 脚本 uImage dataCRC + headerCRC");
            r.checks.add(c2);
            int[] script = findMainScript(f, r.packSize);
            if (script == null) {
                c2.ok = false;
                c2.detail = "✖ 找不到 main update script uImage";
            } else {
                int hoff = script[0];
                int doff = script[1];
                int size = script[2];
                byte[] hdr = new byte[64];
                f.seek(hoff);
                f.readFully(hdr);
                long storedDcrc = be32(hdr, UIMAGE_DCRC_OFF);
                long calcDcrc = crc32OfRange(f, doff, size);
                long storedHcrc = be32(hdr, UIMAGE_HCRC_OFF);
                byte[] h0 = hdr.clone();
                java.util.Arrays.fill(h0, UIMAGE_HCRC_OFF, UIMAGE_HCRC_OFF + 4, (byte) 0);
                long calcHcrc = crc32(h0, 0, 64);
                boolean dOk = storedDcrc == calcDcrc;
                boolean hOk = storedHcrc == calcHcrc;
                c2.ok = dOk && hOk;
                c2.detail = String.format("dataCRC %08x/%08x%s headerCRC %08x/%08x%s",
                        storedDcrc, calcDcrc, dOk ? "✔" : " ✘", storedHcrc, calcHcrc, hOk ? "✔" : " ✘");
                if (!dOk) {
                    r.notes.add("★ dataCRC 不符 ⇒ U-Boot 会**拒执行 main 脚本**："
                            + "init 脚本照跑把分区擦了，main 不执行 ⇒ 只擦不写（10/06 现场）");
                }
            }

            // ---- ③ 分区表 MD5（含识别数量与区间核对）----
            if (progress != null) {
                progress.onStage("③ 校验分区表 MD5 …");
            }
            Check c3 = new Check("③ 分区表 MD5（每项回读）");
            r.checks.add(c3);
            TableResult t3 = checkPartitionTable(f, r.packSize);
            // ★ 数量不符必须判负：docs/54 §3 明确要求「核对识别数量为 19 项」。
            //   曾经这里把 "19" 写成字面量，于是解析只认出 12 项且全对也会显示「19 项全对」并放行。
            boolean countOk = t3.total == EXPECT_PARTITIONS;
            boolean rangeOk = t3.oob == 0;
            c3.ok = t3.bad == 0 && countOk && rangeOk;
            StringBuilder d3 = new StringBuilder();
            d3.append(t3.bad == 0 ? "已识别 " + t3.total + " 项，MD5 全对"
                    : t3.bad + " / " + t3.total + " 项 MD5 不符");
            if (!countOk) {
                d3.append("；★ 识别数应为 ").append(EXPECT_PARTITIONS)
                        .append("，实际 ").append(t3.total)
                        .append(" —— 包结构与已知版本不符，不能按本规则判定");
            }
            if (!rangeOk) {
                d3.append("；").append(t3.oob).append(" 项区间越界");
            }
            c3.detail = d3.toString();

            // ---- ④ main 脚本内硬编码 MD5（含识别数量与配对核对）----
            if (progress != null) {
                progress.onStage("④ 校验 main 脚本内硬编码 MD5 …");
            }
            Check c4 = new Check("④ main 脚本内硬编码 MD5（每个分区写完回读）");
            r.checks.add(c4);
            if (script == null) {
                c4.ok = false;
                c4.detail = "跳过（找不到脚本）";
            } else {
                ScriptResult t4 = checkScriptMd5s(f, script[1], script[2]);
                boolean c4count = t4.total == EXPECT_MD5_POINTS;
                c4.ok = t4.bad == 0 && t4.oob == 0 && c4count;
                StringBuilder d4 = new StringBuilder();
                d4.append(t4.bad == 0 ? "已识别 " + t4.total + " 项，全对"
                        : t4.bad + " / " + t4.total + " 项不符");
                if (t4.oob > 0) {
                    d4.append("；").append(t4.oob).append(" 项区间越界");
                }
                if (!c4count) {
                    d4.append("；★ 校验点应为 ").append(EXPECT_MD5_POINTS)
                            .append("，实际 ").append(t4.total)
                            .append(" —— 脚本结构可能已变，配对关系需重新分析");
                }
                if (t4.fatloads > t4.total) {
                    d4.append("；fatload ").append(t4.fatloads)
                            .append(" 个但只配对到 ").append(t4.total).append(" 个校验点");
                }
                c4.detail = d4.toString();
            }

            // ---- ⑤⑥ SPAPP superblock / GD32 S-record ----
            checkPayloads(r, f, progress);
        } finally {
            try {
                f.close();
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    // ============================================================ ⑤ SPAPP / GD32 结构

    /**
     * ⑤ 两个最易被自制包搞坏的分区的<b>结构性</b>检查。
     *
     * <p>六项检查只能证明「包自洽」，证明不了「内容合理」。docs/54 §4/§5 指出两类
     * <b>结构损坏</b>，它们照样能通过全部六项 MD5/CRC，却让刷完的仪表起不来：</p>
     * <ul>
     *   <li><b>SPAPP</b> 是 SquashFS。重建时若用了宿主 umask，原厂 {@code 0777}
     *       会被降成 {@code 0755}（实测踩过：32 个可执行文件失 +x）；压缩格式、
     *       block size、inode 数与原厂不一致也会让 Android 挂载失败。</li>
     *   <li><b>GD32_APP</b> 是 S-record，不是裸 BIN。记录损坏（长度字段/一补校验和
     *       不对）时仪表 MCU 编程拿到的是垃圾，刷写阶段才发现。</li>
     * </ul>
     *
     * <p>★ 这里只做<b>只读、廉价</b>的检查（读 superblock 96 字节 + 逐行校验 S-record），
     * 不解 SquashFS、不比对原厂逐文件元数据 —— 那些留给 Mac 侧工具。</p>
     */
    private static void checkPayloads(Result r, java.io.RandomAccessFile f, Progress progress) {
        // ---- ⑤ SPAPP：SquashFS 4.0 superblock ----
        if (progress != null) {
            progress.onStage("⑤ 检查 SPAPP(SquashFS) 与 GD32(S-record) 结构 …");
        }
        Check c5 = new Check("⑤ SPAPP SquashFS superblock");
        r.checks.add(c5);
        try {
            long off = findPartitionOffset(f, "spapp.");
            if (off < 0) {
                c5.ok = false;
                c5.detail = "分区表里找不到 spapp.";
            } else {
                f.seek(IMAGE_SKIP + off);
                byte[] sb = new byte[96];
                f.readFully(sb);
                // SquashFS superblock 里的整数字段是小端
                int magic = (int) le32(sb, 0);
                int inodes = (int) le32(sb, 4);
                int mkfsTime = (int) le32(sb, 8);
                int blockSize = (int) le32(sb, 12);
                int fragCount = (int) le32(sb, 16);
                int comp = le16(sb, 0x14);
                int blockLog = le16(sb, 0x16);
                int flags = le16(sb, 0x18);
                int idCount = le16(sb, 0x1A);
                int vmaj = le16(sb, 0x1C);
                int vmin = le16(sb, 0x1E);
                // 'hsqs' 小端 = 0x73717368
                boolean magicOk = magic == 0x73717368;
                boolean verOk = vmaj == 4 && vmin == 0;
                boolean compOk = comp == 3;               // 3 = LZO (lzo1x_999)
                // ★ flags 的常量在 squashfs_fs.h 里是<b>位序号</b>，不是位掩码 ——
                //   EXPORTABLE 是 bit 8 ⇒ 掩码 0x0100；NO_XATTRS 是 bit 10 ⇒ 0x0400。
                //   写成 0x0008 会误判成 CHECK 位（实测原厂 flags=0x0440）。
                boolean nfsOff = (flags & SQUASHFS_EXPORTABLE) == 0;
                boolean noXattr = (flags & SQUASHFS_NO_XATTRS) != 0;
                StringBuilder d = new StringBuilder();
                d.append(magicOk ? "magic✔ " : String.format("magic✘%08x ", magic));
                d.append(verOk ? "4.0✔ " : String.format("版本 %d.%d✘ ", vmaj, vmin));
                d.append(compOk ? "LZO✔" : String.format("压缩码 %d✘(期望 3)", comp));
                d.append(" block=").append(blockSize).append("/log").append(blockLog);
                d.append(" inode=").append(inodes).append(" id=").append(idCount);
                d.append(" flags=").append(String.format("0x%04x", flags));
                d.append(nfsOff ? " NFS关✔" : " ★NFS开");
                d.append(noXattr ? " 无xattr✔" : " ★有xattr");
                d.append(" mkfs=").append(mkfsTime).append(" frag=").append(fragCount);
                c5.detail = d.toString();
                c5.ok = magicOk && verOk && compOk && nfsOff && noXattr;
                if (!c5.ok) {
                    r.notes.add("★ SPAPP superblock 与原厂不符（"
                            + (magicOk ? "" : "magic; ")
                            + (verOk ? "" : "版本; ")
                            + (compOk ? "" : "压缩格式; ")
                            + (nfsOff ? "" : "NFS导出位; ")
                            + (noXattr ? "" : "xattr; ")
                            + "）⇒ 刷完 Android 可能挂载失败 / 可执行文件失 +x");
                }
            }
        } catch (Exception e) {
            c5.ok = false;
            c5.detail = "读取失败：" + e;
        }

        // ---- ⑥ GD32_APP：S-record 每条记录的一补校验和 ----
        Check c6 = new Check("⑥ GD32_APP S-record 记录校验");
        r.checks.add(c6);
        try {
            long off = findPartitionOffset(f, "GD32_APP");
            if (off < 0) {
                c6.ok = false;
                c6.detail = "分区表里找不到 GD32_APP";
                return;
            }
            long len = findPartitionLen(f, "GD32_APP");
            f.seek(IMAGE_SKIP + off);
            byte[] buf = new byte[(int) len];
            f.readFully(buf);
            SRecord sr = verifySRecords(buf);
            StringBuilder d = new StringBuilder();
            d.append("S0=").append(sr.s0).append(" S3=").append(sr.s3)
                    .append(" S7=").append(sr.s7).append(" 共 ").append(sr.total);
            if (sr.bad > 0) {
                d.append("；").append(sr.bad).append(" 条校验和/长度不符");
            }
            if (sr.dataBytes > 0) {
                d.append("；覆盖 ").append(sr.dataBytes).append(" 字节数据");
            }
            c6.detail = d.toString();
            c6.ok = sr.bad == 0 && sr.s0 == 1 && sr.s7 == 1 && sr.s3 > 0;
            for (String nt : sr.notes) {
                r.notes.add("GD32：" + nt);
            }
            if (!c6.ok) {
                r.notes.add("★ GD32_APP S-record 结构异常 ⇒ 仪表 MCU 编程会拿到垃圾数据");
            }
        } catch (Exception e) {
            c6.ok = false;
            c6.detail = "读取失败：" + e;
        }
    }

    /** 在分区表里按名字找槽位偏移（不是镜像偏移）。找不到返回 -1。 */
    private static long findPartitionOffset(java.io.RandomAccessFile f, String name)
            throws Exception {
        for (int i = 0; ; i++) {
            int base = TABLE_START + i * ENTRY_SIZE;
            byte[] ent = new byte[ENTRY_SIZE];
            try {
                f.seek(base);
                f.readFully(ent);
            } catch (Exception e) {
                return -1;
            }
            int z = 0;
            while (z < NAME_LEN && ent[z] != 0) {
                z++;
            }
            if (z == 0) {
                return -1;
            }
            String n = new String(ent, 0, z, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (name.equals(n)) {
                return le32(ent, ENTRY_OFF_OFF);
            }
        }
    }

    private static long findPartitionLen(java.io.RandomAccessFile f, String name) throws Exception {
        for (int i = 0; ; i++) {
            int base = TABLE_START + i * ENTRY_SIZE;
            byte[] ent = new byte[ENTRY_SIZE];
            try {
                f.seek(base);
                f.readFully(ent);
            } catch (Exception e) {
                return 0;
            }
            int z = 0;
            while (z < NAME_LEN && ent[z] != 0) {
                z++;
            }
            if (z == 0) {
                return 0;
            }
            String n = new String(ent, 0, z, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (name.equals(n)) {
                return le32(ent, ENTRY_LEN_OFF);
            }
        }
    }

    /** S-record 解析的量化结果。 */
    private static final class SRecord {
        int s0, s3, s7;
        int total;
        int bad;            // 长度或一补校验和不符
        /** S3 记录的<b>数据字节</b>总数（已扣掉 count 字段、4 字节地址与校验和）。 */
        long dataBytes;
        /** 附注（进最终 notes）。 */
        final List<String> notes = new ArrayList<>();

        void addNote(String s) {
            if (notes.size() < 4) {
                notes.add(s);
            }
        }
    }

    /**
     * 逐条校验 S-record 的一补（ones-complement）校验和。
     *
     * <p>★ 实测格式（{@code GD32_APP} 原厂分区，19606 行逐条核对得出；<b>不要按教科书改</b>）：</p>
     * <pre>
     *   S3150801000000000120DD120108818E0408D58E04083E     ← 46 字符
     *    ^ ^^                                            ^
     *    | |                                             └ 校验和
     *    | └ count = 0x15 = 21 ＝ <b>count 字段之后</b>的字节数（本行共 22 字节）
     *    └ type（1/3/7），<b>不计入</b> count
     * </pre>
     * <ul>
     *   <li><b>行字符数必须等于 {@code 4 + count*2}</b>（'S' + type + count两位 + count 个字节）。
     *       对不上就是截断/串行损坏，直接判坏。</li>
     *   <li><b>校验和：从 count 字段起的全部字节（含 count 自身、不含 type）之和
     *       的低 8 位 = 0xFF</b>。实测 19606 行<b>全部</b>满足这一条；
     *       「含 type」或「不含 count」两种口径都是 0/19606 通过，别搞反。</li>
     *   <li>实测构成：1 条 S0 头 + 19604 条 S3 数据 + 1 条 S7 结束，共 19606 行，行尾 CRLF，
     *       S7 之后还有 9 字节 NUL 填充（分区尾部对齐，正常）。</li>
     * </ul>
     */
    private static SRecord verifySRecords(byte[] data) {
        SRecord r = new SRecord();
        int i = 0;
        int n = data.length;
        while (i < n) {
            // 跳过行尾与空白（CRLF / LF / CR）
            while (i < n && (data[i] == '\r' || data[i] == '\n' || data[i] == ' '
                    || data[i] == '\t')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            if (data[i] == 0) {
                // ★ 实测：S7 结束行之后还有 9 字节 NUL 填充（对齐到扇区）。
                //   那是正常的分区尾部对齐，<b>不能</b>当成损坏记录，否则整包永远判不过。
                //   只有「NUL 之后又出现非 NUL 内容」才算异常。
                int j = i;
                while (j < n && data[j] == 0) {
                    j++;
                }
                if (j >= n) {
                    break;      // 全是 NUL ⇒ 正常收尾
                }
                r.total++;
                r.bad++;
                r.addNote("S7 结束行之后出现非 NUL 数据（偏移 " + i + "）");
                i = j;
                continue;
            }
            if (data[i] != 'S') {
                r.total++;
                r.bad++;
                while (i < n && data[i] != '\n') {
                    i++;
                }
                continue;
            }
            int lineEnd = i;
            while (lineEnd < n && data[lineEnd] != '\n' && data[lineEnd] != '\r') {
                lineEnd++;
            }
            int type = (i + 1 < n) ? hexVal(data[i + 1]) : -1;
            // ⚠ count 是两位十六进制：S011… 是 0x11=17，不是 1
            int count = (i + 3 < n) ? ((hexVal(data[i + 2]) << 4) | hexVal(data[i + 3])) : -1;
            // 行字符数 = 'S'(1) + type(2) + count两位(2) + count 个字节(2*count)
            if (type < 0 || count < 1 || (lineEnd - i) != 4 + count * 2) {
                r.total++;
                r.bad++;
                i = lineEnd;
                continue;
            }
            // 累加「count 字段起、到校验和自身」的全部字节（含 count、不含 type），低 8 位应为 0xFF
            int sum = 0;
            boolean parseOk = true;
            for (int k = 0; k < count + 1; k++) {   // count 的两个字节 + count 个记录字节
                int pos = i + 2 + k * 2;
                if (pos + 1 >= n) {
                    parseOk = false;
                    break;
                }
                int hi = hexVal(data[pos]);
                int lo = hexVal(data[pos + 1]);
                if (hi < 0 || lo < 0) {
                    parseOk = false;
                    break;
                }
                sum += (hi << 4) | lo;
            }
            if (!parseOk || (sum & 0xFF) != 0xFF) {
                r.bad++;
            }
            r.total++;
            if (type == 0) {
                r.s0++;
            } else if (type == 3) {
                r.s3++;
                // count 覆盖：count字段(2) + 地址(4) + 数据 + 校验和(1) ⇒ 数据 = count - 5
                if (count >= 5) {
                    r.dataBytes += (count - 5);
                }
            } else if (type == 7) {
                r.s7++;
            }
            i = lineEnd;
        }
        return r;
    }

    /** 十六进制字符 → 值；非法返回 -1。 */
    private static int hexVal(byte c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    // ============================================================ 分区表

    /** 分区表校验的量化结果。 */
    private static final class TableResult {
        int total;   // 实际识别到的项数
        int bad;     // MD5 不符的项数
        int oob;     // 区间越界的项数
    }

    private static TableResult checkPartitionTable(java.io.RandomAccessFile f, long packSize)
            throws Exception {
        TableResult r = new TableResult();
        for (int i = 0; ; i++) {
            int base = TABLE_START + i * ENTRY_SIZE;
            if (base + ENTRY_SIZE > packSize) {
                break;
            }
            byte[] ent = new byte[ENTRY_SIZE];
            f.seek(base);
            f.readFully(ent);
            int z = 0;
            while (z < NAME_LEN && ent[z] != 0) {
                z++;
            }
            if (z == 0) {
                break;      // 空名 = 表尾
            }
            String name = new String(ent, 0, z, java.nio.charset.StandardCharsets.ISO_8859_1);
            String stored = ascii(ent, ENTRY_MD5_OFF, 32);
            long off = le32(ent, ENTRY_OFF_OFF);
            long len = le32(ent, ENTRY_LEN_OFF);
            r.total++;
            if (len == 0) {
                // 空分区用空字节串的 MD5
                if (!EMPTY_MD5.equals(stored)) {
                    r.bad++;
                }
                continue;
            }
            long s = off + IMAGE_SKIP;
            if (off < 0 || len < 0 || s + len > packSize) {
                r.oob++;        // 越界：既不能校验也不该放过
                continue;
            }
            if (!md5OfRange(f, s, len).equals(stored)) {
                r.bad++;
            }
        }
        return r;
    }

    // ============================================================ 脚本内 MD5

    /** 脚本内 MD5 校验的量化结果。 */
    private static final class ScriptResult {
        int total;      // 实际配对到的校验点数
        int bad;        // MD5 不符
        int oob;        // 区间越界
        int fatloads;   // 脚本里 fatload 的数量（用于发现配对缺口）
    }

    /**
     * 校验 main 脚本里的硬编码 MD5。
     *
     * <p>脚本结构是「先连续 fatload+write 整个分区 → 再连续 read+md5sum 校验」，
     * 所以「第 i 个校验点 ↔ 第 i 个写入块」<b>必须按出现顺序配对</b>。
     * （曾经的坑：按「取之前最近的 fatload」配对，永远配到末块。）</p>
     *
     * <p>★ 2026-10-17：返回<b>实际识别数</b>与越界计数，交给调用方和
     * {@link #EXPECT_MD5_POINTS} 比对。docs/54 §3 要求核对识别数量，
     * 否则「只认出 12 项且全对」会被误判成通过。</p>
     */
    private static ScriptResult checkScriptMd5s(java.io.RandomAccessFile f, int doff, int size)
            throws Exception {
        ScriptResult r = new ScriptResult();
        byte[] script = new byte[size];
        f.seek(doff);
        f.readFully(script);
        String s = new String(script, java.nio.charset.StandardCharsets.ISO_8859_1);

        java.util.regex.Matcher mo = java.util.regex.Pattern
                .compile("isp_image_header_offset \\+ (0x[0-9A-Fa-f]+)").matcher(s);
        List<int[]> writes = new ArrayList<>();     // [脚本内位置, off]
        while (mo.find()) {
            writes.add(new int[]{mo.start(), (int) Long.parseLong(mo.group(1).substring(2), 16)});
        }
        java.util.regex.Matcher ms = java.util.regex.Pattern
                .compile("fatload \\$isp_if \\$isp_dev \\$isp_ram_addr "
                        + "\\$isp_update_file_name (0x[0-9A-Fa-f]+)").matcher(s);
        List<int[]> sizes = new ArrayList<>();      // [脚本内位置, size]
        while (ms.find()) {
            sizes.add(new int[]{ms.start(), (int) Long.parseLong(ms.group(1).substring(2), 16)});
        }
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("if test \"\\$md5sum_value\" = ([0-9a-f]{32})").matcher(s);
        List<String> md5vals = new ArrayList<>();
        while (mm.find()) {
            md5vals.add(mm.group(1));
        }
        r.fatloads = sizes.size();
        if (md5vals.isEmpty() || sizes.isEmpty()) {
            return r;   // total=0 ⇒ 与期望数量不符 ⇒ 调用方判负
        }
        // 按出现顺序把「第 i 个写入块」与「第 i 个校验点」配对
        int total = Math.min(md5vals.size(), sizes.size());
        r.total = total;
        for (int i = 0; i < total; i++) {
            int of = i < writes.size() ? writes.get(i)[1] : sizes.get(i)[1];
            int sz = sizes.get(i)[1];
            long start = IMAGE_SKIP + of;
            if (start < 0 || start + sz > f.length()) {
                r.oob++;
                continue;
            }
            if (!md5OfRange(f, start, sz).equals(md5vals.get(i))) {
                r.bad++;
            }
        }
        return r;
    }

    // ============================================================ uImage 定位

    /** 找 main 脚本 uImage：返回 {@code [headerOff, dataOff, size]}，找不到返回 null。 */
    private static int[] findMainScript(java.io.RandomAccessFile f, long packSize)
            throws Exception {
        byte[] marker = new byte[] {0x27, 0x05, 0x19, 0x56};
        int window = 1 << 20;
        long pos = 0;
        while (pos < packSize) {
            f.seek(pos);
            int want = (int) Math.min(window, packSize - pos);
            if (want <= 0) {
                break;
            }
            byte[] buf = new byte[want];
            f.readFully(buf);
            for (int i = 0; i + 64 <= want; i++) {
                if (buf[i] != marker[0] || buf[i + 1] != marker[1]
                        || buf[i + 2] != marker[2] || buf[i + 3] != marker[3]) {
                    continue;
                }
                String name = ascii(buf, i + UIMAGE_NAME_OFF, UIMAGE_NAME_LEN).trim();
                if (!SCRIPT_NAME.equals(name)) {
                    continue;
                }
                long size = be32(buf, i + UIMAGE_SIZE_OFF);
                long dataOff = pos + i + 64;
                if (size <= 0 || dataOff + size > packSize) {
                    continue;
                }
                return new int[]{(int) (pos + i), (int) dataOff, (int) size};
            }
            pos += Math.max(1, want - 64);
        }
        return null;
    }

    // ============================================================ 原语

    /** 区间 MD5（分段读，不把 128MB 塞进内存）。 */
    private static String md5OfRange(java.io.RandomAccessFile f, long off, long len)
            throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        f.seek(off);
        byte[] buf = new byte[1 << 20];
        long left = len;
        while (left > 0) {
            int n = (int) Math.min(buf.length, left);
            f.readFully(buf, 0, n);
            md.update(buf, 0, n);
            left -= n;
        }
        return hex(md.digest());
    }

    /** 区间 CRC32（uImage dataCRC 用）。 */
    private static long crc32OfRange(java.io.RandomAccessFile f, long off, long len)
            throws Exception {
        CRC32 crc = new CRC32();
        f.seek(off);
        byte[] buf = new byte[1 << 20];
        long left = len;
        while (left > 0) {
            int n = (int) Math.min(buf.length, left);
            f.readFully(buf, 0, n);
            crc.update(buf, 0, n);
            left -= n;
        }
        return crc.getValue();
    }

    private static long crc32(byte[] b, int off, int len) {
        CRC32 crc = new CRC32();
        crc.update(b, off, len);
        return crc.getValue();
    }

    private static long be32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** 分区表字段是<b>小端</b>（与 uImage header 的大端不同，别搞混）。 */
    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    /** SquashFS superblock 里的 16 位字段也是小端。 */
    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static String ascii(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 把 zip 内层包的 MD5 单独算出来（不做六项检查，用于快速展示）。 */
    public static String quickInnerMd5(File zip) {
        try {
            ZipFile zf = new ZipFile(zip);
            try {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!e.isDirectory()
                            && e.getName().toUpperCase().endsWith("GEMINI_PACK.BIN")) {
                        InputStream in = zf.getInputStream(e);
                        try {
                            java.security.MessageDigest md =
                                    java.security.MessageDigest.getInstance("MD5");
                            byte[] buf = new byte[1 << 20];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                md.update(buf, 0, n);
                            }
                            return hex(md.digest());
                        } finally {
                            closeQuietly(in);
                        }
                    }
                }
            } finally {
                closeQuietly(zf);
            }
        } catch (Exception e) {
            return "md5fail:" + e;
        }
        return "no-pack";
    }
}
