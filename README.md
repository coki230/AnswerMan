# AnswerMan
see a picture of questions, give answers

# Java环境不能用17，要用11
### 1. 用 Homebrew 安装符合安卓老工具胃口的 Java 11
brew install openjdk@11

### 2. 将当前终端会话的 JAVA_HOME 临时指向 Java 11
```
export JAVA_HOME="/opt/homebrew/opt/openjdk@11"
export PATH="$JAVA_HOME/bin:$PATH"
```

### 3. 再次尝试打包
buildozer -v android debug deploy run