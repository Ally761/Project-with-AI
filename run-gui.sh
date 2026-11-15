#!/usr/bin/env sh
# Compiles (if needed) and starts the window version. No Maven required, only JDK 17+.
set -e
cd "$(dirname "$0")"
mkdir -p out/main
find src/main -name '*.java' > out/main-sources.txt
javac --release 17 -encoding UTF-8 -d out/main @out/main-sources.txt
exec java -cp out/main gameagent.gui.GuiMain
