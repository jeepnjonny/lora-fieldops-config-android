# usb-serial-for-android
-keep class com.hoho.android.usbserial.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations, RuntimeInvisibleTypeAnnotations
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
# security-crypto (Tink): compile-only annotations, absent at runtime
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
