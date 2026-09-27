# PdfBox-Android (esportazione in PDF).
# BouncyCastle e' escluso dalle dipendenze: serve solo ai PDF cifrati con un certificato
# (PublicKeySecurityHandler); quelli con password usano javax.crypto.
-dontwarn org.bouncycastle.**
# Decodifica JPEG 2000 facoltativa (libreria jp2-android, non inclusa): l'esportazione copia le
# immagini delle pagine cosi' come sono, senza decodificarle.
-dontwarn com.gemalto.jp2.**
