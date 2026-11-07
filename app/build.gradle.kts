import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * The Play upload key, kept outside the repo. `local.properties` (not in git) names a properties
 * file with storeFile, storePassword, keyAlias and keyPassword. Without it, release builds are
 * simply left unsigned.
 */
val uploadKey: Properties? = run {
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(local::load)
    val path = local.getProperty("signing.properties") ?: return@run null
    val file = file(path).takeIf { it.exists() } ?: return@run null
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "com.fitc.ssmgen4"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fitc.ssmgen4"
        // Play's automatic protection needs 24 (androidx.car.app needs 23); Android Auto itself
        // needs a newer phone anyway.
        minSdk = 24
        // Raised from 34 for Google Play distribution (Internal App Sharing to the car).
        // 35+ makes the phone screen draw edge-to-edge; MainActivity pads for the bars.
        targetSdk = 36
        // Play needs a higher versionCode for every upload to a testing track.
        versionCode = 1
        versionName = "0.1.0"
        multiDexEnabled = true
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Android Auto. `app` is the Car App Library API (CarAppService, Screen, templates,
    // SurfaceCallback); `app-projected` adds the phone-projected host integration.
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    // A real XmlPullParser for JVM tests of the logger-definition parser.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
