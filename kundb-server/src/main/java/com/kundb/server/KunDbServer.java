package com.kundb.server;

import com.kundb.common.protocol.FrameConstants;
import com.kundb.server.engine.ExecutionEngine;
import com.kundb.server.engine.SimpleInMemoryEngine;
import com.kundb.server.mysql.MysqlPacketDecoder;
import com.kundb.server.mysql.MysqlServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * KUN-DB 远程服务端入口（Netty）。监听端口（默认 {@link FrameConstants#DEFAULT_PORT}=8020，可用 --port 覆盖；
 * 每个连接经 {@link MysqlPacketDecoder} 解帧后交给 {@link MysqlServerHandler}（MySQL 线协议）。
 * DataGrip / Navicat / 自研 Qt 客户端（QMYSQL 驱动）均可连。
 * <p>数据默认落在工作目录的 {@code kundb-data/}（可用 --datadir 指定），重启不丢。</p>
 */
public final class KunDbServer {

    /** 主程序 exe 名（唯一启动器）：后台服务就是它的一个【隐藏】实例，进程名与用户双击的完全一致。 */
    private static final String APP_EXE_NAME = "KUN-DB.exe";

    /** 隐藏启动辅助脚本：用 WScript 以“隐藏窗口”方式拉起 KUN-DB.exe --bg（纯 ASCII，避免 ANSI 编码坑）。 */
    private static final String BG_HELPER_VBS = "bg-start.vbs";

    /** 后台服务进程的句柄：拉起它的进程 + 日志文件 + PID 文件（本窗口据此实时展示启动日志）。 */
    private static final class BgProc {
        final Process process;
        final File log;
        BgProc(Process process, File log) {
            this.process = process;
            this.log = log;
        }

        /**
         * 真正跑服务的进程 PID：隐藏拉起时 wscript 会立刻退出、服务进程变成孤儿，
         * 拿不到后代 PID，所以改由【服务进程自己】把 PID 写进 kundb.pid，这里读文件。
         */
        long pid() {
            try {
                Path p = Path.of(dataBaseDir(), "kundb.pid");
                if (Files.isRegularFile(p)) {
                    return Long.parseLong(Files.readString(p, StandardCharsets.UTF_8).trim());
                }
            } catch (Exception ignore) {
                // 读不到就回退到直接子进程的 PID
            }
            return process != null ? process.pid() : -1L;
        }
    }

    /** 后台模式标记：子进程以 --bg 启动，真正运行服务，不再二次拉起。 */
    public static void main(String[] args) throws Exception {
        // 从参数里分离出 --bg（后台模式）
        boolean background = false;
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if ("--bg".equals(a)) {
                background = true;
            } else {
                rest.add(a);
            }
        }
        String[] serverArgs = rest.toArray(new String[0]);

        // 交互式控制台（双击 exe / 开始菜单“运行 KUN-DB”复选框）下：拉起一个【无控制台】的
        // 后台 JVM 真正跑服务，原黑框框倒计时 10 秒提示“转入后台运行”后退出，避免一直留着黑框。
        // 不分离的情况：① 已是后台模式(--bg)；② 服务模式(WinSW 注入 KUNDB_SERVICE=1)；③ 非交互
        // （无控制台，如被其他程序调用）。服务模式必须保持前台，否则 WinSW 会判定服务已停止。
        if (!background && isInteractiveConsole() && !isService()) {
            BgProc bg = spawnBackgroundServer(serverArgs);
            if (bg != null) {
                System.out.println("[KUN-DB] 正在启动数据库服务，以下是服务端启动日志：");
                System.out.println("---------------------------------------------------------------");
                boolean ok = followStartupLog(bg);
                System.out.println("---------------------------------------------------------------");
                if (!ok) {
                    System.out.println();
                    System.out.println("[KUN-DB] 启动未成功，请查看上面的错误信息。");
                    if (bg.log != null) {
                        System.out.println("[KUN-DB] 完整日志：" + bg.log.getAbsolutePath());
                    }
                    System.out.println("[KUN-DB] 按回车键关闭窗口 ...");
                    try {
                        System.in.read();
                    } catch (IOException ignore) {
                        // 无标准输入则直接退出
                    }
                    System.exit(1);
                    return;
                }
                countdownThenExit(bg);
                return;
            }
            // 拉起失败则退化为前台运行，保证软件仍可正常使用
        }

        // 后台模式：没有可见窗口，把 System.out/err 接到日志文件上——
        // ① 父窗口要增量读这个文件来实时展示启动日志；② 隐藏 console 写不进去也不会丢日志。
        if (background) {
            redirectSysoutToLog();
        }
        runServerForeground(serverArgs, background);
    }

    /** 把 System.out / System.err 重定向到数据基目录的 logs/kundb-bg.log（后台模式专用）。 */
    private static void redirectSysoutToLog() {
        try {
            Path logDir = Path.of(dataBaseDir(), "logs");
            Files.createDirectories(logDir);
            File log = logDir.resolve("kundb-bg.log").toFile();
            java.io.PrintStream ps = new java.io.PrintStream(
                    new java.io.FileOutputStream(log, true), true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);
        } catch (Exception e) {
            // 重定向失败也不影响服务运行，只是父窗口读不到日志（会退化成按端口文件判断）
        }
    }

    /** 真正运行服务端（解析参数 → 初始化引擎 → 绑定端口 → 阻塞直到连接关闭）。 */
    private static void runServerForeground(String[] args, boolean background) throws Exception {
        int port = FrameConstants.DEFAULT_PORT;
        String datadir = null;
        String configPath = null; // 为 null 时按候选顺序自动查找
        boolean portFromCli = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") || args[i].equals("-p")) {
                port = Integer.parseInt(args[++i]);
                portFromCli = true;
            } else if (args[i].equals("--datadir") || args[i].equals("-d")) {
                datadir = args[++i];
            } else if (args[i].equals("--config") || args[i].equals("-c")) {
                configPath = args[++i];
            }
        }
        // 应用根目录（jpackage: 启动器同目录；便携 jar: 所在目录的上级）
        String appHome = detectAppHome();
        // 配置文件（若存在）提供默认值；命令行参数优先级更高
        Map<String, String> cfg = new HashMap<>();
        loadConfig(findConfig(configPath, appHome).toString(), cfg);
        if (!portFromCli && cfg.containsKey("port")) {
            try {
                port = Integer.parseInt(cfg.get("port").trim());
            } catch (NumberFormatException ignore) {
                // 保留默认端口
            }
        }
        if (datadir == null && cfg.containsKey("datadir")) {
            datadir = cfg.get("datadir").trim();
        }
        // 数据目录解析：相对路径以应用根目录为基准；若装在 Program Files 则重定向到 ProgramData（避免 UAC 虚拟化）
        String resolvedData = resolveDatadir(datadir, appHome);
        System.setProperty("kundb.datadir", resolvedData);
        ExecutionEngine engine = new SimpleInMemoryEngine();
        engine.init();

        BlessingBanner.print();
        ChannelFuture future;
        try {
            future = start(port, engine);
        } catch (Exception e) {
            // 双击 KUN-DB.exe（控制台窗口）启动失败时，给出可见的中文原因，而不是静默退出
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            System.err.println();
            System.err.println("[KUN-DB] 启动失败：" + cause);
            if (cause instanceof java.net.BindException) {
                System.err.println("[KUN-DB] 端口 " + port + " 已被占用。常见原因：");
                System.err.println("  1) KUN-DB 已经在后台运行（去任务管理器结束旧的 KUN-DB / java 进程）；");
                System.err.println("  2) 端口被其他程序占用（可修改 conf/kundb.ini 里的 port 换端口）。");
            }
            System.err.println("[KUN-DB] 按回车键关闭窗口 ...");
            try {
                if (System.console() != null) {
                    System.in.read(); // 双击运行时暂停，让用户看清原因；服务方式无控制台则直接退出
                }
            } catch (IOException ignore) {
                // 无标准输入（如 Windows 服务方式），直接退出
            }
            System.exit(1);
            return;
        }
        System.out.println("[KUN-DB] 服务端已启动（MySQL 协议），监听端口 " + port
                + "，数据目录 " + resolvedData
                + (background
                    ? "（后台运行：停止服务请运行 stop-server.bat）"
                    : "（关闭本窗口或按 Ctrl+C 退出）"));
        writePortFile(port);
        if (background) {
            // 记下自己的 PID：父窗口要显示它，stop-server.bat 要按它精确结束服务
            writePidFile(ProcessHandle.current().pid());
        }
        future.channel().closeFuture().sync();
    }

    /** 拉起一个【无控制台】的后台进程真正跑服务；返回句柄（含日志文件），失败返回 null。 */
    private static BgProc spawnBackgroundServer(String[] serverArgs) {
        try {
            String appHome = detectAppHome();
            // 后台进程的日志放【数据基目录】：① 父窗口要读它来实时展示启动日志；
            // ② 红线（真机踩坑）：不能放安装目录（Program Files 无写权限，CreateProcess 重定向
            //    文件创建失败会直接导致后台拉起失败回退前台），ProgramData 才可写。
            File log = null;
            try {
                Path logDir = Path.of(dataBaseDir(), "logs");
                Files.createDirectories(logDir);
                log = logDir.resolve("kundb-bg.log").toFile();
                Files.deleteIfExists(log.toPath()); // 清掉上次的日志，本窗口从头展示
            } catch (IOException ignore) {
                log = null;
            }

            // 清掉上次遗留的 PID / 端口文件，避免把上一次的进程当成本次的服务进程
            try {
                Path base = Path.of(dataBaseDir());
                Files.deleteIfExists(base.resolve("kundb.pid"));
                Files.deleteIfExists(base.resolve("kundb.port"));
            } catch (IOException ignore) {
                // 清不掉也不影响，后面以日志为准
            }

            // 当前 exe 路径：jpackage 下由 jpackage.app-path 给出（辅助脚本按自身位置拼 exe，这里只用于回退分支）
            String appPath = System.getProperty("jpackage.app-path");

            List<String> cmd = new ArrayList<>();
            File helper = new File(appHome, BG_HELPER_VBS);
            Path wscript = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                    "System32", "wscript.exe");
            if (helper.isFile() && Files.isRegularFile(wscript)) {
                // 首选：用 WScript 以【隐藏窗口】方式拉起【同一个】KUN-DB.exe --bg。
                // 这样任务管理器里进程名 = 用户双击的那个 exe，不会再出现第二个启动器。
                // （红线：不能直接 ProcessBuilder 起控制台 exe —— 子控制台进程会继承本窗口的
                //   console，父窗口关掉后 console 仍被子进程持有，黑框框就关不掉。）
                cmd.add(wscript.toString());
                cmd.add("//nologo");
                cmd.add(helper.getAbsolutePath());
                cmd.add("--bg");
            } else {
                // 回退：直接用 runtime 里的 javaw.exe（无控制台，进程名会显示 javaw.exe）
                String javaHome = System.getProperty("java.home");
                Path javaw = Path.of(javaHome, "bin", "javaw.exe");
                if (!Files.isRegularFile(javaw)) {
                    javaw = Path.of(javaHome, "bin", "java.exe");
                }
                cmd.add(javaw.toString());
                if (appPath != null && !appPath.isEmpty()) {
                    cmd.add("-Djpackage.app-path=" + appPath);
                }
                cmd.add("-Dfile.encoding=UTF-8");
                cmd.add("-cp");
                cmd.add(System.getProperty("java.class.path"));
                cmd.add("com.kundb.server.KunDbServer");
                cmd.add("--bg");
            }
            for (String a : serverArgs) {
                cmd.add(a);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(appHome));
            // 隐藏拉起时服务进程会把自己的日志写进 log（见 redirectSysoutToLog），
            // 这里只需丢弃 wscript/javaw 启动器本身的输出，避免继承本窗口 console。
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            if (!cmd.contains(BG_HELPER_VBS)) {
                // 回退分支：javaw 本身就是服务进程，可以直接记 PID
                writePidFile(proc.pid());
            }
            return new BgProc(proc, log);
        } catch (Exception e) {
            System.err.println("[KUN-DB] 后台启动失败，改在前台运行：" + e.getMessage());
            return null;
        }
    }

    /**
     * 实时把后台进程的启动日志打到本控制台，直到出现“启动成功”标记才返回 true；
     * 启动失败（日志出现失败字样 / 进程退出 / 超时）返回 false。
     * 子进程的 System.out 在 -Dfile.encoding=UTF-8 下按 UTF-8 写文件，故此处按 UTF-8 解码。
     */
    private static boolean followStartupLog(BgProc bg) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 40000;
        final StringBuilder seen = new StringBuilder();
        long offset = 0;
        long goneSince = 0;
        boolean launcherGone = false;
        while (System.currentTimeMillis() < deadline) {
            if (bg.log != null && bg.log.isFile()) {
                long len = bg.log.length();
                if (len > offset) {
                    int n = (int) Math.min(len - offset, 32768);
                    byte[] buf = new byte[n];
                    try (RandomAccessFile raf = new RandomAccessFile(bg.log, "r")) {
                        raf.seek(offset);
                        raf.readFully(buf);
                    } catch (IOException ignore) {
                        // 正在被写入，下一轮再读
                    }
                    int use = completePrefixLen(buf); // 不要把多字节汉字从中间切开
                    if (use > 0) {
                        String chunk = new String(buf, 0, use, StandardCharsets.UTF_8);
                        seen.append(chunk);
                        System.out.print(chunk);
                        System.out.flush();
                        offset += use;
                    }
                }
                String text = seen.toString();
                if (text.contains("服务端已启动")) {
                    return true;
                }
                if (text.contains("启动失败") || text.contains("Exception in thread")) {
                    return false;
                }
            } else if (portFileExists()) {
                return true; // 拿不到日志文件时，以端口文件出现作为启动成功依据
            }
            // 隐藏拉起时 wscript 会立刻退出（服务进程变孤儿），所以【不能用进程存活判断成功】：
            // 只要服务自己写出了 PID + 端口文件，就说明它已经起来了。
            boolean alive = bg.process != null && bg.process.isAlive();
            if (!alive) {
                if (!launcherGone) {
                    launcherGone = true;
                    goneSince = System.currentTimeMillis();
                }
                if (pidFileExists() && portFileExists()) {
                    return true;
                }
                if (System.currentTimeMillis() - goneSince > 5000) {
                    Thread.sleep(400); // 再等一下，确保最后的日志落盘
                    return false;      // 启动器已退出且迟迟没有 PID/端口文件 —— 启动失败
                }
            }
            Thread.sleep(150);
        }
        return false; // 超时
    }

    /** PID 文件是否存在（后台服务进程启动成功后自己写）。 */
    private static boolean pidFileExists() {
        return Files.isRegularFile(Path.of(dataBaseDir(), "kundb.pid"));
    }

    /** 返回 buf 中“完整字符”的字节长度：末尾若是不完整的 UTF-8 序列则截掉，留到下一轮再拼。 */
    private static int completePrefixLen(byte[] buf) {
        int n = buf.length;
        for (int i = 1; i <= Math.min(4, n); i++) {
            int v = buf[n - i] & 0xFF;
            if (v < 0x80) {
                return n;                       // 末尾是 ASCII，说明前面都完整
            }
            if (v >= 0xC0) {                    // 遇到起始字节：判断其后跟的字节数够不够
                int need = (v >= 0xF0) ? 4 : (v >= 0xE0) ? 3 : 2;
                return (i >= need) ? n : n - i;
            }
            // 是续字节(0x80~0xBF)，继续往前找起始字节
        }
        return n;
    }

    /** 控制台倒计时 10 秒后退出（服务已在后台进程运行）。 */
    private static void countdownThenExit(BgProc bg) throws InterruptedException {
        System.out.println();
        System.out.println("[KUN-DB] 启动成功！服务已在后台运行。");
        System.out.println("         进程 PID = " + bg.pid()
                + "，进程名就是 " + APP_EXE_NAME + "（任务管理器里显示为「KUN-DB 数据库服务」）。");
        System.out.println("         监听端口 " + detectPort()
                + "，可随时用 MySQL 客户端或开始菜单的「KUN-DB 命令行客户端」连接（端口自动跟随）。");
        System.out.println("         需要停止服务：运行安装目录里的 stop-server.bat，或在任务管理器结束该进程。");
        System.out.println();
        for (int i = 10; i >= 1; i--) {
            System.out.print("\r[KUN-DB] 程序将转入后台运行，窗口将在 " + i + " 秒后关闭 ...");
            System.out.flush();
            Thread.sleep(1000);
        }
        System.out.println("\r[KUN-DB] 已转入后台运行，服务继续在后台提供数据库服务。      ");
        Thread.sleep(400);
        System.exit(0);
    }

    private static boolean isInteractiveConsole() {
        return System.console() != null;
    }

    private static boolean isService() {
        return "1".equals(System.getenv("KUNDB_SERVICE"));
    }

    /** 探测应用根目录：jpackage 用 jpackage.app-path（启动器同级）；否则取主 jar 所在目录。 */
    private static String detectAppHome() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isEmpty()) {
            return Path.of(appPath).getParent().toString();
        }
        try {
            Path jar = Paths.get(KunDbServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            // 便携布局 jar 在 lib/ 或 app/，取上级作为应用根
            if (dir != null && (dir.endsWith("lib") || dir.endsWith("app"))) {
                dir = dir.getParent();
            }
            if (dir != null) {
                return dir.toString();
            }
        } catch (Exception ignore) {
            // 回退到工作目录
        }
        return System.getProperty("user.dir");
    }

    /** 在多个候选位置中查找配置文件；显式路径优先，否则按 appHome/conf、appHome/../conf、CWD/conf 顺序。 */
    private static Path findConfig(String explicit, String appHome) {
        List<Path> candidates = new ArrayList<>();
        if (explicit != null) {
            candidates.add(Path.of(explicit));
        }
        candidates.add(Path.of(appHome, "conf", "kundb.ini"));
        candidates.add(Path.of(appHome, "..", "conf", "kundb.ini"));
        candidates.add(Path.of(System.getProperty("user.dir"), "conf", "kundb.ini"));
        for (Path c : candidates) {
            try {
                if (Files.isRegularFile(c)) {
                    return c;
                }
            } catch (Exception ignore) {
                // 继续下一个候选
            }
        }
        return candidates.get(0); // 回退到首个候选（loadConfig 会因文件不存在而跳过）
    }

    /** 解析数据目录：绝对路径原样使用；相对路径以应用根为基准，装在 Program Files 时重定向到 %ProgramData%/KUN-DB。 */
    private static String resolveDatadir(String datadir, String appHome) {
        String name = (datadir == null || datadir.isEmpty()) ? "data" : datadir;
        Path dp = Path.of(name);
        if (dp.isAbsolute()) {
            return dp.toString();
        }
        return Path.of(dataBaseDir()).resolve(name).toString();
    }

    /**
     * 数据基目录：装在 Program Files 下时返回 %ProgramData%/KUN-DB（可写），
     * 否则返回应用根目录。后台日志、端口文件等运行期可写产物都放这里。
     */
    private static String dataBaseDir() {
        String appHome = detectAppHome();
        boolean installed = appHome.toLowerCase(Locale.ROOT).contains("program files");
        if (installed) {
            return Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"), "KUN-DB").toString();
        }
        return appHome;
    }

    /**
     * 把后台服务进程的 PID 落到数据基目录的 kundb.pid，便于 stop-server.bat 精确结束它。
     */
    private static void writePidFile(long pid) {
        try {
            Path base = Path.of(dataBaseDir());
            Files.createDirectories(base);
            Files.write(base.resolve("kundb.pid"),
                    String.valueOf(pid).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[KUN-DB] 写 PID 文件失败（不影响服务运行）：" + e.getMessage());
        }
    }

    /** 端口文件是否已生成（后台服务启动成功的标志之一）。 */
    private static boolean portFileExists() {
        return Files.isRegularFile(Path.of(dataBaseDir(), "kundb.port"));
    }

    /** 读取端口文件里的端口号；读不到时回退默认端口（仅用于提示文案）。 */
    private static String detectPort() {
        try {
            Path p = Path.of(dataBaseDir(), "kundb.port");
            if (Files.isRegularFile(p)) {
                String s = Files.readString(p, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (IOException ignore) {
            // 回退默认端口
        }
        return String.valueOf(FrameConstants.DEFAULT_PORT);
    }

    /**
     * 把实际监听端口落到数据基目录的 kundb.port（内容就是端口号）。
     * 命令行客户端 SqlShell 无参启动时读这个文件自动跟随端口（含 conf/kundb.ini 改过端口的场景）。
     */
    private static void writePortFile(int port) {
        try {
            Path base = Path.of(dataBaseDir());
            Files.createDirectories(base);
            Files.write(base.resolve("kundb.port"),
                    String.valueOf(port).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[KUN-DB] 写端口文件失败（不影响服务运行）：" + e.getMessage());
        }
    }

    /** 启动服务端并异步绑定端口；返回的 future 在绑定完成后 complete。 */
    public static ChannelFuture start(int port, ExecutionEngine engine) throws Exception {
        NioEventLoopGroup boss = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new IdleStateHandler(90, 0, 0, TimeUnit.SECONDS));
                        ch.pipeline().addLast(new MysqlPacketDecoder());
                        ch.pipeline().addLast(new MysqlServerHandler(engine));
                    }
                });
        return b.bind(port).sync();
    }

    /**
     * 读取简单的 key=value 配置文件（类 my.ini）。忽略空行与 # / ; 注释行，UTF-8 编码。
     * 仅用于补充默认值，不会覆盖已存在的 key。
     */
    private static void loadConfig(String path, Map<String, String> out) {
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String val = line.substring(eq + 1).trim();
                out.putIfAbsent(key, val);
            }
        } catch (IOException e) {
            System.err.println("[KUN-DB] 读取配置文件 " + path + " 失败：" + e.getMessage());
        }
    }
}
