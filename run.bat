@echo off
rem Build and start the app (Windows). Requires JDK 17+.
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\leakplanner\*.java
if errorlevel 1 exit /b 1
java -cp out leakplanner.Main
