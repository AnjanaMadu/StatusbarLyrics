package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

// Compile-only API signatures. Vector supplies the runtime implementation.
public interface IXposedHookLoadPackage {
    void handleLoadPackage(LoadPackageParam param) throws Throwable;
}
