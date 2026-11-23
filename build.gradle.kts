plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

val debugKeystoreFile = rootProject.file("debug.keystore")
val debugKeystoreBase64File = rootProject.file("debug.keystore.base64")
if (!debugKeystoreFile.exists() && debugKeystoreBase64File.exists()) {
    val encoded = debugKeystoreBase64File.readText().replace("\\s".toRegex(), "")
    debugKeystoreFile.writeBytes(java.util.Base64.getDecoder().decode(encoded))
}

