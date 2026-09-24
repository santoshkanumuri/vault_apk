plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("com.eatthepath:java-otp:1.0.0")
    api("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("junit:junit:4.13.2")
}
