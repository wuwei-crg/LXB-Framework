@echo off
chcp 65001 >nul

echo ========================================
echo LXB Server - One-Click Start
echo ========================================
echo.

REM ===== Step 1: Build =====
echo [1/4] Compiling Java code...
call gradlew :lxb-core:clean :lxb-core:classes >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed
    pause
    exit /b 1
)

REM ===== Step 2: Package JAR =====
echo [2/4] Creating JAR...
set BUILD_DIR=lxb-core\build\classes\java\main
powershell -Command "$buildDir=Resolve-Path '%BUILD_DIR%'; $zip=[System.IO.Compression.ZipFile]::Open('lxb-core.jar','Create'); $len=$buildDir.Path.Length+1; Get-ChildItem $buildDir -Recurse -File | ForEach-Object { $rel=$_.FullName.Substring($len).Replace('\','/'); [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$_.FullName,$rel)|Out-Null }; $m=$zip.CreateEntry('META-INF/MANIFEST.MF'); $s=$m.Open(); $w=New-Object IO.StreamWriter($s); $w.Write(\"Manifest-Version: 1.0`r`nMain-Class: com.lxb.server.Main`r`n\"); $w.Close(); $s.Close(); $zip.Dispose()" >nul 2>&1

REM ===== Step 3: Convert to DEX =====
echo [3/4] Converting to DEX format...
if exist "lxb-server.zip" del lxb-server.zip
for /d %%D in ("%LOCALAPPDATA%\Android\Sdk\build-tools\*") do if exist "%%D\d8.bat" set D8=%%D\d8.bat
for /f "delims=" %%D in ('dir /b /o-n "%LOCALAPPDATA%\Android\Sdk\platforms\android-*" 2^>nul') do if exist "%LOCALAPPDATA%\Android\Sdk\platforms\%%D\android.jar" set ANDROID_JAR=%LOCALAPPDATA%\Android\Sdk\platforms\%%D\android.jar& goto :found

:found
call "%D8%" --lib "%ANDROID_JAR%" --release --min-api 21 --output lxb-server.zip lxb-core.jar
if not exist "lxb-server.zip" (
    echo [ERROR] DEX conversion failed
    pause
    exit /b 1
)

REM ===== Step 4: Deploy and Run =====
echo [4/4] Deploying to device and starting server...
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
"%ADB%" push lxb-server.zip /data/local/tmp/lxb-server.zip >nul 2>&1

echo [4/4] Setting up port forwarding...
"%ADB%" forward tcp:12345 tcp:12345

echo.
echo ========================================
echo [SUCCESS] LXB Server is starting...
echo ========================================
echo Web Console: Connect to 127.0.0.1:12345
echo Press Ctrl+C to stop
echo.

"%ADB%" shell "CLASSPATH=/data/local/tmp/lxb-server.zip app_process /system/bin com.lxb.server.Main"
