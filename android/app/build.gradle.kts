plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.resumabletransfer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.resumabletransfer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Release signing ──────────────────────────────────────────────────────
    // Credentials come from android/keystore.properties, which is gitignored.
    // A keystore and its passwords do not belong in a public repository: anyone
    // holding them can ship an update that Android accepts as this app. The
    // release APK in app-release/ is signed; producing a new one needs your own
    // key, which the README explains.
    val keystorePropsFile = rootProject.file("keystore.properties")
    // Parsed by hand rather than via java.util.Properties: inside a Gradle
    // Kotlin script the bare name `java` resolves to Gradle's JavaPluginExtension
    // and shadows the package, and the usual escape hatch does not compile here.
    // The format is four flat key=value lines, so this is not a parser question.
    val keystoreProps: Map<String, String> = if (keystorePropsFile.exists()) {
        keystorePropsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { line ->
                line.substringBefore('=').trim() to line.substringAfter('=').trim()
            }
    } else {
        emptyMap()
    }
    val hasReleaseKey = keystoreProps["storeFile"]
        ?.let { rootProject.file(it).exists() } == true

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getValue("storeFile"))
                storePassword = keystoreProps["storePassword"]
                keyAlias = keystoreProps["keyAlias"]
                keyPassword = keystoreProps["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            // Was false. A debug-signed APK with minification off is the largest,
            // least private thing this project could ship -- and "debug" is in the
            // filename a reviewer downloads.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                // Warn rather than silently produce an unsigned APK that looks
                // legitimate in a release folder.
                logger.warn(
                    "No keystore.properties found: assembleRelease will produce an " +
                        "unsigned APK. The README explains how to create a key."
                )
                null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    
    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)

    // QR: embedded gives the capture activity + CameraX preview;
    // core is the encoder used to draw the pairing code on the receiver.
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    // Unit tests. Everything on the Android side was previously verified by hand
    // over adb; these make the parts that are easy to get quietly wrong --
    // token matching, payload encoding, resume offsets, the pre-flight decision
    // -- repeatable instead.
    testImplementation(libs.junit)
}
