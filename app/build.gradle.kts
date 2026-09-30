import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hotword.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.hotword.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}

// Bundled ZIP is a git-ignored build input. It is NOT fetched on the phone.
val verifyBundledModel by tasks.registering {
    group = "verification"
    description = "Verify the SHA-256 of the Russian model embedded in the APK"
    doLast {
        val archive = file("src/main/assets/models/ru.zip")
        check(archive.isFile && archive.length() > 0) {
            "Missing bundled model; run bash scripts/prepare-bundled-model.sh from repository root"
        }
        val expected = rootProject.file("models/ru.sha256").readText().trim()
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(expected, ignoreCase = true)) {
            "Bundled model SHA-256 mismatch; run bash scripts/prepare-bundled-model.sh"
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyBundledModel) }

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
    testImplementation("junit:junit:4.13.2")
}
