@echo off
REM 构建 APK。
REM   tools\build.bat          直接用已提交的前端产物出包
REM   tools\build.bat --web    先重建前端（需要 node），再出包
REM 产物：app\android\app\build\outputs\apk\debug\app-debug.apk
setlocal
set ROOT=%~dp0..

if "%1"=="--web" (
  echo ==^> 重建前端
  pushd "%ROOT%\app"
  if not exist node_modules call npm install
  call npm run build
  popd

  echo ==^> 同步到 assets
  set DEST=%ROOT%\app\android\app\src\main\assets\web
  if exist "%DEST%" rmdir /s /q "%DEST%"
  mkdir "%DEST%"
  xcopy /e /i /q /y "%ROOT%\app\dist\*" "%DEST%\" >nul
  del /q "%DEST%\assets\remixicon-*.svg" 2>nul
  del /q "%DEST%\assets\remixicon-*.eot" 2>nul
  del /q "%DEST%\assets\remixicon-*.ttf" 2>nul
  del /q "%DEST%\assets\remixicon-*.woff" 2>nul
)

echo ==^> 构建 APK
cd /d "%ROOT%\app\android"
call gradlew.bat assembleDebug
if errorlevel 1 exit /b 1

echo.
echo 完成: app\android\app\build\outputs\apk\debug\app-debug.apk
endlocal
