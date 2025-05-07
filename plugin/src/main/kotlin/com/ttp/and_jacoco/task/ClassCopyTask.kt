package com.ttp.and_jacoco.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import java.io.File
import org.gradle.api.file.DuplicatesStrategy

open class ClassCopyTask : DefaultTask() {

    var variantName: String? = null

    @TaskAction
    fun doCopy() {
        println("start copy classes")
        val javaDir = "${project.projectDir}${File.separator}classes${File.separator}java"
        val kotlinDir = "${project.projectDir}${File.separator}classes${File.separator}kotlin"
        project.delete(javaDir)
        project.mkdir(javaDir)
        project.delete(kotlinDir)
        project.mkdir(kotlinDir)
        val buildJavaDir = "${project.buildDir}${File.separator}intermediates${File.separator}javac${File.separator}${variantName}${File.separator}classes"
        val buildKotlinDir = "${project.buildDir}${File.separator}tmp${File.separator}kotlin-classes${File.separator}${variantName}"

        project.copy {
            it.from(buildJavaDir)
            it.into(javaDir)
            it.exclude(
                "**/R.class",
                "**/R$*.class",
                "**/BR.class",
                "**/Manifest*.*",
                "**/BuildConfig.class",
                "**/DataBinderMapperImpl.class", // Common DataBinding file
                "**/*\$ViewInjector*.*",
                "**/*\$ViewBinder*.*"
            )
            it.duplicatesStrategy = DuplicatesStrategy.EXCLUDE // Add duplicates strategy
        }

        project.copy {
            it.from(buildKotlinDir)
            it.into(kotlinDir)
            it.exclude("**/*.kotlin_module")
            it.duplicatesStrategy = DuplicatesStrategy.EXCLUDE // Add duplicates strategy
        }

        //由于class文件被ignore了，所以要加上-f
        try {
            val command = "git add -f ${project.projectDir}${File.separator}classes${File.separator}"
            println("Executing: $command")
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                System.err.println("Git add command failed with exit code $exitCode: $error")
            }
        } catch (e: Exception) {
            System.err.println("Error executing git add command: ${e.message}")
            e.printStackTrace()
        }
    }
} 