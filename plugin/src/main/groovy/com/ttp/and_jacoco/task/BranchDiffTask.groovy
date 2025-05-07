package com.ttp.and_jacoco.task


import com.ttp.and_jacoco.extension.JacocoExtension
import com.ttp.and_jacoco.report.ReportGenerator
import com.ttp.and_jacoco.util.Utils
import org.codehaus.groovy.runtime.IOGroovyMethods
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.jacoco.core.data.MethodInfo
import org.jacoco.core.diff.DiffAnalyzer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class BranchDiffTask extends DefaultTask {
    def currentName//当前分支名
    JacocoExtension jacocoExtension

    @TaskAction
    def getDiffClass() {
        println "downloadEcData start"
        downloadEcData()
        println "downloadEcData end"

        //生成差异报告
        println "pullDiffClasses start"
        pullDiffClasses()
        println "pullDiffClasses end"

        if (jacocoExtension.reportDirectory == null) {
            jacocoExtension.reportDirectory = "${project.buildDir.getAbsolutePath()}/outputs/report"
        }
        ReportGenerator generator = new ReportGenerator(jacocoExtension.execDir, toFileList(jacocoExtension.classDirectories),
                toFileList(jacocoExtension.sourceDirectories), new File(jacocoExtension.reportDirectory));
        generator.create();
    }

    def toFileList(List<String> path) {
        List<File> list = new ArrayList<>(path.size())
        for (String s : path)
            list.add(new File(s))
        return list
    }

    def pullDiffClasses() {
        currentName = "git name-rev --name-only HEAD".execute().text.replaceAll("\n", "")
        if(currentName.contains("/")){
            currentName=currentName.substring(currentName.lastIndexOf("/")+1)
        }

        println "currentName:\n" + currentName
        //获得两个分支的差异文件
        def diff = "git diff origin/${jacocoExtension.branchName} origin/${currentName} --name-only".execute().text
        List<String> diffFiles = getDiffFiles(diff)

        println("diffFiles size=" + diffFiles.size())
        writerDiffToFile(diffFiles)

        //两个分支差异文件的目录
        def currentDir = "${project.rootDir.parentFile}/temp/${currentName}/app"
        def branchDir = "${project.rootDir.parentFile}/temp/${jacocoExtension.branchName}/app"

        project.delete(currentDir)
        project.delete(branchDir)
        new File(currentDir).mkdirs()
        new File(branchDir).mkdirs()

        //先把两个分支的所有class copy到temp目录
        copyBranchClass(jacocoExtension.branchName, branchDir)
        copyBranchClass(currentName, currentDir)
        //再根据diffFiles 删除不需要的class
        deleteOtherFile(diffFiles, branchDir)
        deleteOtherFile(diffFiles, currentDir)

        //删除空文件夹
        deleteEmptyDir(new File(branchDir))
        deleteEmptyDir(new File(currentDir))

        createDiffMethod(currentDir, branchDir)

        writerDiffMethodToFile()
    }

    def writerDiffToFile(List<String> diffFiles) {
        String path = "${project.buildDir.getAbsolutePath()}/outputs/diff/diffFiles.txt"
        File parent = new File(path).getParentFile();
        if (!parent.exists()) parent.mkdirs()

        println("writerDiffToFile size=" + diffFiles.size() + " to >" + path)

        FileOutputStream fos = new FileOutputStream(path)
        for (String str : diffFiles) {
            fos.write((str + "\n").getBytes())
        }
        fos.close()
    }

    def writerDiffMethodToFile() {
        String path = "${project.buildDir.getAbsolutePath()}/outputs/diff/diffMethod.txt"

        println("writerDiffMethodToFile size=" + DiffAnalyzer.getInstance().getDiffList().size() + " >" + path)

        // 替换FileUtils.writeToFile
        File file = new File(path)
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs()
        }
        Files.write(Paths.get(path), DiffAnalyzer.getInstance().toString().getBytes(StandardCharsets.UTF_8))
    }

    def deleteOtherFile(List<String> diffFiles, String dir) {

        readFiles(dir, {
            String path = ((File) it).getAbsolutePath().replace(dir, "app")
            //path= app/classes/com/example/jacoco_plugin/MyApp.class
            return diffFiles.contains(path)
        })
    }

    void readFiles(String dirPath, Closure closure) {
        File file = new File(dirPath);
        if (!file.exists()) {
            return
        }
        File[] files = file.listFiles();
        for (File classFile : files) {
            if (classFile.isDirectory()) {
                readFiles(classFile.getAbsolutePath(), closure);
            } else {
                if (classFile.getName().endsWith(".class")) {
                    if (!closure.call(classFile)) {
                        classFile.delete()
                    }
                } else {
                    classFile.delete()
                }
            }
        }
    }

    private void copyBranchClass(String currentName, GString currentDir) {
        String[] cmds
        if (Utils.windows) {
            cmds = new String[5]
            cmds[0] = jacocoExtension.getGitBashPath()
            cmds[1] = jacocoExtension.copyClassShell
            cmds[2] = currentName
            cmds[3] = project.rootDir.getAbsolutePath()
            cmds[4] = currentDir.toString()
        } else {
            cmds = new String[4]
            cmds[0] = jacocoExtension.copyClassShell
            cmds[1] = currentName
            cmds[2] = project.rootDir.getAbsolutePath()
            cmds[3] = currentDir.toString()
        }

        println("cmds=" + cmds)
        Process pces = Runtime.getRuntime().exec(cmds)
        String result = IOGroovyMethods.getText(new BufferedReader(new InputStreamReader(pces.getIn())))
        String error = IOGroovyMethods.getText(new BufferedReader(new InputStreamReader(pces.getErr())))

        println("copyClassShell succ :" + result)
        println("copyClassShell error :" + error)

        pces.closeStreams()
    }

    def createDiffMethod(def currentDir, def branchDir) {
        //生成差异方法
/*
        def path="${project.buildDir.getAbsolutePath()}/intermediates/runtime_symbol_list/${getBuildType()}/R.txt"
        def file=new File(path)
        List<String> ids=readIdList(file)

        println("createDiffMethod r=${path} exist=${file.exists()} len=${ids.size()}")
*/
        DiffAnalyzer.getInstance().reset()
//        DiffAnalyzer.getInstance().setResIdLines(ids)
        DiffAnalyzer.readClasses(currentDir, DiffAnalyzer.CURRENT)
        DiffAnalyzer.readClasses(branchDir, DiffAnalyzer.BRANCH)
        DiffAnalyzer.getInstance().diff()

        println("excludeMethod before diff.size=${DiffAnalyzer.getInstance().getDiffList().size()}")

        //excludeMethod
        if (jacocoExtension.excludeMethod != null) {
            Iterator<MethodInfo> iterator = DiffAnalyzer.getInstance().getDiffList().iterator()
            while (iterator.hasNext()) {
                MethodInfo info = iterator.next();
                if (jacocoExtension.excludeMethod.call(info))
                    iterator.remove()
            }
        }
        println("excludeMethod after diff.size=${DiffAnalyzer.getInstance().getDiffList().size()}")

    }

    void deleteEmptyDir(File dir) {
        File[] dirs = dir.listFiles { it.isDirectory() }
        for (File d in dirs) {
            deleteEmptyDir(d)
        }
        if (dir.isDirectory() && dir.list().size() == 0) {
            dir.delete()
        }
    }

    List<String> readIdList(File file) {
        List<String> list = new ArrayList<>();
        try {
            BufferedReader fis = new BufferedReader(new FileReader(file));
            String line;
            while ((line = fis.readLine()) != null) {
                if (line.contains("0x7f"))
                    list.add(line)
            }
            fis.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    List<String> getDiffFiles(String diff) {
        List<String> diffFiles = new ArrayList<>()
        if (diff == null || diff == '') {
            return diffFiles
        }
        String[] strings = diff.split("\n")
        def classes = "/classes/"
        strings.each {
            if (it.endsWith('.class')) {
                String classPath = it.substring(it.indexOf(classes) + classes.length())
                if (isInclude(classPath)) {
                    if (jacocoExtension.excludeClass != null) {
                        boolean exclude = jacocoExtension.excludeClass.call(it)
                        if (!exclude) {
                            diffFiles.add(it)
                        }
                    } else {
                        diffFiles.add(it)
                    }
                }
            }
        }
        return diffFiles
    }

    def isInclude(String classPath) {
        List<String> includes = jacocoExtension.includes
        for (String str : includes) {
            if (classPath.startsWith(str.replaceAll("\\.", "/"))) {
                return true
            }
        }
        return false
    }


    //下载ec数据文件
    def downloadEcData() {
        if (jacocoExtension.execDir == null) {
            jacocoExtension.execDir = "${project.buildDir}/jacoco/code-coverage/"
        }
        new File(jacocoExtension.execDir).mkdirs()

        boolean isSuccess = getUrlFile()
        if (!isSuccess) {
            println "downloadEcData getUrlFile false"
        }
    }

    boolean getUrlFile() {
        if (jacocoExtension.host == null || jacocoExtension.host.trim() == '') {
            return false
        }

        String urlpath = jacocoExtension.host + "/jacocoDiff/getecfile"
        try {
            println "getUrlFile urlpath=${urlpath}"
            URL url = new URL(urlpath)
            URLConnection uc = url.openConnection()
            uc.setDoOutput(true)
            uc.connect()
            InputStream inputstream = uc.getInputStream()
            byte[] buffer = new byte[4096]
            int byteRead = -1

            String path = jacocoExtension.execDir + "jacoco.exec"
            new File(path).getParentFile().mkdirs()
            FileOutputStream outputstream = new FileOutputStream(path)
            while ((byteRead = inputstream.read(buffer)) != -1) {
                outputstream.write(buffer, 0, byteRead)
            }

            outputstream.close()
            inputstream.close()

            println("getUrlFile success. path=${path}")

            return true
        } catch (Exception e) {
            e.printStackTrace()
        }
        return false
    }

    String getBuildType() {
        return project.android.applicationVariants[0].name
    }
}