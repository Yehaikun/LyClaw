package lyjew.com.lyclaw.reflect.model;

public class Inconsistency {
    private String claim1;
    private String claim2;
    private String reason;
    private String resolution;

    public Inconsistency() {}
    public Inconsistency(String claim1, String claim2, String reason, String resolution) {
        this.claim1 = claim1; this.claim2 = claim2; this.reason = reason; this.resolution = resolution;
    }
    public String getClaim1() { return claim1; }
    public void setClaim1(String v) { this.claim1 = v; }
    public String getClaim2() { return claim2; }
    public void setClaim2(String v) { this.claim2 = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getResolution() { return resolution; }
    public void setResolution(String v) { this.resolution = v; }
}
