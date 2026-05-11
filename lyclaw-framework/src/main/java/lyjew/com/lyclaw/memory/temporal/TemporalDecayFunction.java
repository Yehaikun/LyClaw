package lyjew.com.lyclaw.memory.temporal;

public interface TemporalDecayFunction {

    double compute(long daysSinceCreation, double baseDecayFactor);
    String getName();
}
