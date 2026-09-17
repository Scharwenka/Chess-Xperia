# Les methodes natives et les classes referencees depuis le JNI ne doivent pas
# etre renommees : le pont C++ les resout par nom.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.scharwenka.xperiamix.audio.NativeMixer { *; }
