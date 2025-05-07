package com.ttp.and_jacoco.extension

import java.io.File

open class JacocoExtension {
    //jacoco开关，false时不会进行probe插桩
    var jacocoEnable: Boolean = false
    //需要对比的分支名
    var branchName: String? = null
    //exec文件路径，支持多个ec文件，自动合并
    var execDir: String? = null
    //源码目录，支持多个源码
    var sourceDirectories: List<String>? = null
    //class目录，支持多个class目录
    var classDirectories: List<String>? = null
    //需要插桩的文件
    var includes: List<String>? = null
    //生成报告的目录
    var reportDirectory: String? = null
    //git 提交命令
    var gitPushShell: String? = null
    //复制class 的shell
    var copyClassShell: String? = null
    //git-bash的路径，插件会自动寻找路径，如果找不到，建议自行配置
    private var _gitBashPath: String? = null // Renamed to avoid conflict with getter
    //下载ec 的服务器
    var host: String? = null

    /**
     * 类过滤器 返回 true 的将会被过滤
     * exclude{*      it="/com/ttp/xxx.class"
     *     return it.endsWith(".a")
     *}*/
    var excludeClass: ((String) -> Boolean)? = null
    /**
     *
     * 方法过滤器 返回true 的将会被过滤
     * exclude{*     it = MethodInfo
     *}*/
    var excludeMethod: ((Any) -> Boolean)? = null // TODO: Define MethodInfo class or use a more specific type

    var gitBashPath: String?
        get() {
            if (_gitBashPath == null || _gitBashPath!!.isEmpty()) {
                try {
                    val process = Runtime.getRuntime().exec("where git")
                    val path = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    val paths = path.split('\n')
                    var temp = ""
                    for (p in paths) {
                        val file = File(p.trim())
                        if (file.exists()) {
                            val gitBash = File(file.parentFile.parentFile, "git-bash.exe")
                            println("GitBashPath:$gitBash exist:${gitBash.exists()}")
                            if (gitBash.exists()) {
                                temp = gitBash.absolutePath
                                _gitBashPath = temp
                                return temp
                            }
                        }
                    }
                    return temp
                } catch (e: Exception) {
                    e.printStackTrace()
                    return ""
                }
            }
            return _gitBashPath
        }
        set(value) {
            _gitBashPath = value
        }
} 