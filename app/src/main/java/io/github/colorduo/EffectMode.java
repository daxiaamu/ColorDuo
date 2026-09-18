package io.github.colorduo;

public final class EffectMode {
    public static final int GAUSSIAN=1, FROST=2;
    private EffectMode() {}
    public static int normalize(int mode) { return mode==GAUSSIAN ? GAUSSIAN : FROST; }
}
