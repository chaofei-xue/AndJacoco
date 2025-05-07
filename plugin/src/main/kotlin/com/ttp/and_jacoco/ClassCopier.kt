package com.ttp.and_jacoco

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarInputStream
import java.util.zip.ZipEntry

class ClassCopier(private val classDir: String, private val includes: List<String>?) {

    // className is expected in "com/example/package/MyClass" format (without .class extension)
    private fun isClassIncluded(className: String): Boolean {
        if (includes.isNullOrEmpty()) {
            return true // If no includes, process all
        }
        // includes patterns are like "com.example.mypackage.*" or "com.example.MySpecificClass"
        return includes.any { pattern ->
            val normalizedPattern = pattern.replace('.', '/')
            when {
                normalizedPattern.endsWith("/**") -> { // e.g., com/example/mypackage/** (match package and subpackages)
                    className.startsWith(normalizedPattern.dropLast(2)) 
                }
                normalizedPattern.endsWith("/*") -> { // e.g., com/example/mypackage/* (match package, not subpackages)
                    val basePackage = normalizedPattern.dropLast(2)
                    className.startsWith(basePackage) && className.count { it == '/' } == basePackage.count { it == '/' }
                }
                else -> { // e.g., com/example/MySpecificClass or com.example.* (prefix)
                    if (normalizedPattern.endsWith("*")) {
                        className.startsWith(normalizedPattern.dropLast(1))
                    } else {
                        className == normalizedPattern
                    }
                }
            }
        }
    }

    // fileInput is the original .class file from the build process
    // fileOutputTarget is where it should be copied within 'this.classDir' (e.g., projectDir/classes/com/example/MyClass.class)
    // originalBaseDir is the root directory from which fileInput originates (e.g., build/intermediates/.../classes)
    fun doClass(fileInput: File, fileOutputTarget: File, originalBaseDir: File) {
        if (!fileInput.name.endsWith(".class")) return

        val className = fileInput.absolutePath
            .substringAfter(originalBaseDir.absolutePath + File.separator)
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')

        if (isClassIncluded(className)) {
            println("[ClassCopier] Copying class: ${fileInput.path} to ${fileOutputTarget.path} (matched include: $className)")
            try {
                fileOutputTarget.parentFile?.mkdirs()
                Files.copy(fileInput.toPath(), fileOutputTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                System.err.println("[ClassCopier] Error copying class ${fileInput.path}: ${e.message}")
                e.printStackTrace()
            }
        } else {
            // println("[ClassCopier] Skipping class (not included): $className from ${fileInput.path}")
        }
    }

    fun doJar(jarInputFile: File, @Suppress("UNUSED_PARAMETER") ignoredJarOutputFile: File?) { // ignoredJarOutputFile is null when called from JacocoTransform
        println("[ClassCopier] Extracting from JAR: ${jarInputFile.path} to directory $classDir")
        try {
            JarInputStream(FileInputStream(jarInputFile)).use { jis ->
                var entry: ZipEntry? = jis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".class")) {
                        val className = entry.name.removeSuffix(".class") // entry.name is already like com/example/MyClass.class
                        if (isClassIncluded(className)) {
                            val targetFile = File(classDir, entry.name)
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { fos ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var bytesRead: Int
                                while (jis.read(buffer).also { bytesRead = it } != -1) {
                                    fos.write(buffer, 0, bytesRead)
                                }
                            }
                            println("[ClassCopier] Extracted and copied from JAR: ${entry.name} to ${targetFile.path}")
                        }
                    }
                    jis.closeEntry()
                    entry = jis.nextEntry
                }
            }
        } catch (e: IOException) {
            System.err.println("[ClassCopier] Error processing JAR ${jarInputFile.path}: ${e.message}")
            e.printStackTrace()
        }
    }
} 