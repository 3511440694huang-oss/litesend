plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.qingchuan"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.qingchuan"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"
        // 无任何 native 依赖：APK 为纯 dex，天然同时支持 armeabi-v7a(32 位) 与 arm64-v8a。
        // 显式声明 ABI 过滤既无意义（没有 .so），也会造成误解，故不开启 splits.abi。
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("repoDebug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("repoDebug")
        }
        debug {
            signingConfig = signingConfigs.getByName("repoDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }
}

// 单测的 java.io.tmpdir 必须是可写目录：Termux 的 /tmp 不可写，
// 否则测试里创建的临时目录全失败（表现为 FileNotFoundException）。
tasks.withType<Test>().configureEach {
    val dir = layout.buildDirectory.dir("test-tmp").get().asFile
    dir.mkdirs()
    systemProperty("java.io.tmpdir", dir.absolutePath)
    maxHeapSize = "1g"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation("junit:junit:4.13.2")
}
