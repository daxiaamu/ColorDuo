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

/** Adapter inspected against OplusLauncher 16.6.17 and 17.3.9. */
public final class DuoHook implements IXposedHookLoadPackage {
    private final WeakHashMap<View, PageState> active = new WeakHashMap<>();
    private static final class PageState {
        final boolean clip; int layer;
        PageState(ViewGroup page) { clip=page.getClipChildren(); layer=page.getLayerType(); }
    }
    private Class<?> cellType,slantType;
    private final java.util.Set<Class<?>> readyPages=new java.util.HashSet<>(), rejectedPages=new java.util.HashSet<>();
    private final java.util.Set<Method> drawHooks=new java.util.HashSet<>(), layerHooks=new java.util.HashSet<>(), agentHooks=new java.util.HashSet<>();
    private final ArrayList<XC_MethodHook.Unhook> installedHooks=new ArrayList<>();
    private final java.util.Set<Class<?>> readyAgents=new java.util.HashSet<>();
    private int applyDepth;
    private final java.util.Set<View> changingLayers=java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private PageRenderer depthRenderer;
    private int effectMode=EffectMode.FROST;
    private EffectSettings.Watcher settingsWatcher;
    private java.lang.ref.WeakReference<ViewGroup> currentWorkspace=new java.lang.ref.WeakReference<>(null);
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
                    boolean ready = LauncherReflection.hasNamedSuperclass(activity.getClass(),"com.android.launcher.Launcher")
                            && !activity.isFinishing() && !activity.isDestroyed();
                    if (ready) {
                        try {
                            currentWorkspace=new java.lang.ref.WeakReference<>((ViewGroup)XposedHelpers.callMethod(activity,"getWorkspace"));
                            if (settingsWatcher==null) settingsWatcher=new EffectSettings.Watcher(activity,DuoHook.this::applyEffectMode);
                            else settingsWatcher.refresh();
                        } catch (Throwable error) { android.util.Log.w("ColorDuoSettings","Settings unavailable",error); }
                    }
                    startup.runWhenReady(ready, () -> {
                        install(pkg.classLoader,activity);
                        try {
                            android.content.pm.PackageInfo info=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0);
                            XposedBridge.log("ColorDuo launcher="+info.versionName+" ("+info.getLongVersionCode()+") sdk="+android.os.Build.VERSION.SDK_INT);
                        } catch (Exception ignored) { /* Version logging must not disable rendering. */ }
                        activity.getWindow().getDecorView().postDelayed(() -> {
                            if (failed || activity.isDestroyed()) return;
                            try { warmPages((ViewGroup) XposedHelpers.callMethod(activity, "getWorkspace"), true); }
                            catch (Throwable error) { XposedBridge.log("ColorDuo initial prewarm: " + error); }
                        }, 250);
                    });
                }
            });
            XposedBridge.log("ColorDuo "+BuildConfig.VERSION_NAME+": waiting for launcher onPostResume");
        } catch (Throwable error) { disable(error); }
    }

    private void install(ClassLoader loader,Activity activity) {
        try {
            ViewGroup liveWorkspace=(ViewGroup)XposedHelpers.callMethod(activity,"getWorkspace");
            LauncherBindings bindings=new LauncherBindings(loader,liveWorkspace.getClass(),activity.getClass());
            cellType=bindings.cell; slantType=bindings.slant;
            GpuRasterizer.enableForLauncher();
            workspaceField=bindings.workspaceField;
            launcherField=bindings.launcherField;
            normalStateField=bindings.normalStateField;
            rangeMethod=bindings.rangeMethod;
            switchingMethod=bindings.switchingMethod;
            interceptMethod=bindings.interceptMethod;
            stateMethod=bindings.stateMethod;
            XC_MethodHook clear = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { clearAll(); }
            };
            hook(bindings.beginMethod, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    pageTransition = true; warmGeneration++; transitionDraws=0;
                    if (!failed) warmPages((ViewGroup)p.thisObject, false);
                }
            });
            // Install cleanup first. Never modify an effect setting or guess an effect ID.
            hook(bindings.restoreMethod, clear); agentHooks.add(bindings.restoreMethod);
            hook(bindings.recycleMethod, clear); agentHooks.add(bindings.recycleMethod);
            hook(bindings.endMethod, new XC_MethodHook() {
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
            hook(bindings.detachMethod, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    clearAll(); warmGeneration++;
                    if (depthRenderer!=null) { depthRenderer.release(); depthRenderer=null; }
                }
            });
            hook(bindings.setStateMethod, clear);
            hook(bindings.setStateAnimationMethod, clear);
            hook(bindings.resetMethod, clear);
            Object controller=XposedHelpers.getObjectField(liveWorkspace,"mEffectController");
            Object agent=XposedHelpers.callMethod(controller,"getEffectAgent");
            if(agent!=null && slantType.isInstance(agent)) ensureAgentHook(agent);
            XposedBridge.log("ColorDuo "+BuildConfig.VERSION_NAME+": Slant adapter installed after launcher resume (contract checked; baselines 16.6.17 / 17.3.9 / 17.3.12)");
        } catch (Throwable error) { disable(error); }
    }

    private void hook(Method method,XC_MethodHook callback) {
        installedHooks.add(XposedBridge.hookMethod(method,callback));
    }
    private void hookOnce(java.util.Set<Method> seen,Method method,XC_MethodHook callback) {
        if(seen.contains(method)) return;
        hook(method,callback); seen.add(method);
    }
    private void rollbackHooks(int checkpoint) {
        while(installedHooks.size()>checkpoint) {
            XC_MethodHook.Unhook unhook=installedHooks.remove(installedHooks.size()-1);
            drawHooks.remove(unhook.getHookedMethod()); layerHooks.remove(unhook.getHookedMethod());
            agentHooks.remove(unhook.getHookedMethod());
            try { unhook.unhook(); } catch(Throwable ignored) { /* Best-effort host recovery. */ }
        }
    }
    private boolean ensurePageHooks(View page) {
        if(page==null || cellType==null || !cellType.isInstance(page)) return false;
        Class<?> type=page.getClass();
        if(readyPages.contains(type)) return true;
        if(rejectedPages.contains(type)) return false;
        int checkpoint=installedHooks.size();
        try {
            // Resolve both signatures before any mutation; subclass overrides need not call super.
            Method draw=LauncherReflection.method(type,"dispatchDraw",void.class,Canvas.class);
            Method hardware=LauncherReflection.method(type,"enableHardwareLayer",void.class,boolean.class);
            hookOnce(drawHooks,draw, new XC_MethodHook() {
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
            hookOnce(layerHooks,hardware, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View page=(View)p.thisObject;
                    PageState state=active.get(page);
                    if (state==null || failed || !changingLayers.add(page)) return;
                    p.setObjectExtra("ColorDuo.layerOwner",Boolean.TRUE);
                    state.layer=(Boolean)p.args[0] ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
                    if ((Boolean)p.args[0]) p.args[0]=false;
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if(Boolean.TRUE.equals(p.getObjectExtra("ColorDuo.layerOwner"))) changingLayers.remove((View)p.thisObject);
                }
            });
            readyPages.add(type);
            XposedBridge.log("ColorDuo page adapter: "+type.getName()+" draw="+draw.getDeclaringClass().getName());
            return true;
        } catch(Throwable error) {
            rollbackHooks(checkpoint);
            rejectedPages.add(type);
            XposedBridge.log("ColorDuo unsupported page; keep native: "+type.getName()+": "+error);
            return false;
        }
    }
    private void ensureAgentHook(Object agent) throws ReflectiveOperationException {
        Class<?> type=agent.getClass();
        if(readyAgents.contains(type)) return;
        Method apply=LauncherReflection.method(type,"applySlantEffect",void.class,int.class);
        Method restore=LauncherReflection.method(type,"restoreParameters",void.class);
        Method recycle=LauncherReflection.method(type,"recycle",void.class);
        hookOnce(agentHooks,apply,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { applyDepth++; }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if(--applyDepth>0) return;
                if(failed || p.hasThrowable()) { clearAll(); return; }
                try { update(p.thisObject); } catch(Throwable error) { disable(error); }
            }
        });
        XC_MethodHook clear=new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { clearAll(); }
        };
        hookOnce(agentHooks,restore,clear);
        hookOnce(agentHooks,recycle,clear);
        readyAgents.add(type);
        XposedBridge.log("ColorDuo effect adapter: "+type.getName()+" apply="+apply.getDeclaringClass().getName());
    }

    private void applyEffectMode(int selected) {
        int mode=EffectMode.normalize(selected);
        if (failed || mode==effectMode) return;
        clearAll(); warmGeneration++;
        if (depthRenderer!=null) depthRenderer.release();
        depthRenderer=null; effectMode=mode;
        ViewGroup workspace=currentWorkspace.get();
        if (workspace!=null && workspace.isAttachedToWindow()) {
            warmPages(workspace,true);
            workspace.invalidate();
        }
        android.util.Log.i("ColorDuoSettings","Applied effect="+mode+" without launcher restart");
    }

    private void warmPages(ViewGroup workspace, boolean refresh) {
        try {
            Object controller=XposedHelpers.getObjectField(workspace,"mEffectController");
            Object agent=XposedHelpers.callMethod(controller,"getEffectAgent");
            if (agent==null || slantType==null || !slantType.isInstance(agent)) return;
            ensureAgentHook(agent);
            if (depthRenderer == null) depthRenderer = PageRenderer.create(effectMode);
            int current = (Integer) XposedHelpers.callMethod(workspace, "getCurrentPage");
            for (int i=Math.max(0,current-1); i<=Math.min(workspace.getChildCount()-1,current+1); i++) {
                View page=workspace.getChildAt(i);
                if(ensurePageHooks(page)) depthRenderer.prepare(page,refresh);
            }
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
        if (depthRenderer == null) depthRenderer = PageRenderer.create(effectMode);
        cleanup.clear();
        cleanup.addAll(active.keySet());
        for (int indexToClean = 0; indexToClean < cleanup.size(); indexToClean++) {
            View page = cleanup.get(indexToClean);
            int index = workspace.indexOfChild(page);
            if (index < range[0] || index > range[1]) clear(page);
        }
        for (int i = range[0]; i <= Math.min(range[1], workspace.getChildCount() - 1); i++) {
            View page = workspace.getChildAt(i);
            if (page == null || !ensurePageHooks(page)) continue;
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
        rollbackHooks(0);
        if (depthRenderer!=null) { depthRenderer.release(); depthRenderer=null; }
        XposedBridge.log("ColorDuo: disabled for this launcher process: " + error);
    }
}
