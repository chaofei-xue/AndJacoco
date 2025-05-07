package com.ttp.and_jacoco

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.ttp.and_jacoco.extension.JacocoExtension
import com.ttp.and_jacoco.task.BranchDiffTask
import com.ttp.and_jacoco.util.Utils
import groovy.io.FileType
import org.codehaus.groovy.runtime.IOGroovyMethods
import org.gradle.api.Project
import org.jacoco.core.diff.DiffAnalyzer
import org.jacoco.core.tools.Util

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections

class JacocoTransform extends Transform {
    Project project

    JacocoExtension jacocoExtension

    JacocoTransform(Project project, JacocoExtension jacocoExtension) {
        this.project = project
        this.jacocoExtension = jacocoExtension
    }

    @Override
    String getName() {
        return "jacoco"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return true
    }

    // 从TransformInvocation获取信息
    void doTransform(TransformInvocation transformInvocation) {
        def dirInputs = new HashSet<>()
        def jarInputs = new HashSet<>()

        if (!transformInvocation.isIncremental()) {
            transformInvocation.getOutputProvider().deleteAll()
        }

        transformInvocation.inputs.each { input ->
            input.directoryInputs.each { dirInput ->
                dirInputs.add(dirInput)
            }
            input.jarInputs.each { jarInput ->
                jarInputs.add(jarInput)
            }
        }

        if (!dirInputs.isEmpty() || !jarInputs.isEmpty()) {
            if (jacocoExtension.jacocoEnable) {
                //copy class到 app/classes
                copy(transformInvocation, dirInputs, jarInputs, jacocoExtension.includes)
                //提交classes 到git
                gitPush(jacocoExtension.gitPushShell, "jacoco auto commit")
                //获取差异方法集
                BranchDiffTask branchDiffTask = project.tasks.findByName('generateReport')
                branchDiffTask.pullDiffClasses()
            }
            //对diff方法插入探针
            inject(transformInvocation, dirInputs, jarInputs, jacocoExtension.includes)
        }
    }

    // 实现必要的抽象方法
    void transform(Context context, Collection<TransformInput> inputs, Collection<TransformInput> referencedInputs, 
                  TransformOutputProvider outputProvider, boolean isIncremental) 
                  throws IOException, TransformException, InterruptedException {
        // 创建TransformInvocation并传递给doTransform
        TransformInvocation transformInvocation = new TransformInvocation() {
            @Override
            Context getContext() {
                return context
            }

            @Override
            Collection<TransformInput> getInputs() {
                return inputs
            }

            @Override
            Collection<TransformInput> getReferencedInputs() {
                return referencedInputs
            }

            @Override
            Collection<SecondaryInput> getSecondaryInputs() {
                return Collections.emptyList()
            }

            @Override
            TransformOutputProvider getOutputProvider() {
                return outputProvider
            }

            @Override
            boolean isIncremental() {
                return isIncremental
            }
        }
        doTransform(transformInvocation)
    }

    // 新版本API的transform方法
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        doTransform(transformInvocation)
    }

    def copy(TransformInvocation transformInvocation, def dirInputs, def jarInputs, List<String> includes) {
        def classDir = "${project.projectDir}/classes"
        ClassCopier copier = new ClassCopier(classDir, includes)
        if (!transformInvocation.incremental) {
            // 替换FileUtils.deletePath
            deleteDirectory(new File(classDir))
        }
        if (!dirInputs.isEmpty()) {
            dirInputs.each { dirInput ->
                if (transformInvocation.incremental) {
                    dirInput.changedFiles.each { entry ->
                        File fileInput = entry.getKey()
                        File fileOutputJacoco = new File(fileInput.getAbsolutePath().replace(dirInput.file.getAbsolutePath(), classDir))
                        Status fileStatus = entry.getValue()

                        switch (fileStatus) {
                            case Status.ADDED:
                            case Status.CHANGED:
                                if (fileInput.isDirectory()) {
                                    return // continue.
                                }
                                copier.doClass(fileInput, fileOutputJacoco)
                                break
                            case Status.REMOVED:
                                if (fileOutputJacoco.exists()) {
                                    if (fileOutputJacoco.isDirectory()) {
                                        fileOutputJacoco.deleteDir()
                                    } else {
                                        fileOutputJacoco.delete()
                                    }
                                    println("REMOVED output file Name:${fileOutputJacoco.name}")
                                }
                                break
                        }
                    }
                } else {
                    dirInput.file.traverse(type: FileType.FILES) { fileInput ->
                        File fileOutputJacoco = new File(fileInput.getAbsolutePath().replace(dirInput.file.getAbsolutePath(), classDir))
                        copier.doClass(fileInput, fileOutputJacoco)
                    }
                }
            }
        }

        if (!jarInputs.isEmpty()) {
            jarInputs.each { jarInput ->
                File jarInputFile = jarInput.file
                copier.doJar(jarInputFile, null)
            }
        }

    }

    def inject(TransformInvocation transformInvocation, def dirInputs, def jarInputs, List<String> includes) {

        ClassInjector injector = new ClassInjector(includes)
        if (!dirInputs.isEmpty()) {
            dirInputs.each { dirInput ->
                File dirOutput = transformInvocation.outputProvider.getContentLocation(dirInput.getName(),
                        dirInput.getContentTypes(), dirInput.getScopes(),
                        Format.DIRECTORY)
                // 替换FileUtils.mkdirs
                dirOutput.mkdirs()

                if (transformInvocation.incremental) {
                    dirInput.changedFiles.each { entry ->
                        File fileInput = entry.getKey()
                        File fileOutputTransForm = new File(fileInput.getAbsolutePath().replace(
                                dirInput.file.getAbsolutePath(), dirOutput.getAbsolutePath()))
                        // 替换FileUtils.mkdirs
                        fileOutputTransForm.parentFile.mkdirs()
                        Status fileStatus = entry.getValue()
                        switch (fileStatus) {
                            case Status.ADDED:
                            case Status.CHANGED:
                                if (fileInput.isDirectory()) {
                                    return // continue.
                                }
                                if (jacocoExtension.jacocoEnable &&
                                        DiffAnalyzer.getInstance().containsClass(getClassName(fileInput))) {
                                    injector.doClass(fileInput, fileOutputTransForm)
                                } else {
                                    // 替换FileUtils.copyFile
                                    Files.copy(fileInput.toPath(), fileOutputTransForm.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                }
                                break
                            case Status.REMOVED:
                                if (fileOutputTransForm.exists()) {
                                    if (fileOutputTransForm.isDirectory()) {
                                        fileOutputTransForm.deleteDir()
                                    } else {
                                        fileOutputTransForm.delete()
                                    }
                                    println("REMOVED output file Name:${fileOutputTransForm.name}")
                                }
                                break
                        }
                    }
                } else {
                    dirInput.file.traverse(type: FileType.FILES) { fileInput ->
                        File fileOutputTransForm = new File(fileInput.getAbsolutePath().replace(dirInput.file.getAbsolutePath(), dirOutput.getAbsolutePath()))
                        // 替换FileUtils.mkdirs
                        fileOutputTransForm.parentFile.mkdirs()
                        if (jacocoExtension.jacocoEnable &&
                                DiffAnalyzer.getInstance().containsClass(getClassName(fileInput))) {
                            injector.doClass(fileInput, fileOutputTransForm)
                        } else {
                            // 替换FileUtils.copyFile
                            Files.copy(fileInput.toPath(), fileOutputTransForm.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
        }

        if (!jarInputs.isEmpty()) {
            jarInputs.each { jarInput ->
                File jarInputFile = jarInput.file
                File jarOutputFile = transformInvocation.outputProvider.getContentLocation(
                        jarInputFile.getName(), getOutputTypes(), getScopes(), Format.JAR
                )

                // 替换FileUtils.mkdirs
                jarOutputFile.parentFile.mkdirs()

                switch (jarInput.status) {
                    case Status.NOTCHANGED:
                        if (transformInvocation.incremental) {
                            break
                        }
                    case Status.ADDED:
                    case Status.CHANGED:
                        if (jacocoExtension.jacocoEnable) {
                            injector.doJar(jarInputFile, jarOutputFile)
                        } else {
                            // 替换FileUtils.copyFile
                            Files.copy(jarInputFile.toPath(), jarOutputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        break
                    case Status.REMOVED:
                        if (jarOutputFile.exists()) {
                            jarOutputFile.delete()
                        }
                        break
                }
            }
        }
    }

    // 删除目录的辅助方法
    void deleteDirectory(File directory) {
        if (directory.exists()) {
            if (directory.isDirectory()) {
                File[] files = directory.listFiles()
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            deleteDirectory(file)
                        } else {
                            file.delete()
                        }
                    }
                }
            }
            directory.delete()
        }
    }

    def gitPush(String shell, String commitMsg) {
        println("jacoco 执行git命令")
//
        String[] cmds
        if (Utils.windows) {
            cmds = new String[3]
            cmds[0] = jacocoExtension.getGitBashPath()
            cmds[1] = shell
            cmds[2] = commitMsg
        } else {
            cmds = new String[2]
            cmds[0] = shell
            cmds[1] = commitMsg
        }
        println("cmds=" + cmds)
        Process pces = Runtime.getRuntime().exec(cmds)
        String result = IOGroovyMethods.getText(new BufferedReader(new InputStreamReader(pces.getIn())))
        String error = IOGroovyMethods.getText(new BufferedReader(new InputStreamReader(pces.getErr())))

        println("jacoco git succ :" + result)
        println("jacoco git error :" + error)

        pces.closeStreams()
    }

    String getUniqueHashName(File fileInput) {
        final String fileInputName = fileInput.getName()
        if (fileInput.isDirectory()) {
            return fileInputName
        }
        final String parentDirPath = fileInput.getParentFile().getAbsolutePath()
        final String pathMD5 = Util.getMD5(parentDirPath.getBytes())
        return "${pathMD5}_${fileInputName}"
    }

    String getClassName(File file) {
        String name = file.name
        if (name.endsWith('.class')) {
            String path = file.path.replace(file.name, '')

            if (path.contains('app/build/')) {
                int begin = path.indexOf("classes")
                int end = path.length()
                path = path.substring(begin, end)
            }

            if (path.contains("\\")) {
                path = path.replaceAll("\\\\", "/")
            }

            path = path.replaceAll("/", '.')
            name = path + name.replace(".class", '')
            name = name.replaceAll("classes.main.", '')
            name = name.replaceAll("classes.", '')

            // 增加将.替换为/的代码，以匹配getDiffAnalyzer中的containsClass方法的参数格式
            name = name.replaceAll("\\.", '/')
        }
        return name
    }
}