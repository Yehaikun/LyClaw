package lyjew.com.lyclaw.orchestration.pipeline;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultChainTest {

    private List<PipelineStage> stages;
    @Mock private ChatContext context;

    @BeforeEach
    void setUp() {
        stages = new ArrayList<>();
        lenient().when(context.getAttribute(any())).thenReturn(null);
    }

    @Test
    void shouldProcessAllStagesInOrder() {
        AtomicInteger callOrder = new AtomicInteger(0);
        List<Integer> order = new ArrayList<>();

        stages.add(MockStage("S1", 1, chain -> {
            order.add(callOrder.incrementAndGet());
            chain.next(context);
        }));
        stages.add(MockStage("S2", 2, chain -> {
            order.add(callOrder.incrementAndGet());
            chain.next(context);
        }));
        stages.add(MockStage("S3", 3, chain -> {
            order.add(callOrder.incrementAndGet());
            chain.next(context);
        }));

        DefaultChain chain = new DefaultChain(stages, 0);
        chain.proceed(context);

        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    void shouldSkipUnsupportedStages() {
        AtomicInteger callOrder = new AtomicInteger(0);
        List<String> processedStages = new ArrayList<>();

        PipelineStage stage1 = new PipelineStage() {
            @Override public String getStageName() { return "S1"; }
            @Override public int getOrder() { return 1; }
            @Override public boolean supports(ChatContext ctx) { return true; }
            @Override public void process(ChatContext ctx, Chain chain) {
                processedStages.add(getStageName());
                chain.next(ctx);
            }
        };

        PipelineStage stage2 = new PipelineStage() {
            @Override public String getStageName() { return "S2"; }
            @Override public int getOrder() { return 2; }
            @Override public boolean supports(ChatContext ctx) { return false; } // SKIP
            @Override public void process(ChatContext ctx, Chain chain) {
                processedStages.add(getStageName());
                chain.next(ctx);
            }
        };

        PipelineStage stage3 = new PipelineStage() {
            @Override public String getStageName() { return "S3"; }
            @Override public int getOrder() { return 3; }
            @Override public boolean supports(ChatContext ctx) { return true; }
            @Override public void process(ChatContext ctx, Chain chain) {
                processedStages.add(getStageName());
                chain.next(ctx);
            }
        };

        stages.add(stage1);
        stages.add(stage2);
        stages.add(stage3);

        DefaultChain chain = new DefaultChain(stages, 0);
        chain.proceed(context);

        assertThat(processedStages).containsExactly("S1", "S3");
    }

    @Test
    void shouldBreakChainAndStopProcessing() {
        List<String> processedStages = new ArrayList<>();

        stages.add(breakableStage("S1", processedStages, false));
        stages.add(breakableStage("S2", processedStages, true)); // breaks here
        stages.add(breakableStage("S3", processedStages, false)); // should NOT be called

        DefaultChain chain = new DefaultChain(stages, 0);
        chain.proceed(context);

        assertThat(processedStages).containsExactly("S1", "S2");
        assertThat(chain.isBroken()).isTrue();
    }

    @Test
    void shouldSetBreakReasonFromContextAttribute() {
        when(context.getAttribute("__chain_break_reason__")).thenReturn("Security denied");

        stages.add(breakableStage("S1", new ArrayList<>(), true));

        DefaultChain chain = new DefaultChain(stages, 0);
        chain.proceed(context);

        assertThat(chain.isBroken()).isTrue();
        assertThat(chain.getBreakReason()).isEqualTo("Security denied");
    }

    @Test
    void shouldSupportBreakWithExplicitReason() {
        List<String> processed = new ArrayList<>();
        stages.add(new PipelineStage() {
            @Override public String getStageName() { return "S1"; }
            @Override public int getOrder() { return 1; }
            @Override public void process(ChatContext ctx, Chain chain) {
                ((DefaultChain) chain).breakChain(ctx, "Custom break reason");
            }
        });

        DefaultChain chain = new DefaultChain(stages, 0);
        chain.proceed(context);

        assertThat(chain.isBroken()).isTrue();
        assertThat(chain.getBreakReason()).isEqualTo("Custom break reason");
    }

    @Test
    void callingNextOnBrokenChainShouldThrow() {
        DefaultChain chain = new DefaultChain(stages, 0);
        chain.breakChain(context, "test break");

        assertThatThrownBy(() -> chain.next(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void shouldTrackCurrentStageIndex() {
        List<String> processed = new ArrayList<>();
        stages.add(nonBreakingStage("S1", processed));
        stages.add(nonBreakingStage("S2", processed));

        DefaultChain chain = new DefaultChain(stages, 0);

        // before processing, index should be 0 (currentIndex starts at 0,
        // getCurrentStage returns currentIndex - 1)
        chain.proceed(context);

        // after processing all 2 stages, currentIndex = 2, so getCurrentStage = 1
        assertThat(chain.getCurrentStage()).isEqualTo(1);
    }

    @Test
    void shouldHandleEmptyStageList() {
        DefaultChain chain = new DefaultChain(new ArrayList<>(), 0);
        chain.proceed(context);
        assertThat(chain.isBroken()).isFalse();
    }

    // -- helpers --

    private PipelineStage breakableStage(String name, List<String> tracker, boolean shouldBreak) {
        return new PipelineStage() {
            @Override public String getStageName() { return name; }
            @Override public int getOrder() { return 1; }
            @Override public void process(ChatContext ctx, Chain chain) {
                tracker.add(name);
                if (shouldBreak) {
                    chain.breakChain(ctx);
                } else {
                    chain.next(ctx);
                }
            }
        };
    }

    private PipelineStage nonBreakingStage(String name, List<String> tracker) {
        return breakableStage(name, tracker, false);
    }

    /** Minimal mock-stage helper for simpler tests */
    interface StageAction { void execute(Chain chain); }

    static PipelineStage MockStage(String name, int order, StageAction action) {
        return new PipelineStage() {
            @Override public String getStageName() { return name; }
            @Override public int getOrder() { return order; }
            @Override public boolean supports(ChatContext ctx) { return true; }
            @Override public void process(ChatContext ctx, Chain chain) {
                action.execute(chain);
            }
        };
    }
}
