plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.io.File
import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun propLocalOrEnv(key: String): String? {
    val local = localProps.getProperty(key)?.trim()
    if (!local.isNullOrEmpty()) return local
    val env = System.getenv(key)?.trim()
    if (!env.isNullOrEmpty()) return env
    return null
}

val releaseStoreFileRaw = propLocalOrEnv("LXB_RELEASE_STORE_FILE")
val releaseStorePassword = propLocalOrEnv("LXB_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = propLocalOrEnv("LXB_RELEASE_KEY_ALIAS")
val releaseKeyPassword = propLocalOrEnv("LXB_RELEASE_KEY_PASSWORD")

val hasCustomReleaseSigning = !releaseStoreFileRaw.isNullOrBlank()
        && !releaseStorePassword.isNullOrBlank()
        && !releaseKeyAlias.isNullOrBlank()
        && !releaseKeyPassword.isNullOrBlank()

val releaseStoreFile: File? = releaseStoreFileRaw?.let {
    val f = File(it)
    if (f.isAbsolute) f else rootProject.file(it)
}

android {
    namespace = "com.example.lxb_ignition"
    compileSdk {
        version = release(36)
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("../lxb-core/build/libs")
        }
    }


    defaultConfig {
        applicationId = "com.example.lxb_ignition"
        minSdk = 30
        targetSdk = 36
        versionCode = 301
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCustomReleaseSigning && releaseStoreFile != null && releaseStoreFile.exists()) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasCustomReleaseSigning
                && releaseStoreFile != null
                && releaseStoreFile.exists()
            ) {
                signingConfigs.getByName("release")
            } else {
                // Fallback keeps local release artifacts installable even
                // when a custom release keystore is not configured.
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
            "META-INF/NOTICE.md",
            "META-INF/NOTICE",
            "META-INF/LICENSE",
            "META-INF/*.kotlin_module"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(project(":lxb-core"))

    // ViewModel for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")

    // Shizuku - 特权 shell 执行
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // OkHttp - 发送需求到远端服务器
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// 显式声明 assets 合并任务依赖 lxb-core:jar，避免 Gradle 任务顺序不确定
afterEvaluate {
    // Generate lxb-core-dex.jar before app build/install.
    tasks.matching { it.name == "preBuild" || it.name == "installDebug" }
        .configureEach { dependsOn(":lxb-core:buildDex") }

    // Asset merge must wait for dex jar generation as well.
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach { dependsOn(":lxb-core:buildDex") }
}
