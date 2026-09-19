package io.github.colorduo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Metadata-only lookups: never read launcher static fields during validation. */
final class LauncherReflection {
    private LauncherReflection() {}
    static Method method(Class<?> owner,String name,Class<?> result,Class<?>... args) throws NoSuchMethodException {
        for (Class<?> type=owner;type!=null;type=type.getSuperclass()) {
            Method method;
            try { method=type.getDeclaredMethod(name,args); }
            catch (NoSuchMethodException absent) { continue; }
            if (method.getReturnType()!=result)
                throw new NoSuchMethodException(owner.getName()+"."+name+": unexpected return type");
            method.setAccessible(true);
            return method;
        }
        throw new NoSuchMethodException(owner.getName()+"."+name);
    }
    static Field field(Class<?> owner,String name,Class<?> expected) throws NoSuchFieldException {
        for (Class<?> type=owner;type!=null;type=type.getSuperclass()) {
            Field field;
            try { field=type.getDeclaredField(name); }
            catch (NoSuchFieldException absent) { continue; }
            if (field.getType()!=expected)
                throw new NoSuchFieldException(owner.getName()+"."+name+": unexpected field type");
            field.setAccessible(true);
            return field;
        }
        throw new NoSuchFieldException(owner.getName()+"."+name);
    }
}
