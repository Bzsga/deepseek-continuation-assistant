# 构建与安装

源码版本见 `android/app/build.gradle.kts`。当前 Android 为 1.0.3 / versionCode 12，最低 Android 8.0（API 26），compile/target SDK 为 35。

## Android 环境

安装 JDK 17、**Gradle 8.9**、Android SDK Platform 35、Build Tools 35.0.0 和 Platform Tools。仓库不包含 Gradle wrapper，以下命令使用自己安装的 `gradle`。SDK 许可证需由开发者正常接受；首次构建需要下载 Google/Maven Central 依赖。

将 `JAVA_HOME`、`ANDROID_HOME` 指向本机实际工具目录；SDK 也可通过未提交的 `android/local.properties` 的 `sdk.dir` 指定。Windows 上若工具链不能正确处理含非 ASCII 字符的路径，可把检出和工具链放在简短的 ASCII 路径。

在仓库根目录运行：

```text
gradle -p android :app:assembleDebug
gradle -p android :app:testDebugUnitTest :app:lintRelease
```

Debug APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。通过设备文件管理器安装，或连接已开启调试的设备后运行：

```text
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

已有不同签名的同包名应用不能被此包覆盖。保留原版数据时，应先备份，或为开发版本设置不同 applicationId 后并行安装。

PowerShell 也可使用仓库脚本；尖括号为需要替换的工具路径：

```powershell
.\tools\build.ps1 -Jdk '<JDK 17目录>' -Sdk '<Android SDK目录>' -Gradle '<Gradle命令路径>' -Test
```

## 自己签名的 Release

原版签名私钥不随源码提供。生成自己的 Android keystore，并在仓库外保存签名属性文件：

```properties
storeFile=<自己的keystore绝对路径>
storePassword=<自己的密码>
keyAlias=<自己的别名>
keyPassword=<自己的密码>
```

不要提交此文件或 keystore。在仓库根目录运行：

```text
gradle -p android "-PpreviewSigningFile=<签名属性文件路径>" :app:assembleRelease :app:lintRelease
```

属性名 `previewSigningFile` 是现有构建接口，当前版本号仍由 Gradle 配置决定。未提供签名配置的 release 不应作为可安装发行包分发。自己签名的包不能成为原版应用的同签名更新。

Fork 发布前还要修改客户端中的配套服务默认地址、更新清单/下载来源约束、包名及发布工具的证书与包名配置。位置可在 `android/app/src/main/java/` 与 `tools/` 中搜索原版域名、包名和证书常量，流程见 [更新通道](app-updates.md)。

## 测试

设备测试需要 Android 模拟器或真机；使用独立测试数据与安装环境：

```text
gradle -p android :app:connectedDebugAndroidTest
```

后端使用 Python 3.10+。在 `backend` 目录创建虚拟环境，安装开发依赖后运行：

```text
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.venv\Scripts\python.exe -m pytest -q
```

上面使用 Windows 虚拟环境路径，Linux/macOS 将 `.venv\Scripts\python.exe` 换成 `.venv/bin/python`。本地后端测试使用模拟服务和临时数据库，不需要生产密钥。

仓库根目录的更新发布器测试可单独运行，不发布安装包：

```text
python -m unittest discover -s tools/tests -p test_publish_release.py
```

既有构建与测试结论见 [README](../README.md#验证状态与局限)。当前版本的构建、验签及并发保存/恢复验证见对应 Release；历史测试不代表本轮重新执行所有平台与场景。
