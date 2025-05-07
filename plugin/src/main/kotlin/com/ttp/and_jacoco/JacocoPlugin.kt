package com.ttp.and_jacoco

import com.android.build.gradle.AppExtension
import com.ttp.and_jacoco.extension.JacocoExtension
import com.ttp.and_jacoco.task.BranchDiffTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException

class JacocoPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val jacocoExtension = project.extensions.create("jacocoCoverageConfig", JacocoExtension::class.java)

        project.configurations.all { configuration ->
            val name = configuration.name
            if (name != "implementation" && name != "compile") {
                return@all
            }
            //为Project加入agent依赖
//            configuration.dependencies.add(project.dependencies.create('com.ttp.jacoco:rt:0.0.5'))
        }

        val android = project.extensions.findByName("android")

        if (android is AppExtension) {
            val jacocoTransform = JacocoTransform(project, jacocoExtension)
            android.registerTransform(jacocoTransform)
            // throw an exception in instant run mode
            android.applicationVariants.all { variant ->
                val variantName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                try {
                    val instantRunTask = project.tasks.findByName("transformClassesWithInstantRunFor${variantName}")
                    if (instantRunTask != null) {
                        throw GradleException("不支持instant run")
                    }
                } catch (e: UnknownTaskException) {
                    // Ignored
                }
            }
        }

        project.afterEvaluate {
            val androidExtension = project.extensions.findByName("android") as? AppExtension
            androidExtension?.applicationVariants?.all { variant ->
                val variantName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

                if (project.tasks.findByName("generateReport") == null) {
                    val branchDiffTask = project.tasks.create("generateReport", BranchDiffTask::class.java)
                    branchDiffTask.group = "jacoco"
                    branchDiffTask.jacocoExtension = jacocoExtension
                }
            }
        }
    }
} 