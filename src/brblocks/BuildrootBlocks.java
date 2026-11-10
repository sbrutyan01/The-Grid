package brblocks;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The Grid: opens a Buildroot output folder and shows it as EVIA Paradigma-style blocks.
 *
 *   java -cp classes brblocks.BuildrootBlocks [folder]
 *   java -cp classes brblocks.BuildrootBlocks --png file.png [--packages] [--host] [--kernel drivers/usb | --sources "package>Target packages"] [--all] folder
 */
public final class BuildrootBlocks extends JFrame {
    static final String NAME = "The Grid", VERSION = "1.0.0";
    private static final Preferences PREFS = Preferences.userNodeForPackage(BuildrootBlocks.class);

    private final BlockCanvas canvas = new BlockCanvas();
    private final JEditorPane details = new JEditorPane("text/html", "");
    private final JLabel status = new JLabel(" ");
    private final JToggleButton systemBtn = new JToggleButton("System"), packagesBtn = new JToggleButton("Packages"),
            kernelBtn = new JToggleButton("Kernel");
    private final JCheckBox hostBox = new JCheckBox("Host packages"), reduceBox = new JCheckBox("Direct wires only", true);
    private final JTextField search = new JTextField(14);
    private final JProgressBar busy = new JProgressBar();
    private BrModel model;
    private Path folder;
    private KernelModel kernel;        // read the first time the Kernel view opens
    private SourceTree kernelTree, sourceTree;
    private List<SourceTree.Item> kStack = new ArrayList<>(), sStack = new ArrayList<>();   // where each view is
    private final JToggleButton sourcesBtn = new JToggleButton("Sources");
    private final JCheckBox builtBox = new JCheckBox("Only what was built", true);

    private BuildrootBlocks() {
        super(NAME);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1400, 880);
        setLocationByPlatform(true);

        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(button("Open…", "Open a Buildroot workspace or output folder", this::chooseFolder));
        tb.add(button("Reload", "Read the folder again (runs make show-info)", () -> load(folder, false)));
        tb.addSeparator();
        ButtonGroup bg = new ButtonGroup();
        bg.add(systemBtn);
        bg.add(packagesBtn);
        bg.add(kernelBtn);
        bg.add(sourcesBtn);
        systemBtn.setSelected(true);
        systemBtn.setToolTipText("Board → toolchain → bootloader, kernel, root file system → images");
        packagesBtn.setToolTipText("Every package as a block; wires go from a dependency to the package that needs it");
        systemBtn.addActionListener(e -> rebuild());
        packagesBtn.addActionListener(e -> rebuild());
        kernelBtn.setToolTipText("The Linux sources as blocks, marked with what was compiled; double-click to go in, the top block to go back");
        kernelBtn.addActionListener(e -> rebuild());
        sourcesBtn.setToolTipText("The Buildroot sources as blocks; package/ follows menuconfig's menus");
        sourcesBtn.addActionListener(e -> rebuild());
        builtBox.setToolTipText("Kernel and Sources: hide what this build did not compile or use");
        builtBox.addActionListener(e -> remakeTrees());
        tb.add(systemBtn);
        tb.add(packagesBtn);
        tb.add(kernelBtn);
        tb.add(sourcesBtn);
        tb.addSeparator();
        hostBox.setToolTipText("Also show host-* packages (tools built to run on this computer)");
        reduceBox.setToolTipText("Hide a wire A→C when A already reaches C through other blocks");
        hostBox.addActionListener(e -> { if (packagesBtn.isSelected()) rebuild(); });
        reduceBox.addActionListener(e -> { if (packagesBtn.isSelected()) rebuild(); });
        tb.add(hostBox);
        tb.add(reduceBox);
        tb.add(builtBox);
        tb.addSeparator();
        tb.add(new JLabel("Find "));
        search.setMaximumSize(search.getPreferredSize());
        search.setToolTipText("Highlight blocks whose name contains this; Enter selects the first");
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { find(false); }
            public void removeUpdate(DocumentEvent e) { find(false); }
            public void changedUpdate(DocumentEvent e) { find(false); }
        });
        search.addActionListener(e -> find(true));
        tb.add(search);
        tb.addSeparator();
        tb.add(button("Fit", "Show the whole diagram (F)", canvas::fit));
        JCheckBox darkBox = new JCheckBox("Dark", PREFS.getBoolean("dark", false));
        darkBox.addActionListener(e -> {
            PREFS.putBoolean("dark", darkBox.isSelected());
            Theme.apply(darkBox.isSelected());
            details.setText(details.getText());
            canvas.repaint();
        });
        tb.add(Box.createHorizontalGlue());
        busy.setIndeterminate(true);
        busy.setVisible(false);
        busy.setMaximumSize(new Dimension(120, 14));
        tb.add(busy);
        tb.add(darkBox);

        details.setEditable(false);
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        details.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane ds = new JScrollPane(details);
        ds.setBorder(BorderFactory.createEmptyBorder());
        ds.setMinimumSize(new Dimension(220, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, ds);
        split.setResizeWeight(1);
        split.setDividerLocation(1040);
        status.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        canvas.onSelect(this::showDetails);
        canvas.setEmptyText("Open a Buildroot folder (File → Open…)");
        getRootPane().registerKeyboardAction(e -> chooseFolder(),
                KeyStroke.getKeyStroke(KeyEvent.VK_O, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> search.requestFocusInWindow(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        add(tb, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    private static JButton button(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(e -> r.run());
        return b;
    }

    // ================================================================ loading

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser(folder != null ? folder.toFile() : new File(System.getProperty("user.home")));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Buildroot workspace or output folder");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) load(fc.getSelectedFile().toPath(), true);
    }

    private void load(Path dir, boolean useCache) {
        if (dir == null) {
            chooseFolder();
            return;
        }
        busy.setVisible(true);
        status.setText("Loading " + dir + " …");
        new SwingWorker<BrModel, String>() {
            @Override
            protected BrModel doInBackground() throws Exception {
                return BrModel.load(dir, useCache, s -> publish(s));
            }

            @Override
            protected void process(List<String> chunks) {
                status.setText(chunks.get(chunks.size() - 1) + " …");
            }

            @Override
            protected void done() {
                busy.setVisible(false);
                try {
                    model = get();
                    folder = dir;
                    kernel = null;
                    kernelTree = sourceTree = null;
                    kStack = new ArrayList<>();
                    sStack = new ArrayList<>();
                    PREFS.put("folder", dir.toString());
                    setTitle(NAME + " — " + model.out.getParent().getFileName() + " (" + model.out + ")");
                    rebuild();
                    updateStatus();
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    status.setText("Could not open " + dir + ": " + c.getMessage());
                    JOptionPane.showMessageDialog(BuildrootBlocks.this, c.getMessage(), NAME,
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void updateStatus() {
        BrModel m = model;
        String s = (m.cfg("BR2_TARGET_GENERIC_HOSTNAME").isEmpty() ? "" : m.cfg("BR2_TARGET_GENERIC_HOSTNAME") + "  ·  ")
                + "Buildroot " + (m.brVersion.isEmpty() ? "?" : m.brVersion)
                + "  ·  " + m.count("target") + " target, " + m.count("host") + " host packages"
                + (m.totalBuildMs() > 0 ? "  ·  build time " + BrModel.duration(m.totalBuildMs()) : "")
                + "  ·  from " + m.source
                + (m.warning != null ? "  ·  ⚠ " + m.warning : "");
        status.setText(s);
    }

    private void rebuild() {
        if (model == null) return;
        boolean pk = packagesBtn.isSelected();
        hostBox.setEnabled(pk);
        reduceBox.setEnabled(pk);
        builtBox.setEnabled(kernelBtn.isSelected() || sourcesBtn.isSelected());
        if (kernelBtn.isSelected() || sourcesBtn.isSelected()) {
            canvas.setGraph(treeGraph());
        } else {
            canvas.setGraph(pk ? packagesGraphWired(model, hostBox.isSelected(), reduceBox.isSelected()) : systemGraphWired(model));
            updateStatus();
        }
        find(false);
        showDetails(null);
    }

    /** Shows a kernel source folder ("" = the whole kernel). */
    private void openKernel(String path) {
        kernelBtn.setSelected(true);
        if (loadTrees() && kernelTree != null) kStack = kernelTree.path(path, "/");
        rebuild();
    }

    private List<SourceTree.Item> stack() {
        return kernelBtn.isSelected() ? kStack : sStack;
    }

    private void enter(SourceTree.Item it) {
        stack().add(it);
        rebuild();
    }

    private void back() {
        if (stack().size() > 1) stack().remove(stack().size() - 1);
        rebuild();
    }

    /** Reads the kernel build and makes both trees; false when there is nothing to show. */
    private boolean loadTrees() {
        if (kernel == null && KernelModel.find(model) != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            try {
                Path dir = KernelModel.find(model);
                BrModel.Pkg lx = model.pkgs.get("linux");
                kernel = KernelModel.load(dir, lx == null ? dir.getFileName().toString().substring(6) : lx.version());
            } catch (IOException e) {
                status.setText("Could not read the kernel build: " + e.getMessage());
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }
        }
        if (kernelTree == null && kernel != null) {
            kernelTree = SourceTree.kernel(kernel, builtBox.isSelected());
            kStack = kernelTree.pathIds(ids(kStack));
        }
        if (sourceTree == null && model.buildroot != null) {
            sourceTree = SourceTree.buildroot(model, builtBox.isSelected());
            sStack = sourceTree.pathIds(ids(sStack));
        }
        return true;
    }

    private static List<String> ids(List<SourceTree.Item> st) {
        List<String> r = new ArrayList<>();
        for (SourceTree.Item i : st) r.add(i.id);
        return r;
    }

    /** "Only what was built" changed: make the trees again and stay where we were, if it is still there. */
    private void remakeTrees() {
        kernelTree = sourceTree = null;
        if (model != null && (kernelBtn.isSelected() || sourcesBtn.isSelected())) rebuild();
    }

    private Graph treeGraph() {
        loadTrees();
        boolean k = kernelBtn.isSelected();
        SourceTree t = k ? kernelTree : sourceTree;
        if (t == null) {
            canvas.setEmptyText(k ? "The Linux kernel has not been built in this output folder yet"
                    : "The Buildroot source folder was not found");
            return new Graph();
        }
        List<SourceTree.Item> st = stack();
        if (st.isEmpty()) st.add(t.root);
        StringBuilder crumbs = new StringBuilder();
        for (SourceTree.Item i : st) {
            if (crumbs.length() > 0) crumbs.append("  ›  ");
            crumbs.append(i == t.root ? i.label : i.name);
        }
        status.setText(crumbs + (k && kernel != null ? "      ·  " + kernel.root.objects + " objects, " + kernel.root.modules
                + " modules compiled  ·  " + kernel.dir : "      ·  " + model.buildroot));
        return itemGraph(st, this);
    }

    /** Opens a text file in a window (binary and very large files are refused). */
    private void showFile(Path f) {
        try {
            if (Files.size(f) > 4_000_000) { status.setText(f + " is too big to show"); return; }
            byte[] b = Files.readAllBytes(f);
            for (int i = 0; i < Math.min(b.length, 8000); i++) {
                if (b[i] == 0) { status.setText(f.getFileName() + " is a binary file"); return; }
            }
            JTextArea ta = new JTextArea(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            ta.setEditable(false);
            ta.setTabSize(8);
            ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            ta.setCaretPosition(0);
            JDialog d = new JDialog(this, f.toString(), false);
            d.add(new JScrollPane(ta));
            d.setSize(820, 640);
            d.setLocationRelativeTo(this);
            d.setVisible(true);
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
    }

    private void find(boolean jump) {
        String q = search.getText().trim().toLowerCase();
        Set<Graph.Node> m = new LinkedHashSet<>();
        if (!q.isEmpty()) {
            for (Graph.Node n : canvas.graph().nodes) {
                if (n.id.toLowerCase().contains(q) || n.label.toLowerCase().contains(q)) m.add(n);
            }
        }
        canvas.setMatches(m);
        if (jump && !m.isEmpty()) {
            Graph.Node n = m.iterator().next();
            canvas.select(n);
            canvas.center(n);
        }
    }

    /** Switches to the package diagram and selects a package there. */
    private void openPackage(String name) {
        BrModel.Pkg p = model.pkgs.get(name);
        if (p != null && p.isHost() && !hostBox.isSelected()) hostBox.setSelected(true);
        packagesBtn.setSelected(true);
        rebuild();
        Graph.Node n = canvas.graph().get(name);
        if (n != null) {
            canvas.select(n);
            canvas.center(n);
        }
    }

    private void openPackages(boolean host) {
        hostBox.setSelected(host);
        packagesBtn.setSelected(true);
        rebuild();
    }

    private void showMk(BrModel.Pkg p) {
        Path mk = model.mkFile(p);
        if (mk == null) {
            Toolkit.getDefaultToolkit().beep();
            status.setText("No .mk file found for " + p.name());
            return;
        }
        showFile(mk);
    }

    // ================================================================ details panel

    private void showDetails(Graph.Node n) {
        if (n == null) {
            details.setText(model == null ? "" : overviewHtml(model));
        } else {
            details.setText(n.html);
        }
        details.setCaretPosition(0);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String row(String k, String v) {
        return v == null || v.isEmpty() ? "" : "<tr><td valign=top><b>" + esc(k) + "</b></td><td>" + esc(v) + "</td></tr>";
    }

    private static String list(String title, Collection<String> items) {
        if (items.isEmpty()) return "";
        StringBuilder b = new StringBuilder("<p><b>" + esc(title) + "</b> (" + items.size() + ")<br>");
        for (String s : items) b.append("&nbsp;• ").append(esc(s)).append("<br>");
        return b.append("</p>").toString();
    }

    private static String overviewHtml(BrModel m) {
        StringBuilder b = new StringBuilder("<html><body><h2>" + esc(m.out.getParent().getFileName().toString()) + "</h2><table>");
        b.append(row("Output", m.out.toString()));
        b.append(row("Buildroot", m.buildroot == null ? "" : m.buildroot + "  (" + m.brVersion + ")"));
        b.append(row("Defconfig", Path.of(m.cfg("BR2_DEFCONFIG").isEmpty() ? "-" : m.cfg("BR2_DEFCONFIG")).getFileName().toString()));
        b.append(row("Host name", m.cfg("BR2_TARGET_GENERIC_HOSTNAME")));
        b.append(row("Packages", m.count("target") + " target, " + m.count("host") + " host, " + m.count("rootfs") + " file systems"));
        b.append(row("Build time", BrModel.duration(m.totalBuildMs())));
        b.append(row("Data from", m.source));
        if (!m.evia.isEmpty()) b.append(row("Made by", m.evia.getOrDefault("app", "EVIA Paradigma")));
        b.append("</table>");
        if (m.warning != null) b.append("<p><font color=red>").append(esc(m.warning)).append("</font></p>");
        // slowest packages
        List<String> slow = new ArrayList<>(m.steps.keySet());
        slow.sort(Comparator.comparingLong(m::buildMs).reversed());
        List<String> top = new ArrayList<>();
        for (String s : slow.subList(0, Math.min(10, slow.size()))) top.add(s + " — " + BrModel.duration(m.buildMs(s)));
        b.append(list("Slowest to build", top));
        b.append("<p><i>Click a block for details. Double-click opens it. Wheel zooms, drag pans, F fits.</i></p>");
        return b.append("</body></html>").toString();
    }

    private static String pkgHtml(BrModel m, BrModel.Pkg p) {
        StringBuilder b = new StringBuilder("<html><body><h2>" + esc(p.name()) + "</h2><table>");
        b.append(row("Kind", p.category() + (p.virtual() ? " (virtual)" : "")));
        b.append(row("Version", p.version()));
        b.append(row("Licenses", p.licenses()));
        b.append(row("Recipe", p.pkgDir()));
        List<String> inst = new ArrayList<>();
        if (p.installTarget()) inst.add("target");
        if (p.installStaging()) inst.add("staging");
        if (p.installImages()) inst.add("images");
        b.append(row("Installs to", String.join(", ", inst)));
        b.append(row("Patches", p.patches() == 0 ? "" : String.valueOf(p.patches())));
        b.append(row("Build time", BrModel.duration(m.buildMs(p.name()))));
        b.append("</table>");
        Map<String, Long> st = m.steps.get(p.name());
        if (st != null) {
            List<String> l = new ArrayList<>();
            st.forEach((k, v) -> l.add(k + " — " + (v < 1000 ? v + " ms" : BrModel.duration(v))));
            b.append(list("Build steps", l));
        }
        b.append(list("Needs", p.deps()));
        b.append(list("Needed by", p.rdeps()));
        b.append(list("Sources", p.sources()));
        b.append("<p><i>Double-click the block to read its .mk file.</i></p>");
        return b.append("</body></html>").toString();
    }

    // ================================================================ diagrams

    static Graph packagesGraph(BrModel m, boolean host, boolean reduce) {
        Graph g = new Graph();
        for (BrModel.Pkg p : m.pkgs.values()) {
            if (p.isHost() && !host) continue;
            String cat = p.category();
            Graph.Node n = g.add(new Graph.Node(p.name(), cat.toUpperCase(Locale.ROOT),
                    p.name() + (p.version().isEmpty() ? "" : " " + BrModel.shortVersion(p.version())),
                    Theme.category(cat)));
            n.pkg = p;
            n.dashed = p.virtual();
            n.meta = BrModel.duration(m.buildMs(p.name()));
            n.html = pkgHtml(m, p);
        }
        for (Graph.Node n : g.nodes) {
            for (String d : n.pkg.deps()) g.link(g.get(d), n);
        }
        if (reduce) g.reduce();
        for (Graph.Node n : g.nodes) n.hasInput = !g.preds(n).isEmpty();
        return g;
    }

    static Graph systemGraph(BrModel m) {
        return systemGraph(m, null);
    }

    private static Graph systemGraph(BrModel m, BuildrootBlocks w) {
        Graph g = new Graph();

        String defconfig = m.cfg("BR2_DEFCONFIG").isEmpty() ? "" : Path.of(m.cfg("BR2_DEFCONFIG")).getFileName().toString();
        String cpu = m.cfg("BR2_GCC_TARGET_CPU");
        Graph.Node board = g.add(new Graph.Node("board", "BOARD", defconfig.isEmpty() ? m.cfg("BR2_ARCH") : defconfig,
                Theme.category("board")));
        board.hasInput = false;
        board.meta = m.cfg("BR2_ARCH") + (cpu.isEmpty() ? "" : " · " + cpu);
        board.html = "<html><body><h2>Board</h2><table>" + row("Defconfig", defconfig) + row("Architecture", m.cfg("BR2_ARCH"))
                + row("CPU", cpu) + row("Host name", m.cfg("BR2_TARGET_GENERIC_HOSTNAME"))
                + row("Issue", m.cfg("BR2_TARGET_GENERIC_ISSUE")) + row("Console", m.cfg("BR2_TARGET_GENERIC_GETTY_PORT"))
                + row("Overlay", m.cfg("BR2_ROOTFS_OVERLAY")) + row("Post-image", m.cfg("BR2_ROOTFS_POST_IMAGE_SCRIPT"))
                + row("Buildroot", m.brVersion) + (m.evia.isEmpty() ? "" : row("Made by", m.evia.get("app")))
                + "</table></body></html>";

        List<BrModel.Pkg> hostPkgs = m.pkgs.values().stream().filter(BrModel.Pkg::isHost).toList();
        Graph.Node hostN = g.add(new Graph.Node("host", "HOST TOOLS", hostPkgs.size() + " host packages",
                Theme.category("host")));
        hostN.meta = BrModel.duration(hostPkgs.stream().mapToLong(p -> m.buildMs(p.name())).sum());
        hostN.html = "<html><body><h2>Host tools</h2><p>Programs built to run on this computer while building the image.</p>"
                + list("Packages", hostPkgs.stream().map(p -> p.name() + " " + BrModel.shortVersion(p.version())).toList())
                + "<p><i>Double-click to see them as blocks.</i></p></body></html>";
        if (w != null) hostN.open = () -> w.openPackages(true);

        BrModel.Pkg tc = m.firstWith("toolchain-external-");
        if (tc == null) tc = m.firstWith("toolchain-buildroot");
        if (tc == null) tc = m.pkgs.get("toolchain");
        String libc = m.on("BR2_TOOLCHAIN_EXTERNAL_GLIBC") || m.on("BR2_TOOLCHAIN_BUILDROOT_GLIBC") ? "glibc"
                : m.on("BR2_TOOLCHAIN_EXTERNAL_MUSL") || m.on("BR2_TOOLCHAIN_BUILDROOT_MUSL") ? "musl"
                : m.on("BR2_TOOLCHAIN_EXTERNAL_UCLIBC") || m.on("BR2_TOOLCHAIN_BUILDROOT_UCLIBC") ? "uClibc" : "";
        Graph.Node tcN = g.add(new Graph.Node("toolchain", "TOOLCHAIN",
                tc == null ? "toolchain" : tc.name().replace("toolchain-external-", "") + " " + BrModel.shortVersion(tc.version()),
                Theme.category("toolchain")));
        tcN.meta = libc;
        tcN.html = tc == null ? "" : pkgHtml(m, tc).replace("<h2>", "<h2>Toolchain: ");
        if (w != null && tc != null) { String n = tc.name(); tcN.open = () -> w.openPackage(n); }

        g.link(board, hostN);
        g.link(hostN, tcN);

        List<Graph.Node> toImages = new ArrayList<>();
        BrModel.Pkg ub = m.pkgs.get("uboot");
        if (ub == null) ub = m.firstWith("barebox");
        if (ub == null) ub = m.firstWith("grub2");
        if (ub != null) {
            Graph.Node n = g.add(new Graph.Node("bootloader", "BOOTLOADER",
                    (ub.name().equals("uboot") ? "U-Boot" : ub.name()) + " " + BrModel.shortVersion(ub.version()),
                    Theme.category("bootloader")));
            n.meta = m.cfg("BR2_TARGET_UBOOT_BOARD_DEFCONFIG");
            n.html = pkgHtml(m, ub);
            g.link(tcN, n);
            toImages.add(n);
            if (w != null) { String nm = ub.name(); n.open = () -> w.openPackage(nm); }
        }

        BrModel.Pkg lx = m.pkgs.get("linux");
        if (lx != null) {
            String dts = m.cfg("BR2_LINUX_KERNEL_INTREE_DTS_NAME").trim();
            int ndts = dts.isEmpty() ? 0 : dts.split("\\s+").length;
            Graph.Node n = g.add(new Graph.Node("kernel", "KERNEL", "Linux " + lx.version(), Theme.category("kernel")));
            n.meta = m.cfg("BR2_LINUX_KERNEL_DEFCONFIG") + (ndts > 0 ? " · " + ndts + " dtb" : "");
            n.html = pkgHtml(m, lx).replace("</table>", row("Defconfig", m.cfg("BR2_LINUX_KERNEL_DEFCONFIG"))
                    + row("Device trees", dts.replace(' ', '\n')) + "</table>");
            g.link(tcN, n);
            toImages.add(n);
            if (w != null) n.open = () -> w.openKernel("");
        }

        List<BrModel.Pkg> target = m.pkgs.values().stream()
                .filter(p -> p.type().equals("target") && !p.name().equals("linux") && !p.name().equals("uboot")
                        && !p.name().startsWith("toolchain") && !p.virtual()).toList();
        String init = m.on("BR2_INIT_BUSYBOX") ? "busybox init" : m.on("BR2_INIT_SYSTEMD") ? "systemd"
                : m.on("BR2_INIT_SYSV") ? "sysvinit" : m.on("BR2_INIT_OPENRC") ? "openrc" : "";
        Graph.Node rfs = g.add(new Graph.Node("rootfs", "ROOT FILE SYSTEM", target.size() + " packages",
                Theme.category("rootfs")));
        List<String> fsTypes = m.pkgs.values().stream().filter(p -> p.type().equals("rootfs") && !p.name().equals("rootfs-common"))
                .map(p -> p.name().replace("rootfs-", "")).toList();
        rfs.meta = init;
        rfs.html = "<html><body><h2>Root file system</h2><table>" + row("Host name", m.cfg("BR2_TARGET_GENERIC_HOSTNAME"))
                + row("Init", init) + row("Formats", String.join(", ", fsTypes))
                + row("Size", m.cfg("BR2_TARGET_ROOTFS_EXT2_SIZE")) + row("/dev", m.on("BR2_ROOTFS_DEVICE_CREATION_DYNAMIC_MDEV") ? "mdev"
                : m.on("BR2_ROOTFS_DEVICE_CREATION_DYNAMIC_EUDEV") ? "eudev" : m.on("BR2_INIT_SYSTEMD") ? "udev (systemd)" : "static")
                + "</table>"
                + list("Packages", target.stream().map(p -> p.name() + " " + BrModel.shortVersion(p.version())
                + (m.buildMs(p.name()) > 0 ? "  (" + BrModel.duration(m.buildMs(p.name())) + ")" : "")).toList())
                + "<p><i>Double-click to see them as blocks.</i></p></body></html>";
        if (w != null) rfs.open = () -> w.openPackages(false);
        g.link(tcN, rfs);
        toImages.add(rfs);

        long total = 0;
        List<String> files = new ArrayList<>();
        for (Path p : m.images) {
            try {
                long sz = Files.isRegularFile(p) ? Files.size(p) : 0;
                total += sz;
                files.add(p.getFileName() + (Files.isDirectory(p) ? "/" : "  " + BrModel.size(sz)));
            } catch (IOException ignored) {
            }
        }
        Graph.Node img = g.add(new Graph.Node("images", "IMAGES",
                m.images.isEmpty() ? "not built yet" : m.images.size() + " files", Theme.category("images")));
        img.meta = total > 0 ? BrModel.size(total) : "";
        boolean sd = m.images.stream().anyMatch(p -> p.getFileName().toString().equals("sdcard.img"));
        img.html = "<html><body><h2>Images</h2><table>" + row("Folder", m.out.resolve("images").toString())
                + row("SD card image", sd ? "sdcard.img" : "not there yet (genimage did not finish?)") + "</table>"
                + list("Files", files) + "</body></html>";
        for (Graph.Node n : toImages) g.link(n, img);
        return g;
    }

    static Color kernelColor(String kind) {
        return switch (kind) {
            case "modules" -> Theme.category("toolchain");   // orange
            case "mixed" -> Theme.category("rootfs");        // yellow
            default -> Theme.category("target");             // blue
        };
    }

    /** The last item of a path as the top block, its sub-blocks in a framed grid below it. */
    static Graph itemGraph(List<SourceTree.Item> stack, BuildrootBlocks w) {
        Graph g = new Graph();
        g.keepOrder = true;
        SourceTree.Item cur = stack.get(stack.size() - 1);
        cur.ensure();
        Graph.Node top = g.add(new Graph.Node("@", cur.title, cur.label, cur.color));
        top.meta = cur.meta;
        top.used = cur.used;
        top.hasInput = false;
        top.html = cur.html;
        if (stack.size() > 1) {
            SourceTree.Item up = stack.get(stack.size() - 2);
            if (w != null) top.open = w::back;
            top.html = top.html.replace("</body>", "<p><i>Double-click to go back to "
                    + esc(up.label.isEmpty() ? up.name : up.label) + ".</i></p></body>");
        }
        g.groupParent = top;
        List<SourceTree.Item> kids = cur.children();
        g.maxPerRow = (int) Math.max(6, Math.min(14, Math.round(Math.sqrt(kids.size()) * 1.4)));
        for (SourceTree.Item k : kids) {
            k.ensure();
            Graph.Node n = g.add(new Graph.Node(k.id, k.title, k.label, k.color));
            n.meta = k.meta;
            n.html = k.html;
            n.used = k.used;
            n.dashed = k.dashed;
            n.chips = k.chips();
            n.hasInput = false;
            if (w != null) {
                if (k.container()) n.open = () -> w.enter(k);
                else if (k.file != null) n.open = () -> w.showFile(k.file);
            }
        }
        return g;
    }

    static String kernelDirHtml(KernelModel k, KernelModel.Dir d) {
        List<String> subs = new ArrayList<>();
        for (KernelModel.Dir c : d.children) subs.add(c.name + "/  (" + c.objects + " obj" + (c.modules > 0 ? ", " + c.modules + " ko" : "") + ")");
        List<String> mods = new ArrayList<>();
        collectModules(d, d.path, mods);
        String kind = switch (d.kind()) {
            case "modules" -> "modules only (.ko files, loaded at run time)";
            case "mixed" -> "partly built in, partly modules";
            default -> "built into the kernel image";
        };
        return "<html><body><h2>" + esc(d.path) + "/</h2><table>" + row("Menu", d.prompt) + row("Goes", kind)
                + row("Turned on by", k.symbolText(d, true))
                + row("Objects", d.objects + (d.ownObjects != d.objects ? " (" + d.ownObjects + " in this folder)" : ""))
                + row("Modules", d.modules == 0 ? "" : String.valueOf(d.modules)) + "</table>"
                + list("Subfolders", subs) + list("Modules", mods.size() > 300 ? mods.subList(0, 300) : mods)
                + (d.children.isEmpty() ? "" : "<p><i>Double-click to go in.</i></p>") + "</body></html>";
    }

    private static void collectModules(KernelModel.Dir d, String base, List<String> out) {
        String rel = d.path.equals(base) ? "" : d.path.substring(base.length() + 1) + "/";
        for (String m : d.moduleNames) out.add(rel + m);
        for (KernelModel.Dir c : d.children) collectModules(c, base, out);
    }

    private Graph systemGraphWired(BrModel m) {
        return systemGraph(m, this);
    }

    /** The package diagram with double-click showing each package's .mk file. */
    private Graph packagesGraphWired(BrModel m, boolean host, boolean reduce) {
        Graph g = packagesGraph(m, host, reduce);
        for (Graph.Node n : g.nodes) { BrModel.Pkg p = n.pkg; n.open = () -> showMk(p); }
        return g;
    }

    // ================================================================ start

    public static void main(String[] args) throws Exception {
        String png = null;
        boolean pk = false, host = false;
        String kpath = null, spath = null;
        boolean all = false;
        Path dir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version" -> { System.out.println(NAME + " " + VERSION); return; }
                case "--png" -> png = args[++i];
                case "--packages" -> pk = true;
                case "--host" -> host = true;
                case "--kernel" -> kpath = args[++i];
                case "--sources" -> spath = args[++i];
                case "--all" -> all = true;
                default -> dir = Path.of(args[i]);
            }
        }
        if (png != null) {
            snapshot(dir, png, pk, host, kpath, spath, all);
            return;
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        Path start = dir;
        if (start == null && !PREFS.get("folder", "").isEmpty()) start = Path.of(PREFS.get("folder", ""));
        Path first = start;
        SwingUtilities.invokeLater(() -> {
            Theme.apply(PREFS.getBoolean("dark", false));
            BuildrootBlocks f = new BuildrootBlocks();
            f.setVisible(true);
            if (first != null && Files.isDirectory(first)) f.load(first, true);
        });
    }

    /** Renders a diagram to a PNG without a window (for docs and checks). */
    private static void snapshot(Path dir, String png, boolean packages, boolean host, String kpath, String spath,
                                 boolean all) throws Exception {
        BrModel m = BrModel.load(dir == null ? Path.of(".") : dir, true, s -> System.err.println(s));
        BlockCanvas c = new BlockCanvas();
        c.setSize(1800, 1100);
        if (kpath != null) {
            Path kd = KernelModel.find(m);
            if (kd == null) throw new IOException("no kernel build");
            SourceTree t = SourceTree.kernel(KernelModel.load(kd, m.pkgs.get("linux").version()), !all);
            c.setGraph(itemGraph(t.path(kpath, "/"), null));
        } else if (spath != null) {
            c.setGraph(itemGraph(SourceTree.buildroot(m, !all).path(spath, ">"), null));
        } else {
            c.setGraph(packages ? packagesGraph(m, host, true) : systemGraph(m));
        }
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(png));
        System.err.println("wrote " + png + " (" + c.graph().nodes.size() + " blocks, " + c.graph().edges.size() + " wires)");
    }
}
