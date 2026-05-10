package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.memory.impl.PowerLawDecayFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PowerLawDecayFunctionTest {

    private PowerLawDecayFunction decayFunction;

    @BeforeEach
    void setUp() {
        decayFunction = new PowerLawDecayFunction();
    }

    @Test
    @DisplayName("Power law decay with positive values should compute correctly")
    void computePositiveValues() {
        double result = decayFunction.compute(10, 1.0);
        double expected = Math.pow(1.0 + 10, -1.0); // 1/11 ≈ 0.0909
        assertEquals(expected, result, 1e-6);
    }

    @Test
    @DisplayName("Power law at day 0 should be 1.0")
    void computeZeroDays() {
        double result = decayFunction.compute(0, 2.0);
        assertEquals(1.0, result, 1e-10);
    }

    @Test
    @DisplayName("Power law with zero decay factor should be 1.0")
    void computeZeroDecay() {
        double result = decayFunction.compute(100, 0.0);
        assertEquals(1.0, result, 1e-10);
    }

    @Test
    @DisplayName("Power law with negative days should be clamped to 0")
    void computeNegativeDays() {
        double result = decayFunction.compute(-5, 1.0);
        assertEquals(1.0, result, 1e-10);
    }

    @Test
    @DisplayName("Power law with negative decay factor should be clamped to 0")
    void computeNegativeDecay() {
        double result = decayFunction.compute(10, -0.5);
        assertEquals(1.0, result, 1e-10);
    }

    @Test
    @DisplayName("getName should return 'power-law'")
    void getName() {
        assertEquals("power-law", decayFunction.getName());
    }

    @Test
    @DisplayName("Power law decay should be monotonically decreasing")
    void monotonicDecrease() {
        double r1 = decayFunction.compute(5, 0.5);
        double r2 = decayFunction.compute(10, 0.5);
        double r3 = decayFunction.compute(20, 0.5);
        assertTrue(r1 > r2);
        assertTrue(r2 > r3);
    }

    @Test
    @DisplayName("Power law with different exponents")
    void differentExponents() {
        // Higher exponent → faster decay
        double rExpo1 = decayFunction.compute(10, 1.0);
        double rExpo2 = decayFunction.compute(10, 2.0);
        assertTrue(rExpo1 > rExpo2);
    }
}
