@echo off
setlocal

rem ============================================================
rem  Online Shopping System - Group 5 - Iteration 2
rem  One-click start script
rem
rem  Requires: JDK 17 (JAVA_HOME must point to it)
rem  Maven:    uses mvnw.cmd if present, otherwise system mvn
rem ============================================================

cd /d "%~dp0"

if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME is not set.
    echo         Please install JDK 17 and set JAVA_HOME, for example:
    echo         setx JAVA_HOME "D:\jdk17\jdk-17.0.20.1+1"
    pause
    exit /b 1
)

echo [INFO] JAVA_HOME = %JAVA_HOME%
echo [INFO] Starting Spring Boot application on http://localhost:8080
echo [INFO] Default account: admin / admin12345
echo.

if exist "%~dp0mvnw.cmd" (
    call "%~dp0mvnw.cmd" spring-boot:run
) else (
    where mvn >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Maven not found in PATH and mvnw.cmd is missing.
        echo         Install Maven 3.9+ or run: mvn -N wrapper:wrapper
        pause
        exit /b 1
    )
    call mvn spring-boot:run
)

pause
