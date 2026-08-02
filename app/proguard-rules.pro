# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite* {
   <fields>;
}

# Shizuku instantiates this short-lived UserService by class name and constructor.
-keep class io.github.jd1378.otphelper.shizuku.RepairUserService {
    public <init>();
    public <init>(android.content.Context);
    public *;
}
-keep interface io.github.jd1378.otphelper.shizuku.IRepairService { *; }
-keep class io.github.jd1378.otphelper.shizuku.IRepairService$Stub { *; }
