package com.github.gitguiorg.gitgui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.gitguiorg.gitgui.model.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphLayoutTest {
    static Commit c(String hash, String... parents) {
        return new Commit(hash, List.of(parents), "a", "a@x", Instant.EPOCH, List.of(), hash, "", "N");
    }

    @Test
    void linearHistoryStaysInOneLane() {
        List<GraphRow> rows = new GraphLayout().append(List.of(c("c3", "c2"), c("c2", "c1"), c("c1")));
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.nodeLane()).isZero();
            assertThat(r.laneCount()).isEqualTo(1);
        });
        assertThat(rows.get(2).parents()).isEmpty();
        assertThat(rows.get(1).converging()).hasSize(1);
    }

    @Test
    void mergeUsesSecondLaneAndConverges() {
        List<GraphRow> r = new GraphLayout().append(List.of(c("M", "A", "B"), c("A", "R"), c("B", "R"), c("R")));
        assertThat(r.get(0).parents()).extracting(GraphRow.LaneRef::lane).containsExactly(0, 1);
        assertThat(r.get(1).nodeLane()).isZero();
        assertThat(r.get(1).passThrough()).extracting(GraphRow.LaneRef::lane).containsExactly(1);
        assertThat(r.get(2).nodeLane()).isEqualTo(1);
        assertThat(r.get(2).parents()).extracting(GraphRow.LaneRef::lane).containsExactly(0);
        assertThat(r.get(3).nodeLane()).isZero();
        assertThat(r.get(3).laneCount()).isEqualTo(1);
        assertThat(r.get(0).parents().get(0).color()).isNotEqualTo(r.get(0).parents().get(1).color());
    }

    @Test
    void parallelBranchTipsGetSeparateLanes() {
        List<GraphRow> r = new GraphLayout().append(List.of(c("T1", "R"), c("T2", "R"), c("R")));
        assertThat(r.get(0).nodeLane()).isZero();
        assertThat(r.get(1).nodeLane()).isEqualTo(1);
        assertThat(r.get(2).converging()).hasSize(1);
    }

    @Test
    void stateCarriesAcrossPages() {
        GraphLayout l = new GraphLayout();
        l.append(List.of(c("M", "A", "B")));
        List<GraphRow> second = l.append(List.of(c("A", "R"), c("B", "R")));
        assertThat(second.get(0).nodeLane()).isZero();
        assertThat(second.get(1).nodeLane()).isEqualTo(1);
    }
}