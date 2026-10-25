@echo off
rem ============================================================
rem  LogiScope - offline checks (1.16.5 branch)
rem
rem  Why a .bat instead of the one-liners in docs/VERIFY.md:
rem  passing a multi-entry -cp (semicolon list) from Git Bash to
rem  cmd gets mangled, javac then reports "package does not exist"
rem  even though the classes are there. This file keeps the
rem  classpath inside a native cmd context.
rem
rem  Run:  cmd //c dev-check\offline-check.bat
rem  Requires: build\classes\java\main already produced
rem            (gradlew.bat build --offline, which also runs
rem             copyMinecraftDeps -> build\mcdeps\*.jar)
rem  ASCII only on purpose: Chinese text in .bat files can break
rem  the console codepage.
rem ============================================================
setlocal
cd /d "%~dp0.."
set CP=build\classes\java\main;dev-check\lib\*;build\mcdeps\*

echo === [1/5] javac TestChart ===
javac -encoding UTF-8 -cp "%CP%" -d build\dev-check dev-check\TestChart.java
if errorlevel 1 goto fail

echo === [2/5] TestChart (expect 518 assertions, 0 failures) ===
java -Dfile.encoding=UTF-8 -cp "build\dev-check;%CP%" TestChart
if errorlevel 1 goto fail

echo === [3/5] javac + BenchRender ===
javac -encoding UTF-8 -cp build\classes\java\main -d build\dev-check dev-check\BenchRender.java
if errorlevel 1 goto fail
java -Dfile.encoding=UTF-8 -cp "build\dev-check;build\classes\java\main" BenchRender
if errorlevel 1 goto fail

echo === [4/5] verify-jar ===
python verify-jar.py build\libs\logiscope-1.0.0-1.16.5.jar
if errorlevel 1 goto fail

rem Catches "compiles fine, dies in-game" deps (e.g. org/slf4j on 1.16.5:
rem present in the dev classpath, absent from the production one).
echo === [5/5] runtime reference check ===
python dev-check\check-runtime-refs.py build\libs\logiscope-1.0.0-1.16.5.jar build\mcdeps
if errorlevel 1 goto fail

echo ALL_OK
exit /b 0

:fail
echo CHECK_FAILED
exit /b 1
