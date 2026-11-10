package brblocks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Everything read from one Buildroot output folder: the packages and their
 * dependencies (from "make show-info"), the .config, the build times and the images.
 */
final class BrModel {

    /** One package as "make show-info" describes it. */
    record Pkg(String name, String type, String version, boolean virtual, String licenses,
               List<String> deps, List<String> rdeps, List<String> sources, int patches,
               String pkgDir, boolean installTarget, boolean installStaging, boolean installImages) {

        boolean isHost() {
            return type.equals("host");
        }

        /** What kind of block it is: decides the color and the block title. */
        String category() {
            if (type.equals("rootfs")) return "rootfs";
            if (name.equals("linux") || name.equals("linux-headers")) return "kernel";
            if (name.equals("uboot") || name.equals("am33x-cm3") || name.startsWith("arm-trusted-firmware")
                    || name.equals("barebox") || name.equals("grub2") || name.equals("optee-os")) return "bootloader";
            if (name.startsWith("toolchain")) return "toolchain";
            if (name.startsWith("skeleton") || name.startsWith("host-skeleton")) return "skeleton";
            if (isHost()) return "host";
            return "target";
        }
    }

    final Path out;              // the Buildroot output folder (has .config)
    final Path buildroot;        // the Buildroot source folder, or null
    final Map<String, Pkg> pkgs = new TreeMap<>();
    final Map<String, String> config = new LinkedHashMap<>();
    final Map<String, Map<String, Long>> steps = new HashMap<>();   // package -> step -> ms
    final List<Path> images = new ArrayList<>();
    final Map<String, String> evia = new LinkedHashMap<>();         // evia-linux.json, if there
    String brVersion = "";
    String source = "";          // where the package list came from
    String warning;              // set when show-info could not be used

    private BrModel(Path out, Path buildroot) {
        this.out = out;
        this.buildroot = buildroot;
    }

    // ================================================================ finding the folders

    /** The output folder for a folder the user picked: the output folder itself or the workspace above it. */
    static Path findOutput(Path dir) throws IOException {
        dir = dir.toAbsolutePath().normalize();
        for (Path p : List.of(dir, dir.resolve("output"))) {
            if (Files.isRegularFile(p.resolve(".config")) && Files.isDirectory(p.resolve("build"))) return p;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                if (Files.isRegularFile(p.resolve(".config")) && Files.isDirectory(p.resolve("build"))) return p;
            }
        }
        throw new IOException("No Buildroot output folder (with .config and build/) in " + dir);
    }

    private static Path findBuildroot(Path out) {
        try {
            String mk = Files.readString(out.resolve("Makefile"));
            Matcher m = Pattern.compile("-C\\s+\"?([^\"\\s]+)\"?").matcher(mk);
            if (m.find()) {
                Path p = Path.of(m.group(1));
                if (Files.isRegularFile(p.resolve("Makefile"))) return p;
            }
        } catch (IOException ignored) {
        }
        Path p = out.getParent() == null ? null : out.getParent().resolve("buildroot");
        return p != null && Files.isRegularFile(p.resolve("Makefile")) ? p : null;
    }

    // ================================================================ loading

    static BrModel load(Path picked, boolean useCache, Consumer<String> progress) throws IOException {
        Path out = findOutput(picked);
        BrModel m = new BrModel(out, findBuildroot(out));
        progress.accept("Reading .config");
        m.readConfig();
        m.readBrVersion();
        m.readEvia();
        progress.accept("Reading packages (make show-info)");
        m.readPackages(useCache, progress);
        progress.accept("Reading build times");
        m.readBuildTimes();
        m.readImages();
        return m;
    }

    private void readConfig() throws IOException {
        for (String line : Files.readAllLines(out.resolve(".config"))) {
            if (line.startsWith("#") || !line.contains("=")) continue;
            int eq = line.indexOf('=');
            String v = line.substring(eq + 1);
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            config.put(line.substring(0, eq), v);
        }
    }

    private void readBrVersion() {
        if (buildroot == null) return;
        try {
            Matcher m = Pattern.compile("(?m)^export BR2_VERSION\\s*:=\\s*(\\S+)").matcher(
                    Files.readString(buildroot.resolve("Makefile")));
            if (m.find()) brVersion = m.group(1);
        } catch (IOException ignored) {
        }
    }

    private void readEvia() {
        Path ws = out.getParent();
        if (ws == null) return;
        Path f = ws.resolve("evia-linux.json");
        if (!Files.isRegularFile(f)) return;
        try {
            Json.obj(Json.parse(Files.readString(f))).forEach((k, v) -> evia.put(k, Json.str(v)));
        } catch (Exception ignored) {
        }
    }

    private void readPackages(boolean useCache, Consumer<String> progress) throws IOException {
        Path cache = cacheFile();
        String json = null;
        if (useCache && cache != null && Files.isRegularFile(cache)) {
            json = Files.readString(cache);
            source = "make show-info (cached)";
        }
        if (json == null) {
            try {
                json = runShowInfo();
                source = "make show-info";
                if (cache != null) {
                    Files.createDirectories(cache.getParent());
                    Files.writeString(cache, json);
                }
            } catch (IOException e) {
                warning = "make show-info failed, so dependencies are missing: " + e.getMessage();
                source = "build/ folder";
                progress.accept(warning);
                readPackagesFromBuildDir();
                return;
            }
        }
        for (Map.Entry<String, Object> e : Json.obj(Json.parse(json)).entrySet()) {
            Map<String, Object> o = Json.obj(e.getValue());
            List<String> srcs = new ArrayList<>();
            for (Object d : Json.arr(o.get("downloads"))) srcs.add(Json.str(Json.obj(d).get("source")));
            pkgs.put(e.getKey(), new Pkg(e.getKey(), Json.str(o.get("type")), Json.str(o.get("version")),
                    Boolean.TRUE.equals(o.get("virtual")), Json.str(o.get("licenses")),
                    Json.strings(o.get("dependencies")), Json.strings(o.get("reverse_dependencies")),
                    srcs, Json.arr(o.get("patches")).size(), Json.str(o.get("package_dir")),
                    Boolean.TRUE.equals(o.get("install_target")), Boolean.TRUE.equals(o.get("install_staging")),
                    Boolean.TRUE.equals(o.get("install_images"))));
        }
    }

    /** The cached show-info answer; its name changes whenever .config changes. */
    private Path cacheFile() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(out.toString().getBytes(StandardCharsets.UTF_8));
            md.update(Files.readAllBytes(out.resolve(".config")));
            String h = HexFormat.of().formatHex(md.digest()).substring(0, 24);
            return Path.of(System.getProperty("user.home"), ".cache", "the-grid", h + ".json");
        } catch (Exception e) {
            return null;
        }
    }

    private String runShowInfo() throws IOException {
        List<String> cmd = new ArrayList<>(List.of("make", "-s", "--no-print-directory"));
        if (Files.isRegularFile(out.resolve("Makefile"))) {
            cmd.addAll(List.of("-C", out.toString(), "show-info"));
        } else if (buildroot != null) {
            cmd.addAll(List.of("-C", buildroot.toString(), "O=" + out, "show-info"));
        } else {
            throw new IOException("no Makefile in " + out);
        }
        Process p = new ProcessBuilder(cmd).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                .start();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread t = new Thread(() -> copy(p.getErrorStream(), err));
        t.start();
        String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(3, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("make took too long");
            }
            t.join();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw new IOException("interrupted");
        }
        int start = text.indexOf('{');
        if (p.exitValue() != 0 || start < 0) {
            String msg = err.toString(StandardCharsets.UTF_8).strip();
            throw new IOException(msg.isEmpty() ? "exit code " + p.exitValue() : lastLine(msg));
        }
        return text.substring(start);
    }

    private static void copy(InputStream in, ByteArrayOutputStream to) {
        try {
            in.transferTo(to);
        } catch (IOException ignored) {
        }
    }

    private static String lastLine(String s) {
        String[] l = s.split("\n");
        return l[l.length - 1];
    }

    /** Fallback without make: one package per build/ folder, no dependencies. */
    private void readPackagesFromBuildDir() throws IOException {
        Pattern ver = Pattern.compile("^(.+?)-(\\d[\\w.+~-]*|[0-9a-f]{40})$");
        try (Stream<Path> s = Files.list(out.resolve("build"))) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                String dn = d.getFileName().toString();
                if (dn.startsWith("buildroot-")) continue;
                Matcher m = ver.matcher(dn);
                String name = m.matches() ? m.group(1) : dn, v = m.matches() ? m.group(2) : "";
                pkgs.put(name, new Pkg(name, name.startsWith("host-") ? "host" : "target", v, false, "",
                        List.of(), List.of(), List.of(), 0, "", false, false, false));
            }
        }
    }

    private void readBuildTimes() {
        Path log = out.resolve("build").resolve("build-time.log");
        if (!Files.isRegularFile(log)) return;
        Map<String, Double> started = new HashMap<>();
        try {
            for (String line : Files.readAllLines(log)) {
                String[] f = line.split(":", 4);
                if (f.length < 4) continue;
                String step = f[2].trim(), pkg = f[3].trim(), key = pkg + "\u0000" + step;
                double ts = Double.parseDouble(f[0]);
                if (f[1].trim().equals("start")) {
                    started.put(key, ts);
                } else if (started.containsKey(key)) {
                    long ms = Math.round((ts - started.remove(key)) * 1000);
                    steps.computeIfAbsent(pkg, k -> new LinkedHashMap<>()).merge(step, ms, Long::sum);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
    }

    private void readImages() {
        Path dir = out.resolve("images");
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            images.addAll(s.sorted().toList());
        } catch (IOException ignored) {
        }
    }

    // ================================================================ questions

    long buildMs(String pkg) {
        Map<String, Long> m = steps.get(pkg);
        return m == null ? 0 : m.values().stream().mapToLong(Long::longValue).sum();
    }

    long totalBuildMs() {
        long t = 0;
        for (String p : steps.keySet()) t += buildMs(p);
        return t;
    }

    String cfg(String key) {
        return config.getOrDefault(key, "");
    }

    boolean on(String key) {
        return "y".equals(config.get(key));
    }

    /** The first package whose name starts with the prefix, or null. */
    Pkg firstWith(String prefix) {
        for (Pkg p : pkgs.values()) if (p.name().startsWith(prefix) && !p.virtual()) return p;
        for (Pkg p : pkgs.values()) if (p.name().startsWith(prefix)) return p;
        return null;
    }

    long count(String type) {
        return pkgs.values().stream().filter(p -> p.type().equals(type)).count();
    }

    /** The .mk file of a package, or null. */
    Path mkFile(Pkg p) {
        if (buildroot == null || p.pkgDir().isEmpty()) return null;
        String base = p.name().startsWith("host-") ? p.name().substring(5) : p.name();
        Path dir = buildroot.resolve(p.pkgDir());
        Path f = dir.resolve(base + ".mk");
        if (Files.isRegularFile(f)) return f;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(x -> x.toString().endsWith(".mk")).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static String duration(long ms) {
        if (ms <= 0) return "";
        long s = Math.round(ms / 1000.0);
        if (s < 1) return "<1s";
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + (s % 3600 / 60) + "m";
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    static String shortVersion(String v) {
        return v.matches("[0-9a-f]{40}") ? v.substring(0, 7) : v;
    }
}
