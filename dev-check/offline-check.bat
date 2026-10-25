@echo off
rem ============================================================
rem  LogiScope - offline checks (1.12.2 branch)
rem
rem  Difference from the other three lines: the pure-logic layer
rem  (analysis / store / archive data) has ZERO Minecraft imports,
rem  so TestChart and BenchRender compile and run against
rem  build\classes\java\main alone - no mapped MC jar needed.
rem  That keeps this gate fast and immune to mapping changes.
rem
rem  There is no copyMinecraftDeps task on this line (ForgeGradle 3
rem  has no such hook), and the MC-facing modules are not ported yet.
rem
rem  Run:  cmd //c dev-check\offline-check.bat
rem  Requires: build\classes\java\main (gradlew.bat build)
rem  ASCII only on purpose (console codepage).
rem ============================================================
setlocal
cd /d "%~dp0.."
rem build\gatedeps holds gson.jar (ArchiveFormat parses JSON at runtime);
rem it is produced by the copyGateDeps task, see build.gradle.
set CP=build\classes\java\main;build\gatedeps\*

echo === [1/4] javac TestChart ===
javac -encoding UTF-8 -cp "%CP%" -d build\dev-check dev-check\TestChart.java
if errorlevel 1 goto fail

echo === [2/4] TestChart (expect 503 assertions, 0 failures) ===
java -Dfile.encoding=UTF-8 -cp "build\dev-check;%CP%" TestChart
if errorlevel 1 goto fail

echo === [3/4] javac + BenchRender ===
javac -encoding UTF-8 -cp "%CP%" -d build\dev-check dev-check\BenchRender.java
if errorlevel 1 goto fail
java -Dfile.encoding=UTF-8 -cp "build\dev-check;%CP%" BenchRender
if errorlevel 1 goto fail

rem The @Mod annotation cannot expand placeholders, so the version is
rem hard-coded in LogiScopeMod.VERSION and must match gradle.properties.
echo === [4/4] version consistency (gradle.properties vs LogiScopeMod.VERSION) ===
python dev-check\check-version.py
if errorlevel 1 goto fail

echo ALL_OK
exit /b 0

:fail
echo CHECK_FAILED
exit /b 1
