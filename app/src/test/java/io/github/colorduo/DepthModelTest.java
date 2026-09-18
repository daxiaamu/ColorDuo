package io.github.colorduo;
import org.junit.Test;
import static org.junit.Assert.*;
public class DepthModelTest {
    @Test public void flatCardAndNearEdgeStaySharp() {
        assertEquals(0f, DepthModel.spanDp(1200, 3, 0), 0f);
        assertEquals(0f, DepthModel.radiusDp(0, true, 110), 0f);
        assertEquals(0f, DepthModel.radiusDp(1, false, 110), 0f);
    }
    @Test public void oneCardHasContinuousDepthGradient() {
        float previous = 0;
        for (int i = 0; i <= 10000; i++) {
            float radius = DepthModel.radiusDp(i / 10000f, true, 110);
            assertTrue(radius >= previous);
            assertTrue(radius - previous < 0.05f);
            previous = radius;
        }
        assertEquals(26, previous, 0);
        assertTrue(DepthModel.radiusDp(.25f, true, 110) < DepthModel.radiusDp(.75f, true, 110));
    }
    private float radiusAtDepth(float depth) { return DepthModel.radiusDp(1f,true,depth); }
    @Test public void smallDepartureImmediatelyProducesVisibleFrost() {
        assertEquals(0f,radiusAtDepth(0f),0f);
        assertTrue(radiusAtDepth(0.001f)<0.01f);
        assertTrue(radiusAtDepth(1f)>1.7f);
        assertEquals(5.2f,radiusAtDepth(3f),0.0001f);
    }
    @Test public void afterOnsetTheCurveIsLinearAndContinuous() {
        assertEquals(radiusAtDepth(3f-0.0001f),radiusAtDepth(3f+0.0001f),0.001f);
        assertEquals(radiusAtDepth(30f)-radiusAtDepth(20f),
                radiusAtDepth(80f)-radiusAtDepth(70f),0.0001f);
        assertEquals(26f,radiusAtDepth(200f),0f);
    }
    @Test public void oppositeTiltsMirrorTheDepthMap() {
        for (int i = 0; i <= 10000; i++) {
            float x = i / 10000f;
            assertEquals(DepthModel.radiusDp(x, true, 110), DepthModel.radiusDp(1-x, false, 110), .0001f);
        }
    }
    @Test public void physicalDepthScalesWithWidthAndTiltNotJustProgress() {
        float a = DepthModel.spanDp(1200, 3, 8);
        assertEquals(a * 2, DepthModel.spanDp(2400, 3, 8), .0001f);
        assertEquals(a, DepthModel.spanDp(1200, 3, -8), 0);
        assertTrue(DepthModel.spanDp(1200, 3, 16) > a);
        assertEquals(0, DepthModel.spanDp(1200, 0, 8), 0);
    }
    @Test public void lodBoundariesMatchTheOriginalDepthCurve() {
        for (int n=1;n<=259;n++) {
            float radius=n/10f;
            float depth=DepthModel.depthForRadius(radius);
            assertEquals(radius,DepthModel.radiusDp(depth/110f,true,110f),0.0001f);
        }
        assertEquals(Float.POSITIVE_INFINITY,DepthModel.depthForRadius(27f),0f);
    }
    @Test public void unreachableLodDoesNotStartInsideTransparentPadding() {
        assertEquals(1200f,DepthModel.lodBoundary(1000,200,101,100),0f);
        assertEquals(1200f,DepthModel.lodBoundary(1000,200,Float.POSITIVE_INFINITY,100),0f);
        assertEquals(500f,DepthModel.lodBoundary(1000,200,50,100),0f);
    }
    @Test public void blurLayersPreserveOpacityAndBrightness() {
        float[] levels = {0, 4, 10, 18, 26};
        for (int n = 0; n <= 260; n++) {
            float sum = 0;
            for (int i = 0; i < levels.length; i++) {
                float w = DepthModel.layerWeight(n / 10f, levels[Math.max(0,i-1)], levels[i], levels[Math.min(4,i+1)]);
                assertTrue(w >= 0 && w <= 1);
                sum += w;
            }
            assertEquals(1, sum, .0001f);
        }
    }
}
