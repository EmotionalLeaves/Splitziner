plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// versionName comes from the top CHANGELOG.md heading in CI (## v1.2.3 -> 1.2.3), else this default.
val appVersionName: String = (findProperty("versionName") as String?) ?: "0.0.1"
val appVersionCode: Int = appVersionName.split(".").map { it.toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }

android {
    namespace = "io.github.emotionalleaves.splitziner"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.emotionalleaves.splitziner"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A deliberately PUBLIC key, committed to the repo, so every build installs over the last one
        // without uninstalling. Fine for a sideloaded hobby app; never reuse it for a store release.
        create("public") {
            storeFile = rootProject.file("keystore/splitziner-public.jks")
            storePassword = "splitziner-public"
            keyAlias = "splitziner"
            keyPassword = "splitziner-public"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("public")
        }
        debug {
            signingConfig = signingConfigs.getByName("public")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1") }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    testImplementation(libs.junit)
    // On-device smoke test, run on an emulator in CI.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
