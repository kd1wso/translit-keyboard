plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.translit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.translit"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.1"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
