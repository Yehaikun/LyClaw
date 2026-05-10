package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.ExponentialDecayFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialDecayFunctionTest {

    private ExponentialDecayFunction decayFunction;

    @BeforeEach
    void setUp() {
        decayFunction = new ExponentialDecayFunction();
    }

    @Test
    @DisplayName("Exponential decay with positive values should compute correctly")
    void computePositiveValues() {
        double result = decayFunction.compute(10, 0.1);
        double expected = Math.exp(-0.1 * 10); // exp(-1) ≈ 0.3679
        assertEquals(expected, result, 1e-6);
    }

    @Test
    @DisplayName("Exponential decay at day 0 should be 1.0")
    void computeZeroDays() {
        double result = decayFunction.compute(0, 0.1);
        assertEquals(1.0, result, 1e-10);
    }

    @Test
    @DisplayName("Exponential decay with zero decay factor should be 1.0")
    void computeZeroDecay() {
        double result = decayFunction.compute(100, 0.0);
        assertEquals(1.0, result, 1e-10);
    }

    @Test
    @DisplayName("Exponential decay with negative days should be clamped to 0")
    void computeNegativeDays() {
        double result = decayFunction.compute(-5, 0.1);
        assertEquals(1.0, result, 1e-10); // days clamped to 0 → exp(0) = 1
    }

    @Test
    @DisplayName("Exponential decay with negative decay factor should be clamped to 0")
    void computeNegativeDecay() {
        double result = decayFunction.compute(10, -0.1);
        assertEquals(1.0, result, 1e-10); // factor clamped to 0 → exp(0) = 1
    }

    @Test
    @DisplayName("Exponential decay with very high decay factor should approach 0 quickly")
    void computeHighDecay() {
        double result = decayFunction.compute(10, 1.0);
        assertTrue(result < 0.001);
    }

    @Test
    @DisplayName("getName should return 'exponential'")
    void getName() {
        assertEquals("exponential", decayFunction.getName());
    }

    @Test
    @DisplayName("Exponential decay should be monotonically decreasing")
    void monotonicDecrease() {
        double r1 = decayFunction.compute(5, 0.1);
        double r2 = decayFunction.compute(10, 0.1);
        double r3 = decayFunction.compute(15, 0.1);
        assertTrue(r1 > r2);
        assertTrue(r2 > r3);
    }
}
