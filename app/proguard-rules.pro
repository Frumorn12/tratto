# PdfBox-Android (esportazione in PDF).
# BouncyCastle e' escluso dalle dipendenze: serve solo ai PDF cifrati con un certificato
# (PublicKeySecurityHandler); quelli con password usano javax.crypto.
-dontwarn org.bouncycastle.**
# Decodifica JPEG 2000 facoltativa (libreria jp2-android, non inclusa): l'esportazione copia le
# immagini delle pagine cosi' come sono, senza decodificarle.
-dontwarn com.gemalto.jp2.**

# ML Kit (riconoscimento della scrittura): R8 rompe il registro interno dei componenti
# ("Attempt to read from field HashMap ... on a null object reference" nella build release).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_digital_ink.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_linkfirebase.** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.google.android.libraries.mdi.** { *; }
