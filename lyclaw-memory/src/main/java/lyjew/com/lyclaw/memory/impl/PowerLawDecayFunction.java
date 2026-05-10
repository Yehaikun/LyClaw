package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.temporal.TemporalDecayFunction;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PowerLawDecayFunction implements TemporalDecayFunction {

    @Override
    public double compute(long daysSinceCreation, double baseDecayFactor) {
        if (daysSinceCreation < 0) {
            log.warn("Negative daysSinceCreation ({}), clamping to 0", daysSinceCreation);
            daysSinceCreation = 0;
        }
        if (baseDecayFactor < 0.0) {
            log.warn("Negative baseDecayFactor ({}), clamping to 0", baseDecayFactor);
            baseDecayFactor = 0.0;
        }
        return Math.pow(1.0 + daysSinceCreation, -baseDecayFactor);
    }

    @Override
    public String getName() { return "power-law"; }
}
