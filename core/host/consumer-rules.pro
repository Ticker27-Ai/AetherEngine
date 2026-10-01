# AetherEngine consumer ProGuard rules.
#
# Everything the container reaches reflectively must survive, whether the
# shrinker runs in the host app or in a library consumer.

# --- native bridge ---------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class dev.aether.host.native.AetherNative { *; }

# Called from C++ during hidden-API calibration; the name is part of the ABI
# with hidden_api.cpp and must not be obfuscated.
-keep class dev.aether.host.native.HiddenApiProbe {
    public boolean tryBlacklistedReflection();
}

# --- generated stub pool ---------------------------------------------------
# Referenced by Class.forName() from the manifest and from StubPool.
-keep class dev.aether.host.runtime.VirtualStubs { *; }
-keep class dev.aether.host.runtime.Stub* extends dev.aether.host.runtime.VirtualActivity {
    public <init>();
}
-keep class dev.aether.host.runtime.VirtualActivity { *; }

# --- reflective framework access -------------------------------------------
# We do not reference these types directly; only their field/method names as
# strings. Keep the names stable.
-keepclassmembers class android.app.Activity {
    *** mToken;
    *** mIdent;
    *** mEmbeddedID;
    *** mTitle;
    *** mVoiceInteractor;
}
-keepclassmembers class android.app.ActivityThread {
    *** mInstrumentation;
    *** sPackageManager;
    *** mH;
}
-keepclassmembers class android.content.res.AssetManager {
    *** addAssetPath(...);
}

# --- diagnostics ------------------------------------------------------------
-keep class dev.aether.host.core.GuestProfile { *; }
-keep class dev.aether.host.vfs.VfsRouter { *; }
