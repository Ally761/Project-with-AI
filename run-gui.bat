@echo off
rem Compiles (if needed) and starts the window version. No Maven required, only JDK 17+.
cd /d "%~dp0"
if not exist out\main mkdir out\main
dir /s /b src\main\*.java > out\main-sources.txt
javac --release 17 -encoding UTF-8 -d out\main @out\main-sources.txt || exit /b 1
chcp 65001 > nul
java -Dfile.encoding=UTF-8 -cp out\main gameagent.gui.GuiMain
