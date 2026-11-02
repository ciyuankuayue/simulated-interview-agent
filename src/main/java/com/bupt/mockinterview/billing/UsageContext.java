package com.bupt.mockinterview.billing;

/** Request-local attribution only; no private input or model reasoning is retained. */
public final class UsageContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private static final class State {
        Long sessionId; String callId; String status = "SUCCESS"; int attempts;
    }
    private UsageContext() {}
    public interface Scope extends AutoCloseable { @Override void close(); }
    public static Scope withSession(Long sessionId) {
        State previous = CURRENT.get(); State next = new State();
        next.sessionId = sessionId; CURRENT.set(next);
        return () -> restore(previous);
    }
    public static Scope withCall(String callId) {
        State previous = CURRENT.get(); State next = new State();
        next.sessionId = previous == null ? null : previous.sessionId;
        next.callId = callId; CURRENT.set(next);
        return () -> restore(previous);
    }
    private static void restore(State previous) { if (previous == null) clear(); else CURRENT.set(previous); }
    public static Long sessionId() { return CURRENT.get() == null ? null : CURRENT.get().sessionId; }
    public static String callId() { return CURRENT.get() == null ? null : CURRENT.get().callId; }
    public static String status() { return CURRENT.get() == null ? "SUCCESS" : CURRENT.get().status; }
    public static void status(String value) { if (CURRENT.get() != null) CURRENT.get().status = value; }
    public static void noteAttempt() { if (CURRENT.get() != null) CURRENT.get().attempts++; }
    public static int attempts() { return CURRENT.get() == null ? 1 : Math.max(1, CURRENT.get().attempts); }
    public static void clear() { CURRENT.remove(); }
}
