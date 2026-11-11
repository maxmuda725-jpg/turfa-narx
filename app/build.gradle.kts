plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "uz.turfa.narx"
    compileSdk = 35
    defaultConfig {
        applicationId = "uz.turfa.narx"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Faqat telefon protsessorlari: APK kichikroq bo'ladi
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Rasm yuklash kaliti: GitHub Actions'da RASM_KEY secret'dan hosil qilinadi, kodda saqlanmaydi
        buildConfigField("String", "RASM_APP_KEY", "\"${System.getenv("RASM_APP_KEY") ?: ""}\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // Model faylini siqmasdan saqlash (MediaPipe uni to'g'ridan-to'g'ri o'qiydi)
    androidResources { noCompress += "tflite" }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("androidx.core:core:1.13.1")
}
