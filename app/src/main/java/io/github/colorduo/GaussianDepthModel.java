package io.github.colorduo;

/** Virtual card behind the glass: its closest edge is on the focus plane. */
public final class GaussianDepthModel {
    public static final float FOCUS_DEPTH_DP = 4f;
    public static final float FULL_BLUR_DEPTH_DP = 110f;
    public static final float MAX_RADIUS_DP = 26f;
    private GaussianDepthModel() {}
    public static float spanDp(float widthPx, float density, float rotationY) {
        if (!Float.isFinite(widthPx) || !Float.isFinite(density) || !Float.isFinite(rotationY)
                || widthPx <= 0 || density <= 0) return 0f;
        return widthPx / density * Math.abs((float) Math.sin(Math.toRadians(rotationY)));
    }
    public static float radiusDp(float xFraction, boolean farRight, float spanDp) {
        if (!Float.isFinite(xFraction) || !Float.isFinite(spanDp) || spanDp <= 0) return 0f;
        float x = Math.max(0f, Math.min(1f, xFraction));
        float depth = spanDp * (farRight ? x : 1f - x);
        float t = Math.max(0f, Math.min(1f, (depth - FOCUS_DEPTH_DP)
                / (FULL_BLUR_DEPTH_DP - FOCUS_DEPTH_DP)));
        return MAX_RADIUS_DP * t * t * (3f - 2f * t);
    }
    /** Inverse of the continuous depth curve, used to partition equal-LOD drawing regions. */
    public static float depthForRadius(float radiusDp) {
        if (radiusDp>MAX_RADIUS_DP) return Float.POSITIVE_INFINITY;
        if (radiusDp<=0f) return FOCUS_DEPTH_DP;
        float fraction=radiusDp/MAX_RADIUS_DP;
        float t=(float)(0.5-Math.sin(Math.asin(1.0-2.0*fraction)/3.0));
        return FOCUS_DEPTH_DP+t*(FULL_BLUR_DEPTH_DP-FOCUS_DEPTH_DP);
    }
    public static float lodBoundary(float width, float padding, float depth, float span) {
        // The depth map clamps at the content edge, including all transparent padding.
        return depth>=span ? width+padding : width*depth/span;
    }
    public static float layerWeight(float radius, float low, float center, float high) {
        if (radius <= center) return center == low ? 1f : Math.max(0f, (radius - low) / (center - low));
        return high == center ? 1f : Math.max(0f, (high - radius) / (high - center));
    }
}
