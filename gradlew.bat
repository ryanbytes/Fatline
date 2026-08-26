@echo off
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo Gradle 9.4.1 is required. Open this project in Android Studio or install Gradle 9.4.1 and rerun.
exit /b 1
