#!/usr/bin/env bash
# Build and start the app (Mac/Linux). Requires JDK 17+.
cd "$(dirname "$0")"
mkdir -p out && javac -d out src/leakplanner/*.java && java -cp out leakplanner.Main
