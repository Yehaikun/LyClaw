package lyjew.com.lyclaw.event;

import java.time.Instant;
import java.util.UUID;

public class Event {

    private final String eventId;
    private final Instant timestamp;
    private final String source;
    private final String eventType;

    public Event(String source, String eventType) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.source = source;
        this.eventType = eventType;
    }

    public String getEventId() { return eventId; }

    public Instant getTimestamp() { return timestamp; }

    public String getSource() { return source; }

    public String getEventType() { return eventType; }
}
