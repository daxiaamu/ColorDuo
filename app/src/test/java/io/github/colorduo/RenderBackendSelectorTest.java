package io.github.colorduo;

import org.junit.Test;
import static org.junit.Assert.*;

public class RenderBackendSelectorTest {
    @Test public void missingPrivateApiDoesNotBlockPublicBackend() {
        RenderBackendSelector s=new RenderBackendSelector(false,true);
        assertArrayEquals(new int[]{1},s.candidates(0));
    }
    @Test public void failedPreferredBackendFallsBackWithoutPerFrameRetries() {
        RenderBackendSelector s=new RenderBackendSelector(true,true);
        s.failure(0,100); s.success(1);
        assertArrayEquals(new int[]{1},s.candidates(101));
        assertArrayEquals(new int[]{1},s.candidates(2099));
        assertArrayEquals(new int[]{1,0},s.candidates(2100));
    }
    @Test public void bothFailThenRecoverWithoutRestart() {
        RenderBackendSelector s=new RenderBackendSelector(true,true);
        s.failure(0,100); s.failure(1,150);
        assertArrayEquals(new int[]{},s.candidates(2000));
        assertArrayEquals(new int[]{0},s.candidates(2100));
        s.success(0);
        assertArrayEquals(new int[]{0},s.candidates(2101));
    }
    @Test public void repeatedFailuresBackOffButRemainBounded() {
        RenderBackendSelector s=new RenderBackendSelector(true);
        long now=0;
        for(long delay:new long[]{2000,4000,8000,16000,30000,30000}) {
            s.failure(0,now);
            assertEquals(0,s.candidates(now+delay-1).length);
            assertArrayEquals(new int[]{0},s.candidates(now+delay));
            now+=delay;
        }
    }
    @Test public void successResetsFailurePenalty() {
        RenderBackendSelector s=new RenderBackendSelector(true);
        s.failure(0,0); s.failure(0,2000); s.success(0); s.failure(0,3000);
        assertArrayEquals(new int[]{0},s.candidates(5000));
    }
    @Test public void successfulFallbackStaysPreferredAfterRecovery() {
        RenderBackendSelector s=new RenderBackendSelector(true,true);
        s.failure(0,0); s.success(1);
        assertArrayEquals(new int[]{1,0},s.candidates(60000));
        s.failure(1,60000);
        assertArrayEquals(new int[]{0},s.candidates(60001));
    }
}
