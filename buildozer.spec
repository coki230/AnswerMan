[app]

# 1. 应用的名称 (Title of your application)
title = ScreenParser

# 2. 包名 (Package name)
package.name = screenparserapp

# 3. 源代码所在目录 (Source code directory)
# "." 代表当前目录，即 main.py 所在的文件夹
source.dir = .

# 4. 版本号 (Version of your application)
version = 0.1

# 1. 必须包含高级悬浮窗权限
android.permissions = SYSTEM_ALERT_WINDOW, WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE

# 2. 确保包含 android 核心库（用于调用 runnable 等工具）
requirements = python3, kivy, android

android.archs = arm64-v8a