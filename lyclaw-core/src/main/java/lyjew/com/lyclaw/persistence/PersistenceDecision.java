package lyjew.com.lyclaw.persistence;

import java.util.Objects;

public final class PersistenceDecision {

    private final PersistenceSignal signal;
    private final String reason;

    private PersistenceDecision(PersistenceSignal signal, String reason) {
        this.signal = Objects.requireNonNull(signal, "signal must not be null");
        this.reason = reason;
    }

    public static PersistenceDecision write(String reason) {
        return new PersistenceDecision(PersistenceSignal.WRITE, reason);
    }

    public static PersistenceDecision defer(String reason) {
        return new PersistenceDecision(PersistenceSignal.DEFER, reason);
    }

    public static PersistenceDecision skip(String reason) {
        return new PersistenceDecision(PersistenceSignal.SKIP, reason);
    }

    public boolean shouldWrite() { return signal == PersistenceSignal.WRITE; }

    public PersistenceSignal signal() { return signal; }

    public String reason() { return reason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersistenceDecision that)) return false;
        return signal == that.signal && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() { return Objects.hash(signal, reason); }

    @Override
    public String toString() {
        return "PersistenceDecision{" + signal + (reason != null && !reason.isEmpty() ? ", reason='" + reason + '\'' : "") + '}';
    }
}
