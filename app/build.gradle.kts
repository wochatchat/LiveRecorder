import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wochatchat.liverecorder"
    compileSdk = 34

    // Auto-increment version: read from version.properties (maintained by build script)
    val versionPropsFile = rootProject.file("version.properties")
    val (vCode, vName) = if (versionPropsFile.exists()) {
        val p = Properties().apply { load(versionPropsFile.inputStream()) }
        (p.getProperty("versionCode") ?: "1").toInt() to (p.getProperty("versionName") ?: "0.1.0")
    } else { 1 to "0.1.0" }

    defaultConfig {
        applicationId = "com.wochatchat.liverecorder"
        minSdk = 26
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }

    // Keystore decoded from repo secrets at CI build time (signing.properties,
    // git-ignored). Missing/incomplete → unsigned release APK, build still passes.
    val signingPropsFile = rootProject.file("signing.properties")
    if (signingPropsFile.exists()) {
        val props = Properties().apply { load(signingPropsFile.inputStream()) }
        val storeFile = props.getProperty("STORE_FILE")
        val storePassword = props.getProperty("STORE_PASSWORD")
        val keyAlias = props.getProperty("KEY_ALIAS")
        val keyPassword = props.getProperty("KEY_PASSWORD")
        if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
            signingConfigs {
                create("release") {
                    this.storeFile = rootProject.file(storeFile)
                    this.storePassword = storePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (signingPropsFile.exists()) {
                val props = Properties().apply { load(signingPropsFile.inputStream()) }
                if (props.getProperty("STORE_FILE") != null) {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    testOptions {
        // MonitorLoop 单测在 JVM 跑：android.util.Log 默认未 mock 会抛异常，
        // 打开 returnDefaultValues 让 Log 调用静默返回
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Networking (Phase 1: spider HTTP layer + stream download)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // org.json: 纯 Java 实现，单元测试时 org.json 在 JVM 上可用（生产代码走 Android Framework）
    testImplementation("org.json:json:20240303")
    // MockWebServer: StreamDownloader 的 JVM 单测（流式写文件 / 非 200 / 中途取消）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
