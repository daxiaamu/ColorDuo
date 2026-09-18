package io.github.colorduo;
import org.junit.Test;
import static org.junit.Assert.*;

public class EffectModeTest {
    @Test public void invalidModeFallsBackToAcceptedFrostEffect() {
        assertEquals(1,EffectMode.normalize(1));
        assertEquals(2,EffectMode.normalize(2));
        assertEquals(2,EffectMode.normalize(0));
        assertEquals(2,EffectMode.normalize(Integer.MAX_VALUE));
    }
    @Test public void gaussianKeepsTheFirstReleaseCurve() {
        assertEquals(0f,GaussianDepthModel.radiusDp(1,true,4f),0f);
        assertEquals(13f,GaussianDepthModel.radiusDp(1,true,57f),0.0001f);
        assertEquals(26f,GaussianDepthModel.radiusDp(1,true,110f),0f);
        for (int i=1;i<260;i++) {
            float radius=i/10f;
            assertEquals(radius,GaussianDepthModel.radiusDp(1,true,GaussianDepthModel.depthForRadius(radius)),0.0001f);
        }
    }
    @Test public void effectsHaveIndependentNearGlassResponse() {
        assertEquals(0f,GaussianDepthModel.radiusDp(1,true,3f),0f);
        assertEquals(5.2f,DepthModel.radiusDp(1,true,3f),0.0001f);
    }
}
