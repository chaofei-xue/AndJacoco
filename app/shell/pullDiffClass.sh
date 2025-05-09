#!/bin/sh
oriBran=$(git name-rev --name-only HEAD)
echo "当前分支：$oriBran" #remotes/origin/main_DEALER-2932

gitBran=$1 # 本地分支
echo "gitBran=$gitBran"
workDir=$2
outDir=$3
echo "workDir=$workDir"
echo "outDir=$outDir"

# 保存当前的修改
git stash -q || echo "无法保存当前修改，继续执行..."

# 检查分支是否存在
branch_exists=$(git branch --list $gitBran)
echo "start checkout--"

if [ -z "$branch_exists" ]; then
    # 分支不存在，创建并切换
    git checkout -b $gitBran origin/$gitBran || {
        echo "无法创建分支 $gitBran，请检查远程分支是否存在"
        git stash pop -q 2>/dev/null # 尝试恢复修改
        exit 1
    }
else
    # 分支已存在，直接切换
    git checkout $gitBran || {
        echo "无法切换到分支 $gitBran"
        git stash pop -q 2>/dev/null # 尝试恢复修改
        exit 1
    }
fi

echo "start pull--"
git pull --ff-only || echo "警告：无法拉取最新代码，将使用当前代码继续"

echo "start copy: cp -r "${workDir}/app/classes" $outDir "
mkdir -p $outDir
cp -r "${workDir}/app/classes" $outDir
echo "copy over --"

# 切换回原分支
git checkout $oriBran 2>/dev/null || echo "警告：无法切换回原分支 $oriBran"

# 恢复修改
git stash pop -q 2>/dev/null || echo "无法恢复之前的修改，或没有修改需要恢复"