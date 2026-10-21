@echo off
rem ============================================================
rem  LogiScope - offline checks (1.21.1 main branch)
rem
rem  Why a .bat instead of the one-liners in docs/VERIFY.md:
rem  passing a multi-entry -cp (semicolon list) from Git Bash to
rem  cmd gets mangled, javac then reports "package does not exist"
rem  even though the classes are there. This file keeps the
rem  classpath inside a native cmd context.
rem
rem  Run:  cmd //c dev-check\offline-check.bat
rem  Requires: build\classes\java\main already produced
rem            (gradlew.bat build --offline)
rem  ASCII only on purpose: Chinese text in .bat files can break
rem  the console codepage.
rem ============================================================
setlocal
cd /d "%~dp0.."
set CP=build\classes\java\main;dev-check\lib\gson-2.10.1.jar;dev-check\lib\guava-32.1.2-jre.jar;build\moddev\artifacts\neoforge-21.1.209-merged.jar

echo === [1/4] javac TestChart ===
javac -encoding UTF-8 -cp "%CP%" -d build\dev-check dev-check\TestChart.java
if errorlevel 1 goto fail

echo === [2/4] TestChart (expect 478 assertions, 0 failures) ===
java -Dfile.encoding=UTF-8 -cp "build\dev-check;%CP%" TestChart
if errorlevel 1 goto fail

echo === [3/4] javac + BenchRender ===
javac -encoding UTF-8 -cp build\classes\java\main -d build\dev-check dev-check\BenchRender.java
if errorlevel 1 goto fail
java -Dfile.encoding=UTF-8 -cp "build\dev-check;build\classes\java\main" BenchRender
if errorlevel 1 goto fail

echo === [4/4] verify-jar ===
python verify-jar.py build\libs\logiscope-0.1.2-1.21.1.jar
if errorlevel 1 goto fail

echo ALL_OK
exit /b 0

:fail
echo CHECK_FAILED
exit /b 1
