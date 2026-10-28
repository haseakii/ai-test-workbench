@echo off
setlocal DisableDelayedExpansion
set "JAVA_TOOL_OPTIONS="
set "_JAVA_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "CLASSPATH="
for /f "tokens=1 delims==" %%K in ('set LOADER_ 2^>nul') do set "%%K="
chcp 65001 >nul
set "WB_REQUIREMENTS_EXIT=0"
if /I "%~1"=="requirements" goto requirements
if not exist "%~dp0app\platform-server.jar" (
  echo 找不到完整发行包。请先完整解压，再双击 Workbench.cmd。
  if "%~1"=="" pause
  exit /b 2
)
set "WB_LAUNCH_JAVA="
set "WB_LAUNCH_SOURCE="
if exist "%~dp0runtime\java\bin\java.exe" goto portable_unverified
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  set "WB_LAUNCH_JAVA=%JAVA_HOME%\bin\java.exe"
  set "WB_LAUNCH_SOURCE=已有 JAVA_HOME 兼容入口"
  goto java_selected
)
for %%J in (java.exe) do set "WB_LAUNCH_JAVA=%%~$PATH:J"
if not defined WB_LAUNCH_JAVA goto java_missing
set "WB_LAUNCH_SOURCE=已有 PATH 兼容入口"
:java_selected
echo Java 来源：%WB_LAUNCH_SOURCE%。这不代表便携首装通过，运行预检需要 Java 21。
"%WB_LAUNCH_JAVA%" "-Dworkbench.bundle=%~dp0." -Dloader.main=io.aitest.workbench.bootstrap.RuntimeLauncher -Dloader.config.location=classpath:/workbench-disabled-loader.properties "-Dloader.home=%~dp0app" -cp "%~dp0app\platform-server.jar" org.springframework.boot.loader.launch.PropertiesLauncher %*
set "WB_LAUNCH_EXIT=%ERRORLEVEL%"
if not "%WB_LAUNCH_EXIT%"=="0" (
  echo 操作未完成。请核对错误代码；可运行 Workbench.cmd requirements 查看不依赖 Java 的准备说明。
  echo 首装只读方案请用 Workbench.cmd setup。不要关闭 TLS 校验、用管理员身份或删除数据绕过失败。
  if "%~1"=="" pause
)
exit /b %WB_LAUNCH_EXIT%
:portable_unverified
echo [DEPENDENCY_UNVERIFIED] 发现便携 Java 候选，但尚无受信官方摘要、许可证核验和本次使用授权，本轮不运行。
echo 不读取普通批准标志，也不会回退并把已有 Java 的成功当作便携首装。
set "WB_REQUIREMENTS_EXIT=2"
goto requirements
:java_missing
echo [JAVA_MISSING] 未找到已有 Java。当前预览包不含 Java，不会自动下载、安装或修改系统配置。
echo 后续便携包须先完成官方来源、版本、校验和与授权；请勿自行猜测开发环境变量。
set "WB_REQUIREMENTS_EXIT=2"
goto requirements
:requirements
echo Windows 首装准备：推荐应用目录内便携 Java 21 和私有 MySQL 8.4，使用本地 NTFS 私有数据目录。
echo 现有预览包未捆绑这两种软件，下载、运行或初始化仍须先明确批准；依赖检测和方案输出不等于已经安装。
echo 便携 Java 候选位置：runtime\java\bin\java.exe；当前仅检测并拒绝未核验候选，不会修改全局 PATH。
echo 未提供便携候选时可兼容已有 JAVA_HOME 或 PATH，但不能把其成功算作便携首装。
echo MySQL 8.4 可能还需要 MSVC 14 运行库，须在目标机器检测；缺少时停止，不自动安装。
echo 秘密保护准备使用 Windows 当前用户 DPAPI 与系统 PowerShell，不需手写脚本；组件或策略不可用时停止，不绕过。
echo 不安装 Windows 服务、不修改全局网络或权限、不提权、不关闭 TLS，不要求 Docker。
echo Java 官方来源：https://adoptium.net/temurin/releases/
echo MySQL 官方来源：https://dev.mysql.com/downloads/mysql/8.4.html
echo MSVC 官方说明：https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist
echo 以上仅是来源说明，不会联网。只读方案请在已有受信 Java 可用后运行 Workbench.cmd setup。
echo 尚需获准 Windows 验收机器验证，Linux 离线检查不代表 Windows 首装或停启通过。
if "%~1"=="" pause
exit /b %WB_REQUIREMENTS_EXIT%
