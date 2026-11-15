package com.github.gitguiorg.gitgui.ui.view;

import com.github.gitguiorg.gitgui.model.GraphRow;
import com.github.gitguiorg.gitgui.model.GraphRow.LaneRef;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/** Draws one {@link GraphRow} (lanes, merge curves and the commit node). */
final class GraphCanvas extends Canvas {
    static final double LANE_W = 14;
    static final double PAD = 10;
    private static final Color[] PALETTE = {
            Color.web("#4fc3f7"), Color.web("#ba68c8"), Color.web("#81c784"), Color.web("#ffb74d"),
            Color.web("#e57373"), Color.web("#4db6ac"), Color.web("#f06292"), Color.web("#aed581"),
            Color.web("#7986cb"), Color.web("#ffd54f")};

    static double widthFor(int lanes) {
        return PAD * 2 + Math.max(0, lanes - 1) * LANE_W;
    }

    private static double x(int lane) {
        return PAD + Math.min(lane, 11) * LANE_W;
    }

    private static Color color(int idx) {
        return PALETTE[Math.floorMod(idx, PALETTE.length)];
    }

    void draw(GraphRow row, int lanes, double height, boolean merge, int stack) {
        setWidth(widthFor(lanes));
        setHeight(height);
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), height);
        g.setLineWidth(2);
        double mid = height / 2;
        double nx = x(row.nodeLane());

        for (LaneRef r : row.passThrough()) {
            g.setStroke(color(r.color()));
            g.strokeLine(x(r.lane()), 0, x(r.lane()), height);
        }
        for (LaneRef r : row.converging()) {
            g.setStroke(color(r.color()));
            curve(g, x(r.lane()), 0, nx, mid);
        }
        for (LaneRef r : row.parents()) {
            g.setStroke(color(r.color()));
            curve(g, nx, mid, x(r.lane()), height);
        }

        Color nc = color(row.nodeColor());
        g.setFill(merge ? Color.web("#0d1117") : nc);
        g.setStroke(nc);
        g.setLineWidth(2);
        g.fillOval(nx - 4.5, mid - 4.5, 9, 9);
        g.strokeOval(nx - 4.5, mid - 4.5, 9, 9);

        if (stack > 0) {
            Color sc = Color.web(CommitCell.stackColor(stack));
            g.setFill(Color.TRANSPARENT);
            g.setStroke(sc);
            g.setLineWidth(3);
            g.strokeOval(nx - 6.5, mid - 6.5, 13, 13);
        }
    }

    private static void curve(GraphicsContext g, double x1, double y1, double x2, double y2) {
        if (x1 == x2) {
            g.strokeLine(x1, y1, x2, y2);
            return;
        }
        double dy = (y2 - y1) * 0.6;
        g.beginPath();
        g.moveTo(x1, y1);
        g.bezierCurveTo(x1, y1 + dy, x2, y2 - dy, x2, y2);
        g.stroke();
    }
}
