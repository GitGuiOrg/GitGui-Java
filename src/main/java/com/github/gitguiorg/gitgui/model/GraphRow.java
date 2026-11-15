package com.github.gitguiorg.gitgui.model;

import java.util.List;

/**
 * Drawing instructions for one commit row of the history graph. Lanes are 0-based columns.
 * <ul>
 *   <li>{@code passThrough}: vertical lines crossing the whole row</li>
 *   <li>{@code converging}: lines from the top edge into the commit node (lanes that were waiting for this commit)</li>
 *   <li>{@code parents}: lines from the node down to the bottom edge, one per parent</li>
 * </ul>
 */
public record GraphRow(int nodeLane, int nodeColor, int laneCount,
                       List<LaneRef> passThrough, List<LaneRef> converging, List<LaneRef> parents) {
    public record LaneRef(int lane, int color) {}
}
