plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Ya tienes este plugin por alias; no agregues otra línea con id(...) y versión
    alias(libs.plugins.kotlin.compose) // <- este es el Compose Compiler plugin
}

android {
    namespace = "com.oliver.smartwatchdata"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oliver.smartwatchdata"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Recomendado con Kotlin 2.x
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Obsoleto/innecesario
    // useLibrary("wear-sdk")

    buildFeatures {
        compose = true
    }

    // Con el plugin de Compose moderno NO necesitas kotlinCompilerExtensionVersion
    // composeOptions { kotlinCompilerExtensionVersion = "..." }  <-- NO lo pongas
}

dependencies {
    implementation(libs.play.services.wearable)

    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)

    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")


}

