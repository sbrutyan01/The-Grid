package brblocks;

import java.awt.Color;
import java.util.*;

/** Blocks and wires, with a layered top-to-bottom layout (what is built first sits on top). */
final class Graph {

    static final class Node {
        final String id;
        String title;            // small uppercase line, like EVIA's block type
        String label;            // the main line (monospace)
        String meta = "";        // small text on the right of the title (build time)
        Color color;
        boolean dashed;          // virtual packages
        boolean hasInput = true;
        String html = "";        // details panel
        Runnable open;           // double-click
        BrModel.Pkg pkg;
        double x, y;             // body top-left, diagram units
        int w = Block.MIN_W;
        int level, order;

        Node(String id, String title, String label, Color color) {
            this.id = id;
            this.title = title;
            this.label = label;
            this.color = color;
        }
    }

    record Edge(Node from, Node to) {}

    final List<Node> nodes = new ArrayList<>();
    int maxPerRow = Integer.MAX_VALUE;   // longer rows wrap onto more lines
    boolean keepOrder;                   // keep the order nodes were added in (no crossing reduction)
    Node groupParent;                    // set: every other node sits in one framed group under this node,
                                         // fed by a single wire, instead of one wire per node
    final List<Edge> edges = new ArrayList<>();
    private final Map<String, Node> byId = new HashMap<>();

    Node add(Node n) {
        nodes.add(n);
        byId.put(n.id, n);
        return n;
    }

    Node get(String id) {
        return byId.get(id);
    }

    void link(Node a, Node b) {
        if (a != null && b != null && a != b) edges.add(new Edge(a, b));
    }

    List<Node> preds(Node n) {
        List<Node> r = new ArrayList<>();
        for (Edge e : edges) if (e.to() == n) r.add(e.from());
        return r;
    }

    List<Node> succs(Node n) {
        List<Node> r = new ArrayList<>();
        for (Edge e : edges) if (e.from() == n) r.add(e.to());
        return r;
    }

    boolean hasOutput(Node n) {
        for (Edge e : edges) if (e.from() == n) return true;
        return false;
    }

    /** Everything reachable from n, following the wires forward (down) or backward (up). */
    Set<Node> reach(Node n, boolean down) {
        Map<Node, List<Node>> adj = new HashMap<>();
        for (Edge e : edges) {
            Node a = down ? e.from() : e.to(), b = down ? e.to() : e.from();
            adj.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
        }
        Set<Node> seen = new LinkedHashSet<>();
        Deque<Node> st = new ArrayDeque<>(List.of(n));
        while (!st.isEmpty()) {
            for (Node m : adj.getOrDefault(st.pop(), List.of())) if (seen.add(m)) st.push(m);
        }
        seen.remove(n);
        return seen;
    }

    /** Drops every wire a→c that is also reached through a longer path a→b→…→c. */
    void reduce() {
        Map<Node, Set<Node>> succ = new HashMap<>();
        for (Edge e : edges) succ.computeIfAbsent(e.from(), k -> new LinkedHashSet<>()).add(e.to());
        List<Edge> keep = new ArrayList<>();
        for (Edge e : edges) {
            boolean indirect = false;
            for (Node mid : succ.getOrDefault(e.from(), Set.of())) {
                if (mid != e.to() && reachable(succ, mid, e.to())) { indirect = true; break; }
            }
            if (!indirect) keep.add(e);
        }
        edges.clear();
        edges.addAll(keep);
    }

    private static boolean reachable(Map<Node, Set<Node>> succ, Node from, Node to) {
        Deque<Node> st = new ArrayDeque<>(List.of(from));
        Set<Node> seen = new HashSet<>();
        while (!st.isEmpty()) {
            Node n = st.pop();
            if (n == to) return true;
            if (seen.add(n)) st.addAll(succ.getOrDefault(n, Set.of()));
        }
        return false;
    }

    // ================================================================ layout

    static final int GAP_X = 34, GAP_Y = 74;

    /** Levels by longest path, order within a level by barycenter sweeps, rows centered. */
    void layout() {
        Map<Node, List<Node>> pr = new HashMap<>(), su = new HashMap<>();
        for (Edge e : edges) {
            pr.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
            su.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        // longest path from the sources (iterative; a cycle just stops growing after n rounds)
        for (Node n : nodes) n.level = 0;
        for (int round = 0; round < nodes.size(); round++) {
            boolean changed = false;
            for (Edge e : edges) {
                if (e.to().level < e.from().level + 1 && e.from().level + 1 <= nodes.size()) {
                    e.to().level = e.from().level + 1;
                    changed = true;
                }
            }
            if (!changed) break;
        }
        // pull sources down next to their first user, so they don't all crowd the top row
        for (Node n : nodes) {
            if (pr.containsKey(n) || !su.containsKey(n)) continue;
            int min = Integer.MAX_VALUE;
            for (Node s : su.get(n)) min = Math.min(min, s.level);
            n.level = Math.max(0, min - 1);
        }
        if (groupParent != null) for (Node n : nodes) n.level = n == groupParent ? 0 : 1;
        TreeMap<Integer, List<Node>> rows = new TreeMap<>();
        List<Node> sorted = new ArrayList<>(nodes);
        if (!keepOrder) sorted.sort(Comparator.comparing(n -> n.id));
        for (Node n : sorted) rows.computeIfAbsent(n.level, k -> new ArrayList<>()).add(n);
        renumber(rows);
        for (int sweep = 0; sweep < (keepOrder ? 0 : 6); sweep++) {
            boolean downward = sweep % 2 == 0;
            List<Integer> keys = new ArrayList<>(rows.keySet());
            if (!downward) Collections.reverse(keys);
            for (int k : keys) {
                List<Node> row = rows.get(k);
                Map<Node, Double> bary = new HashMap<>();
                for (Node n : row) {
                    List<Node> nb = (downward ? pr : su).getOrDefault(n, List.of());
                    bary.put(n, nb.isEmpty() ? n.order : nb.stream().mapToDouble(m -> m.order).average().orElse(0));
                }
                row.sort(Comparator.comparingDouble(bary::get));
                for (int i = 0; i < row.size(); i++) row.get(i).order = i;
            }
        }
        double y = 0;
        if (groupParent != null && rows.size() == 2) {   // a group: aligned columns
            List<Node> top = rows.firstEntry().getValue(), kids = rows.lastEntry().getValue();
            int cols = Math.min(maxPerRow, kids.size());
            double[] colW = new double[cols];
            for (int i = 0; i < kids.size(); i++) colW[i % cols] = Math.max(colW[i % cols], kids.get(i).w + Block.PAD * 2);
            double total = -GAP_X;
            for (double cw : colW) total += cw + GAP_X;
            for (Node t : top) { t.x = -(t.w + Block.PAD * 2) / 2.0; t.y = 0; }
            for (int i = 0; i < kids.size(); i++) {
                Node n = kids.get(i);
                double x = -total / 2;
                for (int c = 0; c < i % cols; c++) x += colW[c] + GAP_X;
                n.w = (int) colW[i % cols] - Block.PAD * 2;   // same width down a column
                n.x = x;
                n.y = Block.H + GAP_Y + (i / cols) * (Block.H + GAP_X);
            }
            return;
        }
        for (List<Node> whole : rows.values()) for (int st = 0; st < whole.size(); st += maxPerRow) {
            List<Node> row = whole.subList(st, Math.min(whole.size(), st + maxPerRow));
            if (st > 0 && groupParent != null) y -= GAP_Y - GAP_X;   // wrapped rows of a group sit closer
            double width = -GAP_X;
            for (Node n : row) width += n.w + Block.PAD * 2 + GAP_X;
            double x = -width / 2;
            for (Node n : row) {
                n.x = x;
                n.y = y;
                x += n.w + Block.PAD * 2 + GAP_X;
            }
            y += Block.H + GAP_Y;
        }
    }

    private static void renumber(TreeMap<Integer, List<Node>> rows) {
        for (List<Node> row : rows.values()) for (int i = 0; i < row.size(); i++) row.get(i).order = i;
    }
}
