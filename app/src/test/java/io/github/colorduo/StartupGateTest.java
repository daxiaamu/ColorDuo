package io.github.colorduo;
import org.junit.Test;
import static org.junit.Assert.*;
public class StartupGateTest {
    @Test public void packageLoadAndOtherActivitiesNeverRunHostSetup() {
        StartupGate gate = new StartupGate();
        gate.runWhenReady(false, () -> fail("Host setup before launcher is ready"));
        gate.runWhenReady(false, () -> fail("Host setup on an unrelated activity"));
        int[] calls = {0};
        gate.runWhenReady(true, () -> calls[0]++);
        assertEquals(1, calls[0]);
    }
    @Test public void repeatedAndReentrantResumesInstallOnlyOnce() {
        StartupGate gate = new StartupGate();
        int[] calls = {0};
        gate.runWhenReady(true, () -> {
            calls[0]++;
            gate.runWhenReady(true, () -> fail("Reentrant install"));
        });
        gate.runWhenReady(true, () -> fail("Repeated install"));
        assertEquals(1, calls[0]);
    }
    @Test public void failedPartialInstallIsNeverRetried() {
        StartupGate gate = new StartupGate();
        try { gate.runWhenReady(true, () -> { throw new IllegalStateException("incompatible host"); }); }
        catch (IllegalStateException expected) { }
        gate.runWhenReady(true, () -> fail("Retried partially installed hooks"));
    }
}
