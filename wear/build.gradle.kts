import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val privateSigningProperties = Properties()
val privateSigningFile = file("${System.getProperty("user.home")}/.private-vault-signing/signing.properties")
if (privateSigningFile.exists()) privateSigningFile.inputStream().use { privateSigningProperties.load(it) }

android {
    namespace = "com.privatevault.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.application.private_vault"
        minSdk = 30
        targetSdk = 36
        versionCode = 360012
        versionName = "2.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (privateSigningFile.exists()) {
            create("privateRelease") {
                storeFile = file(privateSigningProperties.getProperty("storeFile"))
                // Security review L-14: the password comes from Credential Manager through
                // scripts/release-build.ps1. A password still in the file keeps working.
                val signingPassword = System.getenv("NUVORI_SIGNING_PASSWORD")
                storePassword = privateSigningProperties.getProperty("storePassword") ?: signingPassword
                keyAlias = privateSigningProperties.getProperty("keyAlias")
                keyPassword = privateSigningProperties.getProperty("keyPassword") ?: signingPassword
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (privateSigningFile.exists()) signingConfig = signingConfigs.getByName("privateRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":watchcommon"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    // Upgrade the old Fragment dependency pulled in by Play Services Wearable.
    implementation("androidx.fragment:fragment:1.8.2")
    // Wear OS components: time text, round-screen lists, crown scrolling and swipe-to-go-back.
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.5")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.5")
}
