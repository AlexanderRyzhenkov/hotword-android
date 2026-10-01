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
        versionCode = 9
        versionName = "0.5.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}

// Native AAR and Russian acoustic files are pinned build inputs downloaded only
// on the developer/CI machine. The installed app remains fully offline.
val verifyPocketSphinxInputs by tasks.registering {
    group = "verification"
    description = "Verify PocketSphinx AAR and Russian acoustic model build inputs"
    doLast {
        val aar = file("libs/pocketsphinx-android-5prealpha-release.aar")
        check(aar.isFile && aar.length() > 0L) {
            "Missing PocketSphinx AAR; run bash scripts/prepare-pocketsphinx.sh"
        }

        val assetDir = file("src/main/assets/pocketsphinx/ru")
        val required = listOf(
            "feat.params", "feature_transform", "mdef", "means",
            "mixture_weights", "noisedict", "transition_matrices", "variances"
        )
        check(required.all { file -> assetDir.resolve(file).isFile && assetDir.resolve(file).length() > 0L }) {
            "Missing PocketSphinx Russian acoustic files; run bash scripts/prepare-pocketsphinx.sh"
        }
        val expected = rootProject.file("models/pocketsphinx-ru.sha256").readText().trim()
        val marker = assetDir.resolve(".source-sha256").readText().trim()
        check(marker == expected) {
            "PocketSphinx Russian model source marker mismatch"
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyPocketSphinxInputs) }

dependencies {
    implementation(files("libs/pocketsphinx-android-5prealpha-release.aar"))
    testImplementation("junit:junit:4.13.2")
}
