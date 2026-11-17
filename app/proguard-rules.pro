# LibretroDroid calls back into Kotlin/Java from native code via JNI.
-keep class com.swordfish.libretrodroid.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
