plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Лёгкая сборка без перевода на устройстве (~3 МБ вместо ~20): ./gradlew assembleDebug -Plite
val lite = project.hasProperty("lite")

android {
    namespace = "com.example.translit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.translit"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.5"
        // перевод без интернета (ML Kit) — нативная библиотека только для телефонов (ARM)
        if (!lite) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    sourceSets {
        getByName("main").java.srcDir(if (lite) "src/lite/java" else "src/mlkit/java")
    }

    packaging {
        // нативные библиотеки — сжатыми: APK меньше примерно вдвое
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Скриншоты клавиатуры (Robolectric, нативная графика) — только по запросу:
            // ./gradlew testDebugUnitTest -Pscreenshots  →  app/build/screenshots/*.png
            if (!project.hasProperty("screenshots")) test.exclude("**/ScreenshotTest*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    if (!lite) {
        // перевод без интернета и определение языка (модели языков скачиваются по запросу)
        implementation("com.google.mlkit:translate:17.0.3")
        implementation("com.google.mlkit:language-id:17.0.6")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
