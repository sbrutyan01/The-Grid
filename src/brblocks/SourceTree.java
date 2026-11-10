package brblocks;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A source tree as blocks inside blocks: the Linux kernel sources (marked with what the build
 * compiled) or the Buildroot sources (package/ follows Buildroot's own menus, and what this build
 * uses is outlined). Items load their children only when they are first shown.
 */
final class SourceTree {

    /** One block: a folder, a file, a menu or a package. */
    static final class Item {
        final String id, name;
        String title = "", label = "", meta = "", html = "";
        Color color = FOLDER;
        Path file;                       // a file that double-click opens
        boolean used, dashed;
        Supplier<List<Item>> loader;     // null: no sub-blocks
        Runnable details;                // fills label/meta/html when the block is first shown
        private List<Item> kids;

        Item(String id, String name) {
            this.id = id;
            this.name = name;
        }

        List<Item> children() {
            if (kids == null) kids = loader == null ? List.of() : loader.get();
            return kids;
        }

        boolean container() {
            return loader != null;
        }

        void ensure() {
            if (details != null) {
                Runnable r = details;
                details = null;
                r.run();
            }
        }

        /** Colors of the sub-blocks, for the chips drawn inside the block. */
        List<Color> chips() {
            if (!container()) return List.of();
            List<Color> c = new ArrayList<>();
            for (Item k : children()) {
                if (c.size() >= 40) break;
                c.add(k.color);
            }
            return c;
        }
    }

    static final Color FOLDER = new Color(178, 190, 214), OFF = new Color(196, 198, 204),
            MENU = new Color(142, 206, 186), MENU_OFF = new Color(194, 210, 205);

    final Item root;
    private final Map<String, Item> index = new HashMap<>();

    private SourceTree(Item root) {
        this.root = root;
    }

    /** Follows names from the root ("drivers/usb", or "package>Target packages" for menus). */
    List<Item> path(String spec, String sep) {
        List<Item> stack = new ArrayList<>(List.of(root));
        if (spec == null || spec.isBlank()) return stack;
        for (String name : spec.split(Pattern.quote(sep))) {
            Item cur = stack.get(stack.size() - 1), next = null;
            for (Item k : cur.children()) {
                if (k.name.equals(name) || k.label.equals(name)) { next = k; break; }
            }
            if (next == null) break;
            stack.add(next);
        }
        return stack;
    }

    /** Follows block ids from the root, as far as they still exist (used after a rebuild). */
    List<Item> pathIds(List<String> ids) {
        List<Item> stack = new ArrayList<>(List.of(root));
        for (String id : ids.subList(Math.min(1, ids.size()), ids.size())) {
            Item next = null;
            for (Item k : stack.get(stack.size() - 1).children()) if (k.id.equals(id)) { next = k; break; }
            if (next == null) break;
            stack.add(next);
        }
        return stack;
    }

    // ================================================================ files

    private static final Set<String> ARTIFACTS = Set.of("o", "a", "ko", "mod", "order", "symvers", "cmd", "d",
            "dtb", "dtbo", "lds", "tmp", "lst", "s.o", "so");

    static String ext(String n) {
        int d = n.lastIndexOf('.');
        return d < 0 ? "" : n.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    /** Block title and color for a file, by its kind. */
    static void fileLook(Item it, String n) {
        String e = ext(n);
        String t;
        Color c;
        if (n.endsWith("_defconfig") || n.endsWith(".config") || n.equals(".config")) { t = "CONFIG"; c = new Color(250, 168, 92); }
        else if (n.equals("Makefile") || n.equals("Kbuild") || e.equals("mk")) { t = "MAKEFILE"; c = new Color(132, 172, 240); }
        else if (n.startsWith("Config.in") || n.startsWith("Kconfig")) { t = "KCONFIG"; c = new Color(196, 160, 230); }
        else if (e.equals("patch") || e.equals("diff")) { t = "PATCH"; c = new Color(240, 142, 130); }
        else if (e.equals("sh") || e.equals("py") || e.equals("pl") || n.equals("post-build") || n.equals("post-image")) { t = "SCRIPT"; c = new Color(142, 206, 186); }
        else if (e.equals("hash")) { t = "HASH"; c = new Color(165, 165, 170); }
        else if (e.equals("c") || e.equals("s") || e.equals("rs")) { t = "SOURCE"; c = new Color(242, 202, 112); }
        else if (e.equals("h")) { t = "HEADER"; c = new Color(236, 214, 160); }
        else if (e.equals("dts") || e.equals("dtsi")) { t = "DEVICE TREE"; c = new Color(126, 200, 128); }
        else if (e.equals("txt") || e.equals("rst") || e.equals("md") || e.equals("adoc") || n.startsWith("README")) { t = "TEXT"; c = new Color(214, 214, 220); }
        else { t = "FILE"; c = new Color(205, 205, 210); }
        it.title = t;
        it.color = c;
    }

    private static String fileHtml(Path p, String rel, String extra) {
        long sz = 0;
        try {
            sz = Files.size(p);
        } catch (IOException ignored) {
        }
        return "<html><body><h2>" + esc(p.getFileName().toString()) + "</h2><table>" + row("Path", rel)
                + row("Size", BrModel.size(sz)) + extra + "</table><p><i>Double-click to read it.</i></p></body></html>";
    }

    static List<Path> list(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String row(String k, String v) {
        return v == null || v.isEmpty() ? "" : "<tr><td valign=top><b>" + esc(k) + "</b></td><td>" + esc(v) + "</td></tr>";
    }

    // ================================================================ Linux kernel

    static SourceTree kernel(KernelModel k, boolean onlyBuilt) {
        Item root = new Item("k:", "");
        root.title = "KERNEL";
        root.label = "Linux " + k.version;
        root.meta = k.yes + " y · " + k.mod + " m";
        root.color = Theme.category("kernel");
        root.loader = () -> kernelChildren(k, "", onlyBuilt);
        List<String> outs = new ArrayList<>();
        if (k.zImage > 0) outs.add("zImage " + BrModel.size(k.zImage));
        if (k.vmlinux > 0) outs.add("vmlinux " + BrModel.size(k.vmlinux) + " (with debug info)");
        root.html = "<html><body><h2>Linux " + esc(k.version) + "</h2><table>" + row("Source", k.dir.toString())
                + row("Options", k.yes + " built in (=y), " + k.mod + " as modules (=m)")
                + row("Compiled", k.root.objects + " object files, " + k.root.modules + " modules")
                + row("Output", String.join(", ", outs)) + row("Device trees", k.dtbs.size() + "")
                + "</table><p><b>Colors</b><br>&nbsp;• blue: built into the kernel image<br>&nbsp;• orange: modules only"
                + "<br>&nbsp;• yellow: both<br>&nbsp;• gray: not built</p>"
                + "<p>The small chips inside a block are its sub-blocks. Double-click a block to go in, the top block to go back.</p></body></html>";
        return new SourceTree(root);
    }

    private static List<Item> kernelChildren(KernelModel k, String rel, boolean onlyBuilt) {
        List<Item> out = new ArrayList<>();
        Path dir = rel.isEmpty() ? k.dir : k.dir.resolve(rel);
        Set<String> names = new HashSet<>();
        for (Path p : list(dir)) names.add(p.getFileName().toString());
        for (Path p : list(dir)) {
            String n = p.getFileName().toString(), r = rel.isEmpty() ? n : rel + "/" + n;
            if (Files.isDirectory(p)) {
                KernelModel.Dir d = k.dirs.get(r);
                if (onlyBuilt && d == null) continue;
                out.add(kernelDir(k, r, n, d, onlyBuilt));
            } else {
                String e = ext(n);
                if (ARTIFACTS.contains(e) || n.endsWith(".mod.c") || n.equals("modules.order") || n.startsWith("vmlinux")
                        || n.equals("System.map") || n.equals("Module.symvers") || n.startsWith("modules.")) continue;
                boolean src = e.equals("c") || e.equals("s");
                String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
                boolean compiled = src && names.contains(base + ".o");
                if (onlyBuilt && !compiled) continue;
                Item it = new Item("k:" + r, n);
                fileLook(it, n);
                it.file = p;
                it.label = n;
                if (src && !compiled) { it.color = OFF; it.title = "SOURCE · NOT BUILT"; }
                else if (compiled) it.title = "COMPILED";
                it.details = () -> it.html = fileHtml(p, r, row("Compiled", src ? (compiled ? "yes (" + base + ".o)" : "no") : ""));
                out.add(it);
            }
        }
        out.sort(Comparator.comparing((Item i) -> i.file != null)
                .thenComparing((Item i) -> -kernelObjects(k, i))
                .thenComparing(i -> i.name));
        return out;
    }

    private static int kernelObjects(KernelModel k, Item i) {
        KernelModel.Dir d = k.dirs.get(i.id.substring(2));
        return d == null ? 0 : d.objects;
    }

    private static Item kernelDir(KernelModel k, String rel, String name, KernelModel.Dir d, boolean onlyBuilt) {
        Item it = new Item("k:" + rel, name);
        it.loader = () -> kernelChildren(k, rel, onlyBuilt);
        if (d != null) {
            it.color = BuildrootBlocks.kernelColor(d.kind());
            it.title = d.prompt.isEmpty() ? d.kind().toUpperCase(Locale.ROOT) : d.prompt.toUpperCase(Locale.ROOT);
            String sym = k.symbolText(d, false);
            it.label = name + "/" + (sym.isEmpty() ? "" : "  " + sym);
            it.meta = d.objects + " obj" + (d.modules > 0 ? " · " + d.modules + " ko" : "");
            it.details = () -> it.html = BuildrootBlocks.kernelDirHtml(k, d);
        } else {
            it.color = OFF;
            it.label = name + "/";
            it.meta = "not built";
            it.details = () -> {
                String pr = KernelModel.prompt(k.dir.resolve(rel).resolve("Kconfig"));
                it.title = pr.isEmpty() ? "NOT BUILT" : pr.toUpperCase(Locale.ROOT);
                it.html = "<html><body><h2>" + esc(rel) + "/</h2><table>" + row("Menu", pr)
                        + row("Goes", "not built with this .config") + "</table><p><i>Double-click to go in.</i></p></body></html>";
            };
            it.title = "NOT BUILT";
        }
        return it;
    }

    // ================================================================ Buildroot

    static SourceTree buildroot(BrModel m, boolean onlyUsed) {
        Path br = m.buildroot;
        Set<Path> used = usedPaths(m);
        Item root = new Item("b:", "");
        root.title = "BUILDROOT";
        root.label = "Buildroot " + m.brVersion;
        root.color = Theme.category("board");
        root.meta = br.toString();
        root.html = "<html><body><h2>Buildroot " + esc(m.brVersion) + "</h2><table>" + row("Sources", br.toString())
                + row("Packages", "2 900+ recipes in package/, " + m.count("target") + " target and " + m.count("host")
                + " host packages in this build") + "</table>"
                + "<p><b>Colors</b><br>&nbsp;• green outline: used by this build<br>&nbsp;• blue package: in this build"
                + "<br>&nbsp;• gray: not used<br>&nbsp;• teal: a menu (as in make menuconfig)</p>"
                + "<p>package/ shows Buildroot's own menus. The small chips inside a block are its sub-blocks. "
                + "Double-click a block to go in, the top block to go back.</p></body></html>";
        root.loader = () -> {
            List<Item> out = new ArrayList<>();
            for (Path p : list(br)) {
                String n = p.getFileName().toString();
                if (n.equals("package")) out.add(packagesRoot(m, used, onlyUsed));
                else if (Files.isDirectory(p)) {
                    Item it = folder(m, used, p, onlyUsed);
                    if (it != null) out.add(it);
                } else if (!onlyUsed || used.contains(p)) out.add(file(m, used, p));
            }
            return out;
        };
        return new SourceTree(root);
    }

    /** Every path the .config points into (defconfig, board files, patches, overlays…). */
    private static Set<Path> usedPaths(BrModel m) {
        Set<Path> s = new HashSet<>();
        for (String v : m.config.values()) {
            for (String tok : v.split("\\s+")) {
                if (!tok.contains("/") || tok.contains("://")) continue;
                tok = tok.replace("$(BR2_EXTERNAL)", "").replace("$(TOPDIR)/", "");
                Path p = tok.startsWith("/") ? Path.of(tok) : m.buildroot.resolve(tok);
                if (Files.exists(p)) s.add(p.normalize());
            }
        }
        for (BrModel.Pkg p : m.pkgs.values()) if (!p.pkgDir().isEmpty()) s.add(m.buildroot.resolve(p.pkgDir()).normalize());
        return s;
    }

    private static boolean isUsed(Set<Path> used, Path p) {
        Path n = p.normalize();
        for (Path u : used) if (u.startsWith(n)) return true;
        return false;
    }

    private static String rel(BrModel m, Path p) {
        return m.buildroot.relativize(p).toString();
    }

    private static Item folder(BrModel m, Set<Path> used, Path p, boolean onlyUsed) {
        boolean u = isUsed(used, p);
        if (onlyUsed && !u) return null;
        String r = rel(m, p);
        Item it = new Item("b:" + r, p.getFileName().toString());
        it.title = "FOLDER";
        it.label = it.name + "/";
        it.used = u;
        it.color = u ? new Color(160, 206, 160) : FOLDER;
        it.loader = () -> {
            List<Item> out = new ArrayList<>();
            for (Path c : list(p)) {
                if (Files.isDirectory(c)) {
                    Item f = folder(m, used, c, onlyUsed);
                    if (f != null) out.add(f);
                } else if (!onlyUsed || isUsed(used, c) || used.contains(p.normalize())) out.add(file(m, used, c));
            }
            return out;
        };
        it.details = () -> {
            List<Path> l = list(p);
            long dirs = l.stream().filter(Files::isDirectory).count();
            it.meta = (dirs > 0 ? dirs + " dirs · " : "") + (l.size() - dirs) + " files";
            it.html = "<html><body><h2>" + esc(r) + "/</h2><table>" + row("Folders", dirs + "") + row("Files", (l.size() - dirs) + "")
                    + row("This build", u ? "uses something in here" : "") + "</table><p><i>Double-click to go in.</i></p></body></html>";
        };
        return it;
    }

    private static Item file(BrModel m, Set<Path> used, Path p) {
        String r = rel(m, p), n = p.getFileName().toString();
        Item it = new Item("b:" + r, n);
        fileLook(it, n);
        it.label = n;
        it.file = p;
        it.used = used.contains(p.normalize());
        it.details = () -> {
            try {
                it.meta = BrModel.size(Files.size(p));
            } catch (IOException ignored) {
            }
            it.html = fileHtml(p, r, row("This build", it.used ? "uses this file" : ""));
        };
        return it;
    }

    // ---------------------------------------------------------------- package/ as Buildroot's menus

    private static final Pattern MENU_LINE = Pattern.compile("^menu\\s+\"(.+)\"");
    private static final Pattern SOURCE = Pattern.compile("^source\\s+\"([^\"]+)\"");

    private static Item packagesRoot(BrModel m, Set<Path> used, boolean onlyUsed) {
        Path pk = m.buildroot.resolve("package");
        Item it = new Item("b:package", "package");
        it.title = "PACKAGES · MENUS";
        it.label = "package/";
        it.used = true;
        it.color = MENU;
        it.loader = () -> {
            List<Item> out = new ArrayList<>();
            Set<Object> seen = new HashSet<>();   // recipe folders (Path) and block ids (String) already placed
            out.addAll(parseMenus(m, used, pk.resolve("Config.in"), "b:package", seen, onlyUsed));
            out.addAll(parseMenus(m, used, pk.resolve("Config.in.host"), "b:package", seen, onlyUsed));
            // recipes no menu sources (toolchain helpers, skeletons used internally…), and package/'s own files
            List<Item> rest = new ArrayList<>();
            for (Path p : list(pk)) {
                if (!Files.isDirectory(p)) continue;
                String id = "b:" + rel(m, p);
                if (!seen.contains(id)) {
                    Item pi = pkg(m, used, p, false, seen, onlyUsed);
                    if (pi != null) rest.add(pi);
                }
                if (!seen.contains(id + "#host") && m.pkgs.containsKey("host-" + p.getFileName())) {
                    Item pi = pkg(m, used, p, true, seen, onlyUsed);
                    if (pi != null) rest.add(pi);
                }
            }
            if (!rest.isEmpty()) out.add(menu("b:package/~other", "Other recipes (no menu here)", rest));
            for (Path p : list(pk)) if (!Files.isDirectory(p) && !onlyUsed) out.add(file(m, used, p));
            out.removeIf(Objects::isNull);
            return out;
        };
        it.details = () -> {
            it.meta = "2 900+ recipes";
            it.html = "<html><body><h2>package/</h2><p>All package recipes, arranged like <code>make menuconfig</code>: "
                    + "Target packages, Host utilities and their sub-menus. Packages in this build are blue.</p></body></html>";
        };
        return it;
    }

    private static Item menu(String id, String name, List<Item> kids) {
        Item it = new Item(id, name);
        it.title = "MENU";
        it.label = name;
        long pk = kids.stream().filter(k -> k.title.startsWith("PACKAGE") || k.title.startsWith("HOST") || k.title.startsWith("IN BUILD")).count();
        long on = kids.stream().filter(k -> k.used).count();
        long sub = kids.stream().filter(k -> k.title.equals("MENU")).count();
        it.meta = (sub > 0 ? sub + " menus · " : "") + pk + " pkg" + (on > 0 ? " · " + on + " used" : "");
        it.used = on > 0;
        it.color = on > 0 ? MENU : MENU_OFF;
        it.loader = () -> kids;
        it.html = "<html><body><h2>" + esc(name) + "</h2><table>" + row("Sub-menus", sub + "") + row("Packages", pk + "")
                + row("In this build", on + "") + "</table><p><i>Double-click to go in.</i></p></body></html>";
        return it;
    }

    /** Items for the menus and packages a Config.in file sources, following nested files. */
    private static List<Item> parseMenus(BrModel m, Set<Path> used, Path file, String idBase, Set<Object> seen, boolean onlyUsed) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return new ArrayList<>();
        }
        Deque<List<Item>> stack = new ArrayDeque<>();
        Deque<String> names = new ArrayDeque<>();
        List<Item> top = new ArrayList<>();
        stack.push(top);
        for (String raw : lines) {
            String l = raw.strip();
            Matcher mm = MENU_LINE.matcher(l), sm = SOURCE.matcher(l);
            if (mm.find()) {
                stack.push(new ArrayList<>());
                names.push(mm.group(1));
            } else if (l.equals("endmenu") && names.size() > 0) {
                List<Item> kids = stack.pop();
                String name = names.pop();
                kids.removeIf(Objects::isNull);
                if (onlyUsed && kids.stream().noneMatch(k -> k.used)) continue;
                if (onlyUsed) kids.removeIf(k -> !k.used);
                stack.peek().add(menu(idBase + ">" + name, name, kids));
            } else if (sm.find()) {
                String src = sm.group(1).replaceAll("^\\$\\(?BR2_BASE_DIR\\)?/?", "");
                if (src.contains("$")) continue;
                Path p = m.buildroot.resolve(src).normalize();
                Path dir = p.getParent();
                Path pkRoot = m.buildroot.resolve("package");
                String fn = p.getFileName().toString();
                boolean isPkg = dir != null && dir.startsWith(pkRoot) && !dir.equals(pkRoot)
                        && (fn.equals("Config.in") || fn.equals("Config.in.host"));
                if (isPkg) {
                    seen.add(dir.normalize());
                    seen.add("b:" + rel(m, dir) + (fn.endsWith(".host") ? "#host" : ""));
                    stack.peek().add(pkg(m, used, dir, p.getFileName().toString().endsWith(".host"), seen, onlyUsed));
                } else if (Files.isRegularFile(p)) {
                    stack.peek().addAll(parseMenus(m, used, p, idBase, seen, onlyUsed));
                }
            }
        }
        while (stack.size() > 1) stack.pop();    // unbalanced file: keep what was at the top
        top.removeIf(Objects::isNull);
        return top;
    }

    private static Item pkg(BrModel m, Set<Path> used, Path dir, boolean host, Set<Object> seen, boolean onlyUsed) {
        String name = (host ? "host-" : "") + dir.getFileName();
        BrModel.Pkg bp = m.pkgs.get(name);
        boolean inBuild = bp != null;
        if (onlyUsed && !inBuild) return null;
        Path cfg = dir.resolve(host ? "Config.in.host" : "Config.in");
        Item it = new Item("b:" + rel(m, dir) + (host ? "#host" : ""), name);
        it.title = inBuild ? "IN BUILD" : host ? "HOST PACKAGE" : "PACKAGE";
        it.label = name;
        it.used = inBuild;
        it.color = inBuild ? (host ? Theme.category("host") : Theme.category("target")) : OFF;
        if (bp != null) it.meta = BrModel.duration(m.buildMs(name));
        it.loader = () -> {
            List<Item> out = new ArrayList<>();
            // a recipe whose Config.in sources others (x11r7, gstreamer1, python…) holds sub-menus
            List<Item> nested = parseMenus(m, used, cfg, it.id, seen, onlyUsed);
            nested.removeIf(k -> k.id.equals(it.id));
            out.addAll(nested);
            for (Path c : list(dir)) {
                if (Files.isDirectory(c)) {
                    if (seen.contains(c.normalize())) continue;
                    Item f = folder(m, used, c, false);
                    if (f != null) out.add(f);
                } else out.add(file(m, used, c));
            }
            return out;
        };
        it.details = () -> {
            String up = dir.getFileName().toString().toUpperCase(Locale.ROOT).replace('-', '_');
            Map<String, String> mk = mkVars(dir, up);
            String version = bp != null ? bp.version() : mk.getOrDefault("VERSION", "");
            it.label = name + (version.isEmpty() || version.contains("$") ? "" : " " + BrModel.shortVersion(version));
            String symbol = "", prompt = "";
            List<String> deps = new ArrayList<>(), help = new ArrayList<>();
            try {
                List<String> cl = Files.readAllLines(cfg);
                boolean inHelp = false, first = true;
                for (String l : cl) {
                    String s = l.strip();
                    if (s.startsWith("config ") || s.startsWith("menuconfig ")) {
                        if (!first) break;
                        first = false;
                        symbol = s.substring(s.indexOf(' ') + 1).strip();
                        inHelp = false;
                    } else if (!first && symbol.length() > 0) {
                        if (prompt.isEmpty() && (s.startsWith("bool \"") || s.startsWith("tristate \"")))
                            prompt = s.substring(s.indexOf('"') + 1, s.lastIndexOf('"'));
                        else if (s.startsWith("depends on") || s.startsWith("select")) deps.add(s);
                        else if (s.equals("help") || s.equals("---help---")) inHelp = true;
                        else if (inHelp && !s.isEmpty() && help.size() < 8) help.add(s);
                    }
                }
            } catch (IOException ignored) {
            }
            String val = symbol.isEmpty() ? "" : m.config.getOrDefault(symbol, "not set");
            StringBuilder h = new StringBuilder("<html><body><h2>" + esc(name) + "</h2><table>");
            h.append(row("Prompt", prompt)).append(row("Option", symbol.isEmpty() ? "" : symbol + " = " + val))
                    .append(row("Version", version)).append(row("License", mk.getOrDefault("LICENSE", "")))
                    .append(row("Site", mk.getOrDefault("SITE", "")))
                    .append(row("In this build", inBuild ? "yes" + (it.meta.isEmpty() ? "" : ", built in " + it.meta) : "no"))
                    .append(row("Recipe", rel(m, dir) + "/")).append("</table>");
            if (!help.isEmpty()) h.append("<p>").append(esc(String.join(" ", help))).append("</p>");
            if (!deps.isEmpty()) {
                h.append("<p><b>Kconfig</b><br>");
                for (String d : deps) h.append("&nbsp;• ").append(esc(d)).append("<br>");
                h.append("</p>");
            }
            h.append("<p><i>Double-click to see its files.</i></p></body></html>");
            it.html = h.toString();
        };
        return it;
    }

    /** NAME_VERSION, NAME_SITE, NAME_LICENSE from a package's .mk file. */
    private static Map<String, String> mkVars(Path dir, String up) {
        Map<String, String> r = new HashMap<>();
        Path mk = dir.resolve(dir.getFileName() + ".mk");
        if (!Files.isRegularFile(mk)) return r;
        try {
            Pattern p = Pattern.compile("^" + Pattern.quote(up) + "_(VERSION|SITE|LICENSE)\\s*[:?]?=\\s*(.*)$");
            for (String l : Files.readAllLines(mk)) {
                Matcher x = p.matcher(l);
                if (x.find() && !r.containsKey(x.group(1))) r.put(x.group(1), x.group(2).strip());
            }
        } catch (IOException ignored) {
        }
        return r;
    }
}
