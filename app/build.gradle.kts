plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.btcsignal.app"
    compileSdk = 34

    defaultConfig {
        // Deliberately different again from the previous "Reversal-Zone" build's
        // applicationId ("com.btcsignal.app.reversal", itself different from the
        // "Alpha" build's "com.btcsignal.app.alpha") so this Gold-Zone build installs as
        // its OWN separate app alongside those already-installed earlier builds, instead
        // of overwriting them. Android treats applicationId as the app's on-device
        // identity; namespace only affects the generated R/BuildConfig package, so it's
        // left unchanged.
        applicationId = "com.btcsignal.app.goldzone"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "5.0.0-goldzone"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose (BOM)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (settings persistence)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking: OkHttp (REST + WebSocket). JSON parsing uses org.json (bundled in Android)
    // deliberately, to avoid pulling in an extra serialization plugin/toolchain.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager (periodic reconciliation / restart safety net)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Charts (lightweight, pure-Compose line/bar charts — no heavy chart lib dependency)
    // Implemented natively in ui/components/Charts.kt using Canvas, so no extra dependency required.

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303") // real org.json impl for JVM unit tests (StrategyRegistry parsing)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
