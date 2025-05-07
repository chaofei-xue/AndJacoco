package com.ttp.and_jacoco

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.ttp.and_jacoco.extension.JacocoExtension
import com.ttp.and_jacoco.task.BranchDiffTask
import com.ttp.and_jacoco.util.Utils
import org.gradle.api.Project
import org.jacoco.core.diff.DiffAnalyzer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections

class JacocoTransform(private val project: Project, private val jacocoExtension: JacocoExtension) : Transform() {

    override fun getName(): String {
        return "jacoco"
    }

    override fun getInputTypes(): Set<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun getScopes(): MutableSet<QualifiedContent.Scope> {
        return mutableSetOf<QualifiedContent.Scope>(
            QualifiedContent.Scope.PROJECT,
            QualifiedContent.Scope.SUB_PROJECTS,
            QualifiedContent.Scope.EXTERNAL_LIBRARIES
        )
    }

    override fun isIncremental(): Boolean {
        return true
    }

    @Throws(TransformException::class, InterruptedException::class, IOException::class)
    override fun transform(
        context: Context,
        inputs: Collection<TransformInput>,
        referencedInputs: Collection<TransformInput>,
        outputProvider: TransformOutputProvider?,
        isIncremental: Boolean
    ) {
        val transformInvocation = object : TransformInvocation {
            override fun getContext(): Context = context
            override fun getInputs(): Collection<TransformInput> = inputs
            override fun getReferencedInputs(): Collection<TransformInput> = referencedInputs
            override fun getSecondaryInputs(): Collection<SecondaryInput> = Collections.emptyList()
            override fun getOutputProvider(): TransformOutputProvider? = outputProvider
            override fun isIncremental(): Boolean = isIncremental
        }
        doTransform(transformInvocation)
    }

    @Throws(TransformException::class, InterruptedException::class, IOException::class)
    fun transform(transformInvocation: TransformInvocation) {
        doTransform(transformInvocation)
    }

    private fun doTransform(transformInvocation: TransformInvocation) {
        val dirInputs = mutableSetOf<DirectoryInput>()
        val jarInputs = mutableSetOf<JarInput>()

        if (!transformInvocation.isIncremental) {
            transformInvocation.outputProvider?.deleteAll()
        }

        transformInvocation.inputs.forEach { input ->
            input.directoryInputs.forEach { dirInput ->
                dirInputs.add(dirInput)
            }
            input.jarInputs.forEach { jarInput ->
                jarInputs.add(jarInput)
            }
        }

        if (dirInputs.isNotEmpty() || jarInputs.isNotEmpty()) {
            if (jacocoExtension.jacocoEnable) {
                copy(transformInvocation, dirInputs, jarInputs)
                gitPush(jacocoExtension.gitPushShell, "jacoco auto commit")
                val branchDiffTask = project.tasks.findByName("generateReport") as? BranchDiffTask
                branchDiffTask?.pullDiffClassesInternal()
            }
            inject(transformInvocation, dirInputs, jarInputs)
        }
    }

    private fun copy(
        transformInvocation: TransformInvocation,
        dirInputs: Set<DirectoryInput>,
        jarInputs: Set<JarInput>
    ) {
        val classDir = "${project.projectDir}/classes"
        val copier = ClassCopier(classDir, jacocoExtension.includes)
        if (!transformInvocation.isIncremental) {
            deleteDirectory(File(classDir))
        }

        dirInputs.forEach { dirInput ->
            val originalBaseDir = dirInput.file
            if (transformInvocation.isIncremental) {
                dirInput.changedFiles.forEach { (fileInput, fileStatus) ->
                    val fileOutputJacoco = File(fileInput.absolutePath.replace(originalBaseDir.absolutePath, classDir))
                    when (fileStatus) {
                        Status.ADDED, Status.CHANGED -> {
                            if (fileInput.isDirectory) {
                                return@forEach // continue
                            }
                            copier.doClass(fileInput, fileOutputJacoco, originalBaseDir)
                        }
                        Status.REMOVED -> {
                            if (fileOutputJacoco.exists()) {
                                if (fileOutputJacoco.isDirectory) {
                                    fileOutputJacoco.deleteRecursively()
                                } else {
                                    fileOutputJacoco.delete()
                                }
                                println("REMOVED output file Name:${fileOutputJacoco.name}")
                            }
                        }
                        Status.NOTCHANGED -> {
                            // No action needed
                        }
                    }
                }
            } else {
                originalBaseDir.walkTopDown().filter { it.isFile }.forEach { fileInput ->
                    val fileOutputJacoco = File(fileInput.absolutePath.replace(originalBaseDir.absolutePath, classDir))
                    copier.doClass(fileInput, fileOutputJacoco, originalBaseDir)
                }
            }
        }

        jarInputs.forEach { jarInput ->
            val jarInputFile = jarInput.file
            copier.doJar(jarInputFile, null)
        }
    }

    private fun inject(
        transformInvocation: TransformInvocation,
        dirInputs: Set<DirectoryInput>,
        jarInputs: Set<JarInput>
    ) {
        val injector = ClassInjector(jacocoExtension.includes)
        dirInputs.forEach { dirInput ->
            val dirOutput = transformInvocation.outputProvider!!.getContentLocation(
                dirInput.name,
                dirInput.contentTypes,
                dirInput.scopes,
                Format.DIRECTORY
            )
            dirOutput.mkdirs()
            val originalBaseDir = dirInput.file

            if (transformInvocation.isIncremental) {
                dirInput.changedFiles.forEach { (fileInput, fileStatus) ->
                    val fileOutputTransform = File(fileInput.absolutePath.replace(originalBaseDir.absolutePath, dirOutput.absolutePath))
                    fileOutputTransform.parentFile.mkdirs()
                    when (fileStatus) {
                        Status.ADDED, Status.CHANGED -> {
                            if (fileInput.isDirectory) {
                                return@forEach // continue
                            }
                            if (jacocoExtension.jacocoEnable && DiffAnalyzer.getInstance().containsClass(getClassName(fileInput))) {
                                injector.doClass(fileInput, fileOutputTransform, originalBaseDir)
                            } else {
                                Files.copy(fileInput.toPath(), fileOutputTransform.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                        Status.REMOVED -> {
                            if (fileOutputTransform.exists()) {
                                if (fileOutputTransform.isDirectory) {
                                    fileOutputTransform.deleteRecursively()
                                } else {
                                    fileOutputTransform.delete()
                                }
                                println("REMOVED output file Name:${fileOutputTransform.name}")
                            }
                        }
                        Status.NOTCHANGED -> {
                           // No action needed
                        }
                    }
                }
            } else {
                originalBaseDir.walkTopDown().filter { it.isFile }.forEach { fileInput ->
                    val fileOutputTransform = File(fileInput.absolutePath.replace(originalBaseDir.absolutePath, dirOutput.absolutePath))
                    fileOutputTransform.parentFile.mkdirs()
                    if (jacocoExtension.jacocoEnable && DiffAnalyzer.getInstance().containsClass(getClassName(fileInput))) {
                        injector.doClass(fileInput, fileOutputTransform, originalBaseDir)
                    } else {
                        Files.copy(fileInput.toPath(), fileOutputTransform.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        jarInputs.forEach { jarInput ->
            val jarInputFile = jarInput.file
            val jarOutputFile = transformInvocation.outputProvider!!.getContentLocation(
                jarInputFile.name + "_" + jarInput.file.absolutePath.hashCode(), 
                jarInput.contentTypes,
                jarInput.scopes,
                Format.JAR
            )
            jarOutputFile.parentFile.mkdirs()

            if (jarInput.status == Status.REMOVED) {
                if (jarOutputFile.exists()) {
                    jarOutputFile.delete()
                }
            } else if (jarInput.status == Status.ADDED || jarInput.status == Status.CHANGED || !transformInvocation.isIncremental) {
                 if (jacocoExtension.jacocoEnable) {
                    injector.doJar(jarInputFile, jarOutputFile)
                } else {
                    Files.copy(jarInputFile.toPath(), jarOutputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteDirectory(directory: File) {
        if (directory.exists()) {
            directory.deleteRecursively()
        }
    }

    private fun gitPush(shell: String?, commitMsg: String) {
        if (shell.isNullOrBlank()) {
            println("Git push shell command is not configured.")
            return
        }
        println("jacoco 执行git命令")

        val cmds: Array<String> = if (Utils.isWindows()) {
            arrayOf(jacocoExtension.gitBashPath!!, shell, commitMsg)
        } else {
            arrayOf(shell, commitMsg)
        }
        println("cmds=${cmds.joinToString(" ")}")
        try {
            val process = Runtime.getRuntime().exec(cmds)
            val result = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()

            println("jacoco git succ :$result")
            println("jacoco git error :$error")
            process.waitFor()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun getClassName(file: File): String {
        var name = file.name
        if (name.endsWith(".class")) {
            var path = file.absolutePath 
            
            val buildIntermediates = "build${File.separator}intermediates"
            val tmpKotlinClasses = "tmp${File.separator}kotlin-classes"
            val buildClasses = "build${File.separator}classes"

            var relativePath: String? = null

            if (path.contains(buildIntermediates)) {
                val classesMarker = "classes" + File.separator
                val startIndex = path.indexOf(classesMarker)
                if (startIndex != -1) {
                    relativePath = path.substring(startIndex + classesMarker.length)
                }
            } else if (path.contains(tmpKotlinClasses)) {
                val kotlinClassesMarker = tmpKotlinClasses + File.separator
                val variantPathStart = path.indexOf(kotlinClassesMarker)
                if(variantPathStart != -1) {
                    val afterKotlinClasses = path.substring(variantPathStart + kotlinClassesMarker.length)
                    relativePath = afterKotlinClasses.substringAfter(File.separator)
                }
            } else if (path.contains(buildClasses)) {
                val classesMarker = buildClasses + File.separator
                val startIndex = path.indexOf(classesMarker)
                if(startIndex != -1) {
                    var afterBuildClasses = path.substring(startIndex + classesMarker.length)
                    val targetPathKeywords = listOf("java${File.separator}main", "kotlin${File.separator}main", "main")
                    for (keyword in targetPathKeywords) {
                        if (afterBuildClasses.startsWith(keyword)) {
                             afterBuildClasses = afterBuildClasses.substringAfter(keyword + File.separator)
                             break
                        }
                    }
                    relativePath = afterBuildClasses
                }
            }

            if (relativePath != null) {
                 name = relativePath.removeSuffix(".class").replace(File.separatorChar, '/')
            } else {
                name = file.name.removeSuffix(".class")
                println("[JacocoTransform.getClassName] Warning: Could not determine relative class path for ${file.path}. Using simple name: $name")
            }
        }
        return name
    }
} 