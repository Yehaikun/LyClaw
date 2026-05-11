package lyjew.com.lyclaw.orchestration.pipeline;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineBuilderTest {

    private List<PipelineStage> stages;

    @BeforeEach
    void setUp() {
        stages = new ArrayList<>();
    }

    static class StubStage implements PipelineStage {
        private final String name;
        private final int order;
        private boolean supports = true;
        private boolean processed = false;

        StubStage(String name, int order) {
            this.name = name;
            this.order = order;
        }

        StubStage(String name, int order, boolean supports) {
            this(name, order);
            this.supports = supports;
        }

        @Override public String getStageName() { return name; }
        @Override public int getOrder() { return order; }

        @Override
        public void process(ChatContext context, Chain chain) {
            processed = true;
            chain.next(context);
        }

        boolean isProcessed() { return processed; }
    }

    @Test
    void shouldAutoDiscoverAndBuildPipeline() {
        // given: unsorted stages
        stages.add(new StubStage("StageC", 3));
        stages.add(new StubStage("StageA", 1));
        stages.add(new StubStage("StageB", 2));

        PipelineBuilder builder = new PipelineBuilder(stages, List.of());

        // then: stages should be sorted by order
        List<PipelineStage> sorted = builder.getStages();
        assertThat(sorted).hasSize(3);
        assertThat(sorted.get(0).getOrder()).isEqualTo(1);
        assertThat(sorted.get(1).getOrder()).isEqualTo(2);
        assertThat(sorted.get(2).getOrder()).isEqualTo(3);
    }

    @Test
    void shouldBuildPipelineWithCorrectStageCount() {
        stages.add(new StubStage("A", 0));
        stages.add(new StubStage("B", 1));
        PipelineBuilder builder = new PipelineBuilder(stages, List.of());

        Pipeline pipeline = builder.build();

        assertThat(pipeline).isNotNull();
        assertThat(pipeline.getStages()).hasSize(2);
    }

    @Test
    void shouldReturnStageCount() {
        stages.add(new StubStage("A", 0));
        stages.add(new StubStage("B", 1));
        PipelineBuilder builder = new PipelineBuilder(stages, List.of());

        assertThat(builder.getStageCount()).isEqualTo(2);
    }

    @Test
    void shouldRebuildPipelineWithFreshSortedStages() {
        stages.add(new StubStage("B", 2));
        stages.add(new StubStage("A", 1));
        PipelineBuilder builder = new PipelineBuilder(stages, List.of());

        Pipeline first = builder.build();
        Pipeline second = builder.rebuild();

        // Both should have sorted stages
        assertThat(second.getStages().get(0).getOrder()).isEqualTo(1);
        assertThat(second.getStages().get(1).getOrder()).isEqualTo(2);
    }

    @Test
    void shouldHandleEmptyStagesList() {
        PipelineBuilder builder = new PipelineBuilder(new ArrayList<>(), List.of());

        assertThat(builder.getStageCount()).isEqualTo(0);
        assertThat(builder.build().getStages()).isEmpty();
    }

    @Test
    void shouldReturnDefensiveCopyOfStages() {
        stages.add(new StubStage("A", 0));
        PipelineBuilder builder = new PipelineBuilder(stages, List.of());

        List<PipelineStage> returned = builder.getStages();
        returned.clear(); // should not affect internal list

        assertThat(builder.getStageCount()).isEqualTo(1);
    }
}
