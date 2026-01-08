plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// 配置 JAR 打包任务
tasks.register<Jar>("buildServerJar") {
    archiveBaseName.set("lxb-core")
    archiveVersion.set("1.0.0")

    // ⭐ 确保先编译
    dependsOn("classes")

    // 指定 Main-Class
    manifest {
        attributes(
            "Main-Class" to "com.lxb.server.Main",
            "Manifest-Version" to "1.0"
        )
    }

    // ⭐ 明确指定从编译输出目录打包
    from(layout.buildDirectory.dir("classes/java/main"))

    // 设置输出目录
    destinationDirectory.set(file("${project.rootDir}/app/src/main/assets"))

    doFirst {
        println("📂 Packing from: ${layout.buildDirectory.dir("classes/java/main").get()}")
    }

    doLast {
        println("✅ lxb-core.jar built successfully at: ${archiveFile.get()}")
        println("📦 JAR size: ${archiveFile.get().asFile.length()} bytes")

        // 列出 JAR 内容以验证
        println("📋 Classes in JAR:")
        project.zipTree(archiveFile.get()).matching {
            include("**/*.class")
        }.files.forEach { file ->
            println("   ${file.name}")
        }
    }
}

// 让 build 任务自动触发 JAR 打包
tasks.named("build") {
    finalizedBy("buildServerJar")
}

