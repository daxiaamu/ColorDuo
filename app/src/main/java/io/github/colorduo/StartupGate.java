package io.github.colorduo;

/** Main-thread gate: do not run host setup before readiness or retry partially installed hooks. */
public final class StartupGate {
    private boolean attempted;
    public void runWhenReady(boolean ready, Runnable install) {
        if (!ready || attempted) return;
        attempted = true;
        install.run();
    }
}
