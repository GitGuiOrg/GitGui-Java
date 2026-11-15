package com.github.gitguiorg.gitgui.ui.view;

import java.util.List;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** What is being dragged inside the GitButler panel, and the drop indicator styling. */
final class ButDnd {
    enum Kind { COMMIT, FILE, BRANCH }

    /** {@code source}: for a commit drag, the branch it comes from (null when unknown or several). */
    record Payload(Kind kind, List<String> ids, String source) {
        Payload(Kind kind, List<String> ids) { this(kind, ids, null); }
    }

    private static Payload current;

    private ButDnd() {}

    /** The payload of the drag in progress, or null when the drag did not start in this panel. */
    static Payload current() { return current; }

    /** Starts a drag of {@code payload} from {@code source}, showing a snapshot of the node under the pointer. */
    static void start(Node source, MouseEvent e, Payload payload) {
        current = payload;
        Dragboard db = source.startDragAndDrop(TransferMode.MOVE);
        ClipboardContent content = new ClipboardContent();
        content.putString(payload.kind() + ":" + String.join("\n", payload.ids()));
        db.setContent(content);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        javafx.scene.image.WritableImage image = source.snapshot(params, null);
        db.setDragView(image, Math.min(e.getX(), image.getWidth() - 1), Math.min(e.getY(), image.getHeight() - 1));
        e.consume();
    }

    static void finish() { current = null; }

    /** Accepts the drag when the payload is one of {@code kinds}. */
    static boolean accept(DragEvent e, Kind... kinds) {
        Payload p = current;
        if (p == null) return false;
        for (Kind k : kinds) {
            if (p.kind() == k) {
                e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
                return true;
            }
        }
        return false;
    }

    /** Drop indicator: a coloured line on one side of the node, or a frame around it. */
    static void mark(Region node, String color, Side side) {
        Color c = Color.web(color);
        BorderStroke stroke = switch (side) {
            case TOP -> new BorderStroke(c, BorderStrokeStyle.SOLID, new CornerRadii(0), new BorderWidths(2, 0, 0, 0));
            case BOTTOM -> new BorderStroke(c, BorderStrokeStyle.SOLID, new CornerRadii(0), new BorderWidths(0, 0, 2, 0));
            case ALL -> new BorderStroke(c, BorderStrokeStyle.SOLID, new CornerRadii(6), new BorderWidths(2));
        };
        node.setBorder(new Border(stroke));
    }

    static void unmark(Region node) { node.setBorder(Border.EMPTY); }

    enum Side { TOP, BOTTOM, ALL }
}
