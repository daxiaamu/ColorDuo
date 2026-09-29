package io.github.colorduo;

/** Process-local preference and bounded retry policy; independent of Android/version names. */
final class RenderBackendSelector {
    private final boolean[] available;
    private final long[] retryAt;
    private final int[] failures;
    private int preferred;
    RenderBackendSelector(boolean... available) {
        this.available=available.clone(); retryAt=new long[available.length]; failures=new int[available.length];
    }
    synchronized void setAvailable(int backend,boolean value) { available[backend]=value; }
    synchronized int[] candidates(long now) {
        int[] result=new int[available.length]; int count=0;
        for(int offset=0;offset<available.length;offset++) {
            int backend=(preferred+offset)%available.length;
            if(available[backend] && now>=retryAt[backend]) result[count++]=backend;
        }
        return java.util.Arrays.copyOf(result,count);
    }
    synchronized void success(int backend) { preferred=backend; failures[backend]=0; retryAt[backend]=0; }
    synchronized void failure(int backend,long now) {
        failures[backend]=Math.min(failures[backend]+1,5);
        retryAt[backend]=now+Math.min(30000L,2000L<<(failures[backend]-1));
    }
}
