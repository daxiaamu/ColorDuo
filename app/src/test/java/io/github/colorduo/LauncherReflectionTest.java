package io.github.colorduo;

import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherReflectionTest {
    static class Base {
        private int value;
        public void change(int state) {}
        public boolean ready() { return true; }
    }
    static class Child extends Base {
        @Override public void change(int state) { if (state==0) return; super.change(state); }
    }
    static class Invalid extends Base { private String value; }
    private static boolean initialized;
    static class Delayed {
        static { initialized=true; }
        public static final Object NORMAL=new Object();
        public void change() {}
    }
    @Test public void concreteOverrideWinsEvenIfItReturnsBeforeSuper() throws Exception {
        assertEquals(Child.class,LauncherReflection.method(Child.class,"change",void.class,int.class).getDeclaringClass());
    }
    @Test public void inheritedMembersRemainResolvable() throws Exception {
        assertEquals(Base.class,LauncherReflection.method(Child.class,"ready",boolean.class).getDeclaringClass());
        assertEquals(Base.class,LauncherReflection.field(Child.class,"value",int.class).getDeclaringClass());
    }
    @Test public void validationDoesNotInitializeLauncherState() throws Exception {
        Class<?> type=Class.forName(getClass().getName()+"$Delayed",false,getClass().getClassLoader());
        LauncherReflection.field(type,"NORMAL",Object.class);
        LauncherReflection.method(type,"change",void.class);
        assertFalse(initialized);
    }
    @Test(expected=NoSuchMethodException.class) public void wrongReturnTypeIsRejected() throws Exception {
        LauncherReflection.method(Child.class,"ready",int.class);
    }
    @Test(expected=NoSuchFieldException.class) public void incompatibleShadowedFieldIsRejected() throws Exception {
        LauncherReflection.field(Invalid.class,"value",int.class);
    }
    @Test(expected=NoSuchMethodException.class) public void missingSignatureIsRejected() throws Exception {
        LauncherReflection.method(Child.class,"change",void.class,String.class);
    }
}
