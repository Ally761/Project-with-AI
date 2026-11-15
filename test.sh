#!/usr/bin/env sh
# Compiles main + test sources and runs the offline test suite (no API key or network needed).
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir -p out/main out/test
find src/main -name '*.java' > out/main-sources.txt
find src/test -name '*.java' > out/test-sources.txt
javac --release 17 -encoding UTF-8 -d out/main @out/main-sources.txt
javac --release 17 -encoding UTF-8 -cp out/main -d out/test @out/test-sources.txt
java -cp out/main:out/test gameagent.AllTests
