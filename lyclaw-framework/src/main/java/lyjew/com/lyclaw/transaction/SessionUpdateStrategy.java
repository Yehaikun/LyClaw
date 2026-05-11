package lyjew.com.lyclaw.transaction;

import java.util.List;

public interface SessionUpdateStrategy {

    List<SessionUpdate> merge(List<SessionUpdate> existing, SessionUpdate newUpdate);
    String getStrategyName();
}
