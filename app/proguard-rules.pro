# Default ProGuard rules

# Keep Room entities and DAOs
-keep class com.phonoteka.data.** { *; }

# Keep Parcelable creators
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep Enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
# Room entities are accessed by generated adapters; Gson DTO field names form the wire contract.
-keep class com.phonoteka.data.TrackEntity { *; }
-keep class com.phonoteka.data.CandidateEntity { *; }
-keep class com.phonoteka.network.CatalogPost { *; }
-keepattributes *Annotation*

# Rhino's desktop JavaBeans / javax.script adapters are optional and have no Android runtime path.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.ScriptEngineFactory
