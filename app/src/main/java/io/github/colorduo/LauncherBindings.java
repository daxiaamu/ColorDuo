package io.github.colorduo;

import android.graphics.Canvas;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Contract checked against OplusLauncher 16.6.17 and 17.3.9 before installing any host hooks. */
final class LauncherBindings {
    final Class<?> slant,base,workspace,workspaceBase,states;
    final Field workspaceField,launcherField,normalStateField;
    final Method rangeMethod,switchingMethod,stateMethod,interceptMethod;
    final Method detachMethod,setStateMethod,setStateAnimationMethod;
    final Method beginMethod,endMethod,drawMethod,hardwareMethod,restoreMethod,recycleMethod,resetMethod,applyMethod;
    LauncherBindings(ClassLoader loader) throws ReflectiveOperationException {
        slant=load(loader,"com.android.launcher.effect.agent.SlantEffectAgent");
        base=load(loader,"com.android.launcher.effect.EffectAgent");
        workspace=load(loader,"com.android.launcher3.OplusWorkspace");
        workspaceBase=load(loader,"com.android.launcher3.Workspace");
        states=load(loader,"com.android.launcher3.LauncherState");
        Class<?> launcher=load(loader,"com.android.launcher.Launcher");
        Class<?> cell=load(loader,"com.android.launcher3.CellLayout");
        Class<?> oplusCell=load(loader,"com.android.launcher3.OplusCellLayout");
        Class<?> controller=load(loader,"com.android.launcher.effect.EffectController");
        Class<?> stateful=load(loader,"com.android.launcher3.statemanager.StatefulActivity");
        Class<?> baseState=load(loader,"com.android.launcher3.statemanager.BaseState");
        Class<?> config=load(loader,"com.android.launcher3.states.StateAnimationConfig");
        Class<?> animation=load(loader,"com.android.launcher3.anim.PendingAnimation");
        if (slant.getSuperclass()!=base || !workspaceBase.isAssignableFrom(workspace)
                || !ViewGroup.class.isAssignableFrom(cell) || !cell.isAssignableFrom(oplusCell))
            throw new IllegalStateException("Unsupported launcher class hierarchy");
        workspaceField=LauncherReflection.field(base,"mWorkspace",workspace);
        launcherField=LauncherReflection.field(base,"mLauncher",launcher);
        normalStateField=LauncherReflection.field(states,"NORMAL",states);
        if (!Modifier.isStatic(normalStateField.getModifiers()))
            throw new IllegalStateException("LauncherState.NORMAL must be static");
        LauncherReflection.field(workspace,"mEffectController",controller);
        rangeMethod=LauncherReflection.method(workspace,"getVisibleChildrenRange",int[].class);
        switchingMethod=LauncherReflection.method(workspaceBase,"isSwitchingState",boolean.class);
        interceptMethod=LauncherReflection.method(base,"interceptEffectWhenSwitchingState",boolean.class);
        stateMethod=LauncherReflection.method(stateful,"isInState",boolean.class,baseState);
        LauncherReflection.method(launcher,"getWorkspace",workspace);
        LauncherReflection.method(controller,"getEffectAgent",base);
        resetMethod=LauncherReflection.method(controller,"resetEffect",void.class);
        applyMethod=LauncherReflection.method(slant,"applySlantEffect",void.class,int.class);
        restoreMethod=LauncherReflection.method(slant,"restoreParameters",void.class);
        recycleMethod=LauncherReflection.method(base,"recycle",void.class);
        beginMethod=LauncherReflection.method(workspace,"onPageBeginTransition",void.class);
        endMethod=LauncherReflection.method(workspace,"onPageEndTransition",void.class);
        drawMethod=LauncherReflection.method(cell,"dispatchDraw",void.class,Canvas.class);
        if (!LauncherReflection.method(oplusCell,"dispatchDraw",void.class,Canvas.class).equals(drawMethod))
            throw new IllegalStateException("Unsupported OplusCellLayout drawing override");
        hardwareMethod=LauncherReflection.method(cell,"enableHardwareLayer",void.class,boolean.class);
        // Resolve the concrete overrides. ColorOS 17 can return before calling Workspace.
        detachMethod=LauncherReflection.method(workspace,"onDetachedFromWindow",void.class);
        setStateMethod=LauncherReflection.method(workspace,"setState",void.class,states);
        setStateAnimationMethod=LauncherReflection.method(workspace,"setStateWithAnimation",void.class,states,config,animation);
    }
    private static Class<?> load(ClassLoader loader,String name) throws ClassNotFoundException {
        return Class.forName(name,false,loader);
    }
}
