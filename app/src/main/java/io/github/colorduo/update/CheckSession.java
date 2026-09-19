package io.github.colorduo.update;

/** Called on the main thread; manual joins are retained until the shared operation finishes. */
final class CheckSession {
    private boolean active,manual;
    boolean begin(boolean requestedManually){manual|=requestedManually;if(active)return false;active=true;return true;}
    boolean finish(){boolean result=manual;active=false;manual=false;return result;}
}
