@echo off
rem Build, install and launch Blink Temp on a USB-connected phone (Windows).
setlocal
cd /d "%~dp0"

if "%JAVA_HOME%"=="" if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if "%JAVA_HOME%"=="" (
  where java >nul 2>nul || (echo No JDK found. Install Android Studio or set JAVA_HOME. & exit /b 1)
)

set "SDK=%ANDROID_HOME%"
if "%SDK%"=="" set "SDK=%ANDROID_SDK_ROOT%"
if "%SDK%"=="" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not exist "%SDK%\platform-tools\adb.exe" (
  echo Android SDK not found. Open Android Studio once ^(it installs the SDK^) or set ANDROID_HOME.
  exit /b 1
)
set "ADB=%SDK%\platform-tools\adb.exe"
set "SDKFWD=%SDK:\=/%"
> local.properties echo sdk.dir=%SDKFWD%

"%ADB%" start-server >nul
"%ADB%" devices | findstr /R /C:"	device$" >nul || (
  echo No phone found. Enable USB debugging, plug in, and accept the "Allow USB debugging?" prompt.
  "%ADB%" devices
  exit /b 1
)

call gradlew.bat installDebug || exit /b 1
"%ADB%" shell am start -n com.example.blinktemp/.MainActivity
echo Done. Sign in in the app, then add the "Blink Temp" widget to your home screen.
