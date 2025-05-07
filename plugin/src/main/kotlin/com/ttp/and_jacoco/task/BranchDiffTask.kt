package com.ttp.and_jacoco.task

import com.ttp.and_jacoco.extension.JacocoExtension
import com.ttp.and_jacoco.report.ReportGenerator
import com.ttp.and_jacoco.util.Utils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.jacoco.core.data.MethodInfo
import org.jacoco.core.diff.DiffAnalyzer
import java.io.*
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

open class BranchDiffTask : DefaultTask() {
    var currentName: String? = null //当前分支名
    lateinit var jacocoExtension: JacocoExtension

    @TaskAction
    fun getDiffClass() {
        println("downloadEcData start")
        downloadEcData()
        println("downloadEcData end")

        //生成差异报告
        println("pullDiffClasses start")
        pullDiffClassesInternal()
        println("pullDiffClasses end")

        if (jacocoExtension.reportDirectory == null) {
            jacocoExtension.reportDirectory = "${project.buildDir.absolutePath}/outputs/report"
        }
        val generator = ReportGenerator(
            jacocoExtension.execDir,
            toFileList(jacocoExtension.classDirectories),
            toFileList(jacocoExtension.sourceDirectories),
            File(jacocoExtension.reportDirectory!!)
        )
        generator.create()
    }

    private fun toFileList(paths: List<String>?): List<File> {
        if (paths == null) return emptyList()
        return paths.map { File(it) }
    }

    internal fun pullDiffClassesInternal() {
        currentName = executeCommand("git name-rev --name-only HEAD")?.replace("\n", "")
        if (currentName?.contains("/") == true) {
            currentName = currentName!!.substring(currentName!!.lastIndexOf("/") + 1)
        }

        println("currentName:\n$currentName")
        //获得两个分支的差异文件
        val diff = executeCommand("git diff origin/${jacocoExtension.branchName} origin/$currentName --name-only")
        val diffFiles = getDiffFiles(diff)

        println("diffFiles size=${diffFiles.size}")
        writerDiffToFile(diffFiles)

        //两个分支差异文件的目录
        val currentDir = "${project.rootDir.parentFile}/temp/$currentName/app"
        val branchDir = "${project.rootDir.parentFile}/temp/${jacocoExtension.branchName}/app"

        project.delete(currentDir)
        project.delete(branchDir)
        File(currentDir).mkdirs()
        File(branchDir).mkdirs()

        //先把两个分支的所有class copy到temp目录
        copyBranchClass(jacocoExtension.branchName!!, branchDir)
        copyBranchClass(currentName!!, currentDir)
        //再根据diffFiles 删除不需要的class
        deleteOtherFile(diffFiles, branchDir)
        deleteOtherFile(diffFiles, currentDir)

        //删除空文件夹
        deleteEmptyDir(File(branchDir))
        deleteEmptyDir(File(currentDir))

        createDiffMethod(currentDir, branchDir)

        writerDiffMethodToFile()
    }

    private fun writerDiffToFile(diffFiles: List<String>) {
        val path = "${project.buildDir.absolutePath}/outputs/diff/diffFiles.txt"
        val parent = File(path).parentFile
        if (!parent.exists()) parent.mkdirs()

        println("writerDiffToFile size=${diffFiles.size} to >$path")

        FileOutputStream(path).use { fos ->
            for (str in diffFiles) {
                fos.write((str + "\n").toByteArray())
            }
        }
    }

    private fun writerDiffMethodToFile() {
        val path = "${project.buildDir.absolutePath}/outputs/diff/diffMethod.txt"

        println("writerDiffMethodToFile size=${DiffAnalyzer.getInstance().diffList.size} >$path")

        val file = File(path)
        if (!file.parentFile.exists()) {
            file.parentFile.mkdirs()
        }
        Files.write(Paths.get(path), DiffAnalyzer.getInstance().toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun deleteOtherFile(diffFiles: List<String>, dir: String) {
        readFiles(dir) { classFile ->
            val path = classFile.absolutePath.replace(dir, "app")
            //path= app/classes/com/example/jacoco_plugin/MyApp.class
            diffFiles.contains(path)
        }
    }

    private fun readFiles(dirPath: String, filter: (File) -> Boolean) {
        val file = File(dirPath)
        if (!file.exists()) {
            return
        }
        val files = file.listFiles()
        files?.forEach { classFile ->
            if (classFile.isDirectory) {
                readFiles(classFile.absolutePath, filter)
            } else {
                if (classFile.name.endsWith(".class")) {
                    if (!filter(classFile)) {
                        classFile.delete()
                    }
                } else {
                    classFile.delete()
                }
            }
        }
    }

    private fun copyBranchClass(branchName: String, targetDir: String) {
        val cmds: Array<String> = if (Utils.isWindows()) {
            arrayOf(
                jacocoExtension.gitBashPath!!,
                jacocoExtension.copyClassShell!!,
                branchName,
                project.rootDir.absolutePath,
                targetDir
            )
        } else {
            arrayOf(
                jacocoExtension.copyClassShell!!,
                branchName,
                project.rootDir.absolutePath,
                targetDir
            )
        }

        println("cmds=${cmds.joinToString(" ")}")
        val process = Runtime.getRuntime().exec(cmds)
        val result = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()

        println("copyClassShell succ :$result")
        println("copyClassShell error :$error")

        process.waitFor()
    }

    private fun createDiffMethod(currentDir: String, branchDir: String) {
        DiffAnalyzer.getInstance().reset()
        DiffAnalyzer.readClasses(currentDir, DiffAnalyzer.CURRENT)
        DiffAnalyzer.readClasses(branchDir, DiffAnalyzer.BRANCH)
        DiffAnalyzer.getInstance().diff()

        println("excludeMethod before diff.size=${DiffAnalyzer.getInstance().diffList.size}")

        //excludeMethod
        jacocoExtension.excludeMethod?.let { excludeClosure ->
            val iterator = DiffAnalyzer.getInstance().diffList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next() as MethodInfo // Cast to MethodInfo
                if (excludeClosure(info)) {
                    iterator.remove()
                }
            }
        }
        println("excludeMethod after diff.size=${DiffAnalyzer.getInstance().diffList.size}")
    }

    private fun deleteEmptyDir(dir: File) {
        val dirs = dir.listFiles { file -> file.isDirectory } ?: return
        for (d in dirs) {
            deleteEmptyDir(d)
        }
        if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) {
            dir.delete()
        }
    }

    private fun readIdList(file: File): List<String> {
        val list = mutableListOf<String>()
        try {
            BufferedReader(FileReader(file)).use { fis ->
                var line: String?
                while (fis.readLine().also { line = it } != null) {
                    if (line!!.contains("0x7f")) {
                        list.add(line!!)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun getDiffFiles(diff: String?): List<String> {
        val diffFiles = mutableListOf<String>()
        if (diff.isNullOrEmpty()) {
            return diffFiles
        }
        val strings = diff.split("\n")
        val classes = "/classes/"
        strings.forEach {
            if (it.endsWith(".class")) {
                val classPath = it.substring(it.indexOf(classes) + classes.length)
                if (isInclude(classPath)) {
                    val exclude = jacocoExtension.excludeClass?.invoke(it) ?: false
                    if (!exclude) {
                        diffFiles.add(it)
                    }
                }
            }
        }
        return diffFiles
    }

    private fun isInclude(classPath: String): Boolean {
        val includes = jacocoExtension.includes ?: return false
        for (str in includes) {
            if (classPath.startsWith(str.replace(".", "/"))) {
                return true
            }
        }
        return false
    }

    private fun downloadEcData() {
        if (jacocoExtension.execDir == null) {
            jacocoExtension.execDir = "${project.buildDir}/jacoco/code-coverage/"
        }
        File(jacocoExtension.execDir!!).mkdirs()

        val isSuccess = getUrlFile()
        if (!isSuccess) {
            println("downloadEcData getUrlFile false")
        }
    }

    private fun getUrlFile(): Boolean {
        if (jacocoExtension.host.isNullOrBlank()) {
            return false
        }

        val urlPath = "${jacocoExtension.host}/jacocoDiff/getecfile"
        try {
            println("getUrlFile urlpath=$urlPath")
            val url = URL(urlPath)
            val uc: URLConnection = url.openConnection()
            uc.doOutput = true
            uc.connect()
            uc.inputStream.use { inputStream ->
                val path = jacocoExtension.execDir + "jacoco.exec"
                File(path).parentFile.mkdirs()
                FileOutputStream(path).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var byteRead: Int
                    while (inputStream.read(buffer).also { byteRead = it } != -1) {
                        outputStream.write(buffer, 0, byteRead)
                    }
                }
            }
            println("getUrlFile success. path=${jacocoExtension.execDir}jacoco.exec")
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    // Helper function to execute shell commands
    private fun executeCommand(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output.trim()
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    // Removed getBuildType() as project.android is not directly available in DefaultTask
    // If needed, this variant information should be passed to the task or accessed differently.
} 