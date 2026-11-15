@echo off
rem Compiles main + test sources and runs the offline test suite (no API key or network needed).
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out\main
mkdir out\test
dir /s /b src\main\*.java > out\main-sources.txt
dir /s /b src\test\*.java > out\test-sources.txt
javac --release 17 -encoding UTF-8 -d out\main @out\main-sources.txt || exit /b 1
javac --release 17 -encoding UTF-8 -cp out\main -d out\test @out\test-sources.txt || exit /b 1
java -cp out\main;out\test gameagent.AllTests
