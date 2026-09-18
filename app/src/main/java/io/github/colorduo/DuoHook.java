package io.github.colorduo;

import android.app.Activity;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Adapter inspected against OplusLauncher 16.6.17 (160060017). */
public final class DuoHook implements IXposedHookLoadPackage {
    private static final String SLANT = "com.android.launcher.effect.agent.SlantEffectAgent";
    private final WeakHashMap<View, PageState> active = new WeakHashMap<>();
    private static final class PageState {
        final boolean clip; int layer;
        PageState(ViewGroup page) { clip=page.getClipChildren(); layer=page.getLayerType(); }
    }
    private DepthBlurRenderer depthRenderer;
    private Field workspaceField, launcherField, normalStateField;
    private Method rangeMethod, switchingMethod, stateMethod, interceptMethod;
    private final StartupGate startup = new StartupGate();

    private boolean failed, firstFrame, pageTransition;
    private long warmGeneration;
    private int transitionDraws;
    private ViewGroup unclippedWorkspace;
    private boolean originalClipChildren;
    private final ArrayList<View> cleanup = new ArrayList<>(4);

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!"com.android.launcher".equals(pkg.packageName)
                || !"com.android.launcher".equals(pkg.processName)) return;
        try {
            // At package load, touch ONLY framework classes. Reading a launcher static field
            // here can permanently poison its class initializer before Application.attach.
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || failed) return;
                    Activity activity = (Activity) p.thisObject;
                    boolean ready = "com.android.launcher.Launcher".equals(activity.getClass().getName())
                            && !activity.isFinishing() && !activity.isDestroyed();
                    startup.runWhenReady(ready, () -> {
                        install(pkg.classLoader);
                        activity.getWindow().getDecorView().postDelayed(() -> {
                            if (failed || activity.isDestroyed()) return;
                            try { warmPages((ViewGroup) XposedHelpers.callMethod(activity, "getWorkspace"), true); }
                            catch (Throwable error) { XposedBridge.log("ColorDuo initial prewarm: " + error); }
                        }, 250);
                    });
                }
            });
            XposedBridge.log("ColorDuo 0.3.2: waiting for launcher onPostResume");
        } catch (Throwable error) { disable(error); }
    }

    private void install(ClassLoader loader) {
        try {
            Class<?> slant = XposedHelpers.findClass(SLANT, loader);
            Class<?> base = slant.getSuperclass();
            Class<?> workspace = XposedHelpers.findClass("com.android.launcher3.OplusWorkspace", loader);
            Class<?> workspaceBase = XposedHelpers.findClass("com.android.launcher3.Workspace", loader);
            Class<?> states = XposedHelpers.findClass("com.android.launcher3.LauncherState", loader);
            workspaceField = XposedHelpers.findField(base, "mWorkspace");
            launcherField = XposedHelpers.findField(base, "mLauncher");
            rangeMethod = XposedHelpers.findMethodExact(workspace, "getVisibleChildrenRange");
            switchingMethod = XposedHelpers.findMethodExact(workspaceBase, "isSwitchingState");
            interceptMethod = XposedHelpers.findMethodExact(base, "interceptEffectWhenSwitchingState");
            normalStateField = XposedHelpers.findField(states, "NORMAL");
            stateMethod = XposedHelpers.findMethodExact(
                    XposedHelpers.findClass("com.android.launcher3.statemanager.StatefulActivity", loader),
                    "isInState", XposedHelpers.findClass("com.android.launcher3.statemanager.BaseState", loader));
            XC_MethodHook clear = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { clearAll(); }
            };
            XposedHelpers.findAndHookMethod(workspace, "onPageBeginTransition", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    pageTransition = true; warmGeneration++; transitionDraws=0;
                    if (!failed) warmPages((ViewGroup)p.thisObject, false);
                }
            });
            XposedHelpers.findAndHookMethod("com.android.launcher3.CellLayout", loader,
                    "dispatchDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (failed || depthRenderer==null || depthRenderer.isRecording()) return;
                    View page=(View)p.thisObject;
                    if (!active.containsKey(page)) return;
                    try {
                        if (depthRenderer.drawPage(page,(Canvas)p.args[0])) { transitionDraws++; p.setResult(null);
                            if (!firstFrame) { firstFrame=true; android.util.Log.i("ColorDuoDraw", "GPU page draw confirmed: " + page.getClass().getName()); } }
                    } catch (Throwable error) { disable(error); }
                }
            });
            // Workspace requests hardware layers again on scroll. Do not allocate a clipped
            // intermediate surface for a page already backed by our cached GPU textures.
            XposedHelpers.findAndHookMethod("com.android.launcher3.CellLayout", loader,
                    "enableHardwareLayer", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    PageState state=active.get((View)p.thisObject);
                    if (state==null || failed) return;
                    state.layer=(Boolean)p.args[0] ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
                    if ((Boolean)p.args[0]) p.args[0]=false;
                }
            });
            // Install cleanup first. Never modify an effect setting or guess an effect ID.
            XposedHelpers.findAndHookMethod(slant, "restoreParameters", clear);
            XposedHelpers.findAndHookMethod(base, "recycle", clear);
            XposedHelpers.findAndHookMethod(workspace, "onPageEndTransition", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    clearAll(); pageTransition = false;
                    if (transitionDraws>0) android.util.Log.i("ColorDuoDraw","ColorDuo: transition GPU draws="+transitionDraws);
                    long generation = ++warmGeneration;
                    ViewGroup ws = (ViewGroup)p.thisObject;
                    ws.postDelayed(() -> {
                        if (!failed && !pageTransition && generation == warmGeneration && ws.isAttachedToWindow())
                            warmPages(ws, true);
                    }, 180);
                }
            });
            XposedHelpers.findAndHookMethod(workspaceBase, "onDetachedFromWindow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    clearAll(); warmGeneration++;
                    if (depthRenderer!=null) { depthRenderer.release(); depthRenderer=null; }
                }
            });
            XposedHelpers.findAndHookMethod(workspaceBase, "setState", states, clear);
            XposedHelpers.findAndHookMethod(workspaceBase, "setStateWithAnimation", states,
                    XposedHelpers.findClass("com.android.launcher3.states.StateAnimationConfig", loader),
                    XposedHelpers.findClass("com.android.launcher3.anim.PendingAnimation", loader), clear);
            XposedHelpers.findAndHookMethod("com.android.launcher.effect.EffectController", loader, "resetEffect", clear);
            XposedHelpers.findAndHookMethod(slant, "applySlantEffect", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (failed || p.hasThrowable()) { clearAll(); return; }
                    try { update(p.thisObject); }
                    catch (Throwable error) { disable(error); }
                }
            });
            XposedBridge.log("ColorDuo 0.3.2: Slant adapter installed after launcher resume (baseline 16.6.17)");
        } catch (Throwable error) { disable(error); }
    }

    private void warmPages(ViewGroup workspace, boolean refresh) {
        try {
            Object controller=XposedHelpers.getObjectField(workspace,"mEffectController");
            Object agent=XposedHelpers.callMethod(controller,"getEffectAgent");
            if (agent==null || !SLANT.equals(agent.getClass().getName())) return;
            if (depthRenderer == null) depthRenderer = new DepthBlurRenderer();
            int current = (Integer) XposedHelpers.callMethod(workspace, "getCurrentPage");
            for (int i=Math.max(0,current-1); i<=Math.min(workspace.getChildCount()-1,current+1); i++)
                depthRenderer.prepare(workspace.getChildAt(i), refresh);
        } catch (Throwable error) { XposedBridge.log("ColorDuo prewarm: " + error); }
    }

    private void update(Object agent) throws Exception {
        ViewGroup workspace = (ViewGroup) workspaceField.get(agent);
        Object launcher = launcherField.get(agent);
        if (workspace == null || launcher == null || !workspace.isAttachedToWindow()
                || !workspace.isHardwareAccelerated()
                || (Boolean) switchingMethod.invoke(workspace)
                || (Boolean) interceptMethod.invoke(agent)
                || !(Boolean) stateMethod.invoke(launcher, normalStateField.get(null))) {
            clearAll(); return;
        }
        int[] range = (int[]) rangeMethod.invoke(workspace);
        if (range == null || range.length < 2 || range[0] < 0 || range[1] <= range[0]) {
            clearAll(); return;
        }
        if (depthRenderer == null) depthRenderer = new DepthBlurRenderer();
        cleanup.clear();
        cleanup.addAll(active.keySet());
        for (int indexToClean = 0; indexToClean < cleanup.size(); indexToClean++) {
            View page = cleanup.get(indexToClean);
            int index = workspace.indexOfChild(page);
            if (index < range[0] || index > range[1]) clear(page);
        }
        for (int i = range[0]; i <= Math.min(range[1], workspace.getChildCount() - 1); i++) {
            View page = workspace.getChildAt(i);
            if (page == null) continue;
            if (!depthRenderer.canDraw(page)) { clear(page); continue; }
            if (unclippedWorkspace == null) {
                unclippedWorkspace=workspace; originalClipChildren=workspace.getClipChildren(); workspace.setClipChildren(false);
            }
            if (!active.containsKey(page)) {
                active.put(page, new PageState((ViewGroup)page));
                ((ViewGroup)page).setClipChildren(false);
                page.addOnAttachStateChangeListener(detachListener);
            }
            if (page.getLayerType()!=View.LAYER_TYPE_NONE) page.setLayerType(View.LAYER_TYPE_NONE,null);
            page.invalidate();

        }
    }

    private final View.OnAttachStateChangeListener detachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {}
        @Override public void onViewDetachedFromWindow(View v) { clear(v); }
    };
    private void clear(View page) {
        if (page == null) return;
        PageState originalClip=active.remove(page);
        if (originalClip==null) return;
        try { ((ViewGroup)page).setClipChildren(originalClip.clip);
            if (page.getLayerType()==View.LAYER_TYPE_NONE) page.setLayerType(originalClip.layer,null);
            page.invalidate(); }
        catch (Throwable error) { XposedBridge.log("ColorDuo: cleanup failed: " + error); }
        finally { page.removeOnAttachStateChangeListener(detachListener); }
    }
    private void clearAll() {
        if (unclippedWorkspace != null) {
            unclippedWorkspace.setClipChildren(originalClipChildren); unclippedWorkspace=null;
        }
        if (active.isEmpty()) return;
        cleanup.clear();
        cleanup.addAll(active.keySet());
        for (int i = 0; i < cleanup.size(); i++) clear(cleanup.get(i));
        cleanup.clear();
    }
    private void disable(Throwable error) {
        failed = true;
        clearAll();
        if (depthRenderer!=null) { depthRenderer.release(); depthRenderer=null; }
        XposedBridge.log("ColorDuo: disabled for this launcher process: " + error);
    }
}
