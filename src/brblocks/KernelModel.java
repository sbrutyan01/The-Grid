package brblocks;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Linux build actually compiled, folder by folder: object files, modules, the CONFIG_
 * option that turns each folder on (from its parent Makefile) and its Kconfig menu title.
 */
final class KernelModel {

    /** One source folder that has compiled objects somewhere below it. */
    static final class Dir {
        final String path;               // "" for the top, else "drivers/usb"
        final String name;
        final List<Dir> children = new ArrayList<>();
        int ownObjects, objects;         // .o here / here and below
        int ownModules, modules;         // module objects (from modules.order)
        final List<String> moduleNames = new ArrayList<>();
        final Set<String> symbols = new LinkedHashSet<>();   // CONFIG_ options that build it
        String prompt = "";              // Kconfig menu title

        Dir(String path) {
            this.path = path;
            this.name = path.isEmpty() ? "" : path.substring(path.lastIndexOf('/') + 1);
        }

        boolean hasBuiltIn;              // its built-in.a has members: some code goes into the kernel image

        /** "built-in", "modules" or "mixed". */
        String kind() {
            if (modules == 0) return "built-in";
            return hasBuiltIn ? "mixed" : "modules";
        }
    }

    private static final Set<String> SKIP = Set.of("scripts", "tools", "Documentation", "include", "samples",
            "rust", "LICENSES", ".tmp_versions");

    final Path dir;
    final String version;
    final Map<String, String> config = new HashMap<>();
    final Map<String, Dir> dirs = new HashMap<>();
    final Dir root;
    final List<String> dtbs = new ArrayList<>();
    long zImage, vmlinux;
    int yes, mod;

    private KernelModel(Path dir, String version) {
        this.dir = dir;
        this.version = version;
        this.root = new Dir("");
        dirs.put("", root);
    }

    /** The Linux build folder of a Buildroot output, or null when the kernel was not built. */
    static Path find(BrModel m) {
        BrModel.Pkg lx = m.pkgs.get("linux");
        Path build = m.out.resolve("build");
        if (lx != null && Files.isDirectory(build.resolve("linux-" + lx.version()))) return build.resolve("linux-" + lx.version());
        try (DirectoryStream<Path> s = Files.newDirectoryStream(build, "linux-[0-9]*")) {
            for (Path p : s) if (Files.isRegularFile(p.resolve(".config"))) return p;
        } catch (IOException ignored) {
        }
        return null;
    }

    static KernelModel load(Path dir, String version) throws IOException {
        KernelModel k = new KernelModel(dir, version);
        k.readConfig();
        k.scanObjects();
        k.readModules();
        k.total(k.root);
        for (Dir d : k.dirs.values()) {
            for (String lib : List.of("built-in.a", "lib.a")) {
                Path a = dir.resolve(d.path).resolve(lib);
                try {
                    if (Files.isRegularFile(a) && Files.size(a) > 8) d.hasBuiltIn = true;   // "!<thin>\n" alone is empty
                } catch (IOException ignored) {
                }
            }
        }
        k.builtInUp(k.root);
        k.readSymbolsAndPrompts();
        k.readOutputs();
        return k;
    }

    private void readConfig() throws IOException {
        for (String l : Files.readAllLines(dir.resolve(".config"))) {
            if (l.startsWith("#") || !l.startsWith("CONFIG_")) continue;
            int eq = l.indexOf('=');
            String v = l.substring(eq + 1);
            config.put(l.substring(0, eq), v);
            if (v.equals("y")) yes++;
            else if (v.equals("m")) mod++;
        }
    }

    private void scanObjects() throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                Path rel = dir.relativize(d);
                if (rel.getNameCount() == 1 && SKIP.contains(rel.toString())) return FileVisitResult.SKIP_SUBTREE;
                return d.getFileName().toString().startsWith(".") && !d.equals(dir)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                String n = f.getFileName().toString();
                Path parent = dir.relativize(f.getParent());
                if (n.endsWith(".o") && !n.endsWith(".mod.o") && parent.getNameCount() > 0
                        && !parent.toString().isEmpty()) {
                    dir(parent.toString()).ownObjects++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void readModules() {
        try {
            for (String l : Files.readAllLines(dir.resolve("modules.order"))) {
                l = l.strip();
                int s = l.lastIndexOf('/');
                if (s < 0) continue;
                Dir d = dir(l.substring(0, s));
                d.ownModules++;
                String n = l.substring(s + 1);
                d.moduleNames.add(n.replaceAll("\\.(o|ko)$", ""));
            }
        } catch (IOException ignored) {
        }
    }

    /** The folder object for a path, creating it and its parents. */
    private Dir dir(String path) {
        Dir d = dirs.get(path);
        if (d != null) return d;
        d = new Dir(path);
        dirs.put(path, d);
        int s = path.lastIndexOf('/');
        dir(s < 0 ? "" : path.substring(0, s)).children.add(d);
        return d;
    }

    /** A folder has built-in code when any folder below it has. */
    private boolean builtInUp(Dir d) {
        for (Dir c : d.children) if (builtInUp(c)) d.hasBuiltIn = true;
        return d.hasBuiltIn;
    }

    private void total(Dir d) {
        d.objects = d.ownObjects;
        d.modules = d.ownModules;
        for (Dir c : d.children) {
            total(c);
            d.objects += c.objects;
            d.modules += c.modules;
        }
        d.children.removeIf(c -> c.objects == 0);
        d.children.sort(Comparator.comparingInt((Dir c) -> -c.objects).thenComparing(c -> c.name));
    }

    private static final Pattern OBJ = Pattern.compile("^obj-(?:\\$\\((CONFIG_[A-Za-z0-9_]+)\\)|(y))\\s*[:+]?=\\s*(.*)$");

    private void readSymbolsAndPrompts() {
        for (Dir d : dirs.values()) {
            if (d.children.isEmpty()) continue;
            Path mk = dir.resolve(d.path).resolve("Makefile");
            Path kb = dir.resolve(d.path).resolve("Kbuild");
            for (Path f : List.of(mk, kb)) {
                if (!Files.isRegularFile(f)) continue;
                try {
                    String text = Files.readString(f).replace("\\\n", " ");
                    for (String line : text.split("\n")) {
                        Matcher m = OBJ.matcher(line.strip());
                        if (!m.matches()) continue;
                        String sym = m.group(1) != null ? m.group(1) : "always";
                        for (String item : m.group(3).split("\\s+")) {
                            if (!item.endsWith("/")) continue;
                            Dir c = dirs.get(d.path.isEmpty() ? item.substring(0, item.length() - 1)
                                    : d.path + "/" + item.substring(0, item.length() - 1));
                            if (c != null) c.symbols.add(sym);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }
        for (Dir d : dirs.values()) d.prompt = prompt(dir.resolve(d.path).resolve("Kconfig"));
    }

    private static String prompt(Path kconfig) {
        if (!Files.isRegularFile(kconfig)) return "";
        try {
            List<String> lines = Files.readAllLines(kconfig);
            for (int i = 0; i < Math.min(lines.size(), 200); i++) {
                String l = lines.get(i).strip();
                Matcher m = Pattern.compile("^menu \"(.+)\"").matcher(l);
                if (m.find()) return m.group(1);
                if (l.startsWith("menuconfig ") || l.startsWith("config ")) {
                    for (int j = i + 1; j < Math.min(lines.size(), i + 4); j++) {
                        Matcher p = Pattern.compile("^(?:bool|tristate|prompt)\\s+\"(.+)\"").matcher(lines.get(j).strip());
                        if (p.find()) return p.group(1);
                    }
                    if (l.startsWith("config ")) return "";
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private void readOutputs() {
        try {
            Path z = dir.resolve("arch/arm/boot/zImage");
            if (!Files.isRegularFile(z)) z = dir.resolve("arch/arm64/boot/Image");
            if (Files.isRegularFile(z)) zImage = Files.size(z);
            if (Files.isRegularFile(dir.resolve("vmlinux"))) vmlinux = Files.size(dir.resolve("vmlinux"));
        } catch (IOException ignored) {
        }
        Path dts = dir.resolve("arch");
        if (!Files.isDirectory(dts)) return;
        try (var s = Files.walk(dts)) {
            s.filter(p -> p.toString().endsWith(".dtb")).map(p -> p.getFileName().toString()).sorted().forEach(dtbs::add);
        } catch (IOException ignored) {
        }
    }

    /** "CONFIG_USB=y" style text for a folder's options; "always" when its Makefile builds it unconditionally. */
    String symbolText(Dir d, boolean full) {
        List<String> parts = new ArrayList<>();
        for (String s : d.symbols) {
            if (s.equals("always")) { parts.add("always"); continue; }
            String v = config.get(s);
            if (v == null) continue;    // not set: this symbol did not cause the build
            parts.add((full ? s : s.substring(7)) + "=" + v);
        }
        return String.join(full ? ", " : " ", parts);
    }
}
