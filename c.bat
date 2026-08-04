@echo off
call sp
call gradlew assembleDebug
if %ERRORLEVEL% equ 0 (
    call cop.bat
    start cmd /k adb1.bat
) else (
    echo [91mBuild FAILED! Copy skipped.[0m
)
