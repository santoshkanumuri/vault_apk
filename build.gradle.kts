buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.9.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.20")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.20-1.0.24")
    }
}
