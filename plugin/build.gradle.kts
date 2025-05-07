plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.github.ttpai"

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.10")
    implementation(gradleApi()) //gradle sdk
    implementation("com.android.tools.build:gradle:7.4.2")
    implementation("com.android.tools.build:transform-api:1.5.0")
    implementation(group = "org.ow2.asm", name = "asm", version = "8.0.1")
    implementation(group = "org.ow2.asm", name = "asm-commons", version = "8.0.1")
    implementation(group = "org.ow2.asm", name = "asm-tree", version = "8.0.1")
    implementation("org.jacoco:org.jacoco.report:0.8.5") {
        exclude(group = "org.jacoco", module = "org.jacoco.core")
    }
    implementation(project(":jacoco-core"))
//    implementation("com.github.ttpai.AndJacoco:jacoco-core:0.0.5")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// Add Kotlin JVM target
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}

sourceSets {
    main {
        java {
            srcDirs("src/main/kotlin")
        }
    }
}

// Configure the jar task to handle duplicates
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 配置发布到JitPack
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.ttpai"
            artifactId = "plugin"
            version = "0.0.7"

            from(components["java"])

            // 添加POM信息
            pom {
                name.set("AndJacoco Plugin")
                description.set("Android Jacoco Plugin for incremental code coverage")
                url.set("https://github.com/ttpai/AndJacoco")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("ttpai")
                        name.set("TTPai")
                        email.set("admin@ttpai.com")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "jitpack"
            url = uri("https://jitpack.io")
        }
    }
} 