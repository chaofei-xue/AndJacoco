package com.ttp.and_jacoco

import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ClassInjector(private val includes: List<String>?) {

    private val instrumenter = Instrumenter(OfflineInstrumentationAccessGenerator())

    // className is expected in "com/example/package/MyClass" format (without .class extension)
    private fun isClassIncluded(className: String): Boolean {
        if (includes.isNullOrEmpty()) {
            return true // If no includes, instrument all that are passed to it
        }
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

    // fileInput: original class file
    // fileOutputTransform: where the instrumented class should be written
    // originalBaseDir: needed to derive className for include check (though less critical here if JacocoTransform filters first)
    fun doClass(fileInput: File, fileOutputTransform: File, @Suppress("UNUSED_PARAMETER") originalBaseDir: File) {
         if (!fileInput.name.endsWith(".class")) { // Should not happen if JacocoTransform filters correctly
            System.err.println("[ClassInjector] Attempted to instrument non-class file: ${fileInput.path}. Copying instead.")
            try {
                 fileOutputTransform.parentFile?.mkdirs()
                 Files.copy(fileInput.toPath(), fileOutputTransform.toPath(), StandardCopyOption.REPLACE_EXISTING) // Use Files.copy from Java NIO
            } catch (e: IOException) {
                System.err.println("[ClassInjector] Error copying non-class file ${fileInput.path}: ${e.message}")
            }
            return
        }
        // JacocoTransform already filters based on DiffAnalyzer. 
        // The isClassIncluded check here acts as an additional filter based on injector's own `includes` list.
        val classNameForFilter = fileInput.absolutePath
            .substringAfter(originalBaseDir.absolutePath + File.separator)
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')

        if (!isClassIncluded(classNameForFilter)) {
            // println("[ClassInjector] Skipping instrumentation (not in includes): $classNameForFilter. Copying original.")
            try {
                fileOutputTransform.parentFile?.mkdirs()
                Files.copy(fileInput.toPath(), fileOutputTransform.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                 System.err.println("[ClassInjector] Error copying non-included class ${fileInput.path}: ${e.message}")
            }
            return
        }

        println("[ClassInjector] Instrumenting class: ${fileInput.path} to ${fileOutputTransform.path}")
        try {
            fileOutputTransform.parentFile?.mkdirs()
            FileInputStream(fileInput).use { fis ->
                FileOutputStream(fileOutputTransform).use { fos ->
                    instrumenter.instrument(fis, fos, fileInput.name)
                }
            }
        } catch (e: IOException) {
            System.err.println("[ClassInjector] Error instrumenting class ${fileInput.path}: ${e.message}")
            e.printStackTrace()
            // Attempt to copy original file if instrumentation fails
            try {
                println("[ClassInjector] Fallback: Copying original class ${fileInput.path} due to instrumentation error.")
                Files.copy(fileInput.toPath(), fileOutputTransform.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (copyEx: IOException) {
                System.err.println("[ClassInjector] Error in fallback copy for class ${fileInput.path}: ${copyEx.message}")
            }
        }
    }

    fun doJar(jarInputFile: File, jarOutputFile: File) {
        println("[ClassInjector] Instrumenting JAR: ${jarInputFile.path} to ${jarOutputFile.path}")
        try {
            jarOutputFile.parentFile?.mkdirs()
            JarInputStream(FileInputStream(jarInputFile)).use { jis ->
                JarOutputStream(FileOutputStream(jarOutputFile)).use { jos ->
                    var entry: JarEntry? = jis.nextJarEntry
                    while (entry != null) {
                        jos.putNextEntry(JarEntry(entry.name))
                        if (!entry.isDirectory && entry.name.endsWith(".class")) {
                            val className = entry.name.removeSuffix(".class")
                            if (isClassIncluded(className)) {
                                println("[ClassInjector] Instrumenting in JAR: ${entry.name}")
                                // Instrument and write to jos. Jis is the source for this entry.
                                val tempEntryBytes = jis.readBytes() // Read current entry into memory
                                val instrumentedBytes = instrumenter.instrument(tempEntryBytes, entry.name)
                                jos.write(instrumentedBytes)
                            } else {
                                // Copy as-is if not included for instrumentation
                                jis.copyTo(jos) // Copies current entry from jis to jos
                            }
                        } else {
                            // Copy non-class files or non-included classes as-is
                            jis.copyTo(jos) // Copies current entry from jis to jos
                        }
                        jos.closeEntry()
                        entry = jis.nextJarEntry
                    }
                }
            }
        } catch (e: IOException) {
            System.err.println("[ClassInjector] Error instrumenting JAR ${jarInputFile.path}: ${e.message}")
            e.printStackTrace()
             // Attempt to copy original JAR if instrumentation fails
            try {
                println("[ClassInjector] Fallback: Copying original JAR ${jarInputFile.path} due to instrumentation error.")
                Files.copy(jarInputFile.toPath(), jarOutputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (copyEx: IOException) {
                System.err.println("[ClassInjector] Error in fallback copy for JAR ${jarInputFile.path}: ${copyEx.message}")
            }
        }
    }
} 