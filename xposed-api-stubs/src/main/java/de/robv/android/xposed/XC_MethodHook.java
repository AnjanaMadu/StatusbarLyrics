package de.robv.android.xposed;

public abstract class XC_MethodHook {
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Object thisObject;
    }

    public final class Unhook {}
}
