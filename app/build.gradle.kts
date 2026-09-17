import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val privateSigningProperties = Properties()
val privateSigningFile = file("${System.getProperty("user.home")}/.private-vault-signing/signing.properties")
if (privateSigningFile.exists()) privateSigningFile.inputStream().use { privateSigningProperties.load(it) }

android {
    namespace = "com.privatevault.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.privatevault.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 22
        versionName = "1.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (privateSigningFile.exists()) {
            create("privateRelease") {
                storeFile = file(privateSigningProperties.getProperty("storeFile"))
                storePassword = privateSigningProperties.getProperty("storePassword")
                keyAlias = privateSigningProperties.getProperty("keyAlias")
                keyPassword = privateSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (privateSigningFile.exists()) signingConfig = signingConfigs.getByName("privateRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation("com.eatthepath:java-otp:1.0.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.sqlite:sqlite:2.4.0")
    // 4.6.1 is the newest SQLCipher Android line compatible with this machine's API 34 SDK.
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.devnied.emvnfccard:library:3.2.0")
    // The EMV parser must never log card responses, even in debug builds.
    implementation("org.slf4j:slf4j-nop:1.7.36")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.6.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
}
