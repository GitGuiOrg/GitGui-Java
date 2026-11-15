package com.github.gitguiorg.gitgui.model;

import com.github.gitguiorg.gitgui.model.GraphRow.LaneRef;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental lane assignment for a topologically ordered commit list (newest first).
 * Feed pages in order with {@link #append(List)}; state carries across pages.
 */
public final class GraphLayout {
    private record Lane(String hash, int color) {}

    private final List<Lane> lanes = new ArrayList<>();
    private int nextColor;

    public List<GraphRow> append(List<Commit> commits) {
        List<GraphRow> rows = new ArrayList<>(commits.size());
        for (Commit c : commits) rows.add(place(c));
        return rows;
    }

    private GraphRow place(Commit c) {
        int before = lanes.size();
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < lanes.size(); i++) {
            Lane l = lanes.get(i);
            if (l != null && l.hash().equals(c.hash())) matches.add(i);
        }

        int node;
        int color;
        if (matches.isEmpty()) {
            node = firstFree();
            color = nextColor++;
        } else {
            node = matches.get(0);
            color = lanes.get(node).color();
        }

        List<LaneRef> pass = new ArrayList<>();
        for (int i = 0; i < lanes.size(); i++) {
            Lane l = lanes.get(i);
            if (l != null && !matches.contains(i)) pass.add(new LaneRef(i, l.color()));
        }
        List<LaneRef> converging = new ArrayList<>();
        for (int i : matches) converging.add(new LaneRef(i, lanes.get(i).color()));
        for (int i : matches) lanes.set(i, null);

        List<LaneRef> parents = new ArrayList<>();
        for (int p = 0; p < c.parents().size(); p++) {
            String ph = c.parents().get(p);
            int existing = laneOf(ph);
            if (existing >= 0) {
                parents.add(new LaneRef(existing, lanes.get(existing).color()));
            } else if (p == 0) {
                set(node, new Lane(ph, color));
                parents.add(new LaneRef(node, color));
            } else {
                int j = firstFree();
                int col = nextColor++;
                set(j, new Lane(ph, col));
                parents.add(new LaneRef(j, col));
            }
        }

        int count = Math.max(Math.max(before, node + 1), lanes.size());
        for (LaneRef r : parents) count = Math.max(count, r.lane() + 1);
        while (!lanes.isEmpty() && lanes.get(lanes.size() - 1) == null) lanes.remove(lanes.size() - 1);
        return new GraphRow(node, color, count, List.copyOf(pass), List.copyOf(converging), List.copyOf(parents));
    }

    private int laneOf(String hash) {
        for (int i = 0; i < lanes.size(); i++) {
            Lane l = lanes.get(i);
            if (l != null && l.hash().equals(hash)) return i;
        }
        return -1;
    }

    private int firstFree() {
        for (int i = 0; i < lanes.size(); i++) if (lanes.get(i) == null) return i;
        return lanes.size();
    }

    private void set(int idx, Lane lane) {
        while (lanes.size() <= idx) lanes.add(null);
        lanes.set(idx, lane);
    }
}
