# Solo per la versione completa (vedi productFlavors in build.gradle.kts).

# ML Kit (riconoscimento della scrittura): R8 rompe il registro interno dei componenti
# ("Attempt to read from field HashMap ... on a null object reference" nella build release).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_digital_ink.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_linkfirebase.** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.google.android.libraries.mdi.** { *; }
