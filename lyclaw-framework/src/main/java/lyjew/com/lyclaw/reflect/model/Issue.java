package lyjew.com.lyclaw.reflect.model;

import java.util.*;

public class Issue {
    private Severity severity;
    private String category;
    private String description;

    public Issue() {}
    public Issue(Severity severity, String category, String description) {
        this.severity = severity; this.category = category; this.description = description;
    }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity v) { this.severity = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String toString() { return "Issue{severity=" + severity + ", category=" + category + ", description=" + description + "}"; }
}
