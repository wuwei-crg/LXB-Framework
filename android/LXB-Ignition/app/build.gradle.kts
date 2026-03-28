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

fun detectNdkRoot(): File? {
    val explicit = propLocalOrEnv("ANDROID_NDK_HOME") ?: localProps.getProperty("ndk.dir")
    if (!explicit.isNullOrBlank()) {
        val f = File(explicit)
        if (f.exists() && f.isDirectory) return f
    }
    val sdkRoot = propLocalOrEnv("ANDROID_HOME")
        ?: propLocalOrEnv("ANDROID_SDK_ROOT")
        ?: "${System.getProperty("user.home")}/AppData/Local/Android/Sdk"
    val ndkDir = File(sdkRoot, "ndk")
    if (ndkDir.exists() && ndkDir.isDirectory) {
        val versions = ndkDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }.orEmpty()
        if (versions.isNotEmpty()) return versions.first()
    }
    val ndkBundle = File(sdkRoot, "ndk-bundle")
    if (ndkBundle.exists() && ndkBundle.isDirectory) return ndkBundle
    return null
}

val starterSource = file("src/main/cpp/lxb_starter.c")
val starterOutputDir = rootProject.file("lxb-core/build/libs")

data class StarterTarget(
    val abi: String,
    val assetName: String,
    val clangCandidates: List<String>
)

val starterTargets = listOf(
    StarterTarget(
        abi = "arm64-v8a",
        assetName = "lxb-starter-arm64",
        clangCandidates = listOf(
            "aarch64-linux-android30-clang.cmd",
            "aarch64-linux-android30-clang.exe",
            "aarch64-linux-android30-clang"
        )
    ),
    StarterTarget(
        abi = "armeabi-v7a",
        assetName = "lxb-starter-armv7",
        clangCandidates = listOf(
            "armv7a-linux-androideabi30-clang.cmd",
            "armv7a-linux-androideabi30-clang.exe",
            "armv7a-linux-androideabi30-clang"
        )
    ),
    StarterTarget(
        abi = "x86_64",
        assetName = "lxb-starter-x86_64",
        clangCandidates = listOf(
            "x86_64-linux-android30-clang.cmd",
            "x86_64-linux-android30-clang.exe",
            "x86_64-linux-android30-clang"
        )
    ),
    StarterTarget(
        abi = "x86",
        assetName = "lxb-starter-x86",
        clangCandidates = listOf(
            "i686-linux-android30-clang.cmd",
            "i686-linux-android30-clang.exe",
            "i686-linux-android30-clang"
        )
    )
)

tasks.register("buildLxbStarters") {
    group = "build"
    description = "Build native lxb starter executables for arm64/armv7/x86/x86_64 and place them in app assets input dir."
    doLast {
        if (!starterSource.exists()) {
            throw GradleException("starter source missing: ${starterSource.absolutePath}")
        }
        val ndkRoot = detectNdkRoot()
        if (ndkRoot == null) {
            logger.warn("NDK not found; skip lxb starter build. Runtime will report missing starter.")
            return@doLast
        }
        val hostTag = if (System.getProperty("os.name").lowercase().contains("win")) {
            "windows-x86_64"
        } else if (System.getProperty("os.name").lowercase().contains("mac")) {
            "darwin-x86_64"
        } else {
            "linux-x86_64"
        }
        val toolchain = File(ndkRoot, "toolchains/llvm/prebuilt/$hostTag/bin")
        if (!toolchain.exists()) {
            throw GradleException("NDK toolchain not found: ${toolchain.absolutePath}")
        }
        fun findTool(vararg names: String): String {
            for (n in names) {
                val f = File(toolchain, n)
                if (f.exists()) return f.absolutePath
            }
            throw GradleException("Cannot find tool in ${toolchain.absolutePath}: ${names.joinToString()}")
        }
        val strip = runCatching {
            findTool("llvm-strip.exe", "llvm-strip")
        }.getOrNull()

        starterOutputDir.mkdirs()
        val built = mutableListOf<String>()
        for (target in starterTargets) {
            val clang = findTool(*target.clangCandidates.toTypedArray())
            val out = File(starterOutputDir, target.assetName)
            exec {
                commandLine(
                    clang,
                    "-O2",
                    "-fPIE",
                    "-pie",
                    starterSource.absolutePath,
                    "-o",
                    out.absolutePath
                )
            }
            if (!strip.isNullOrBlank()) {
                exec {
                    commandLine(strip, out.absolutePath)
                    isIgnoreExitValue = true
                }
            }
            if (!out.exists() || out.length() <= 0L) {
                throw GradleException("starter build produced empty output: ${out.absolutePath}")
            }
            built += "${target.abi}:${out.name}(${out.length()}B)"
        }
        logger.lifecycle("Built native starters: ${built.joinToString(", ")}")
    }
}

tasks.register("buildLxbStarterArm64") {
    group = "build"
    description = "Legacy alias; builds all ABI starter binaries."
    dependsOn("buildLxbStarters")
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
        versionCode = 403
        versionName = "0.4.3"

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
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Make launcher label explicit to avoid confusing with release app.
            resValue("string", "app_name", "LXB Ignition (Debug)")
        }

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

    // OkHttp - 发送需求到远端服务器
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.MuntashirAkon:libadb-android:master-SNAPSHOT")
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
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
        .configureEach {
            dependsOn(":lxb-core:buildDex")
            dependsOn("buildLxbStarters")
        }

    // Asset merge must wait for dex jar generation as well.
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach {
            dependsOn(":lxb-core:buildDex")
            dependsOn("buildLxbStarters")
        }
}
