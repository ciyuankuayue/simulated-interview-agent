#!/bin/sh
# Maven Wrapper: download Maven distribution directly (no maven-wrapper.jar).
set -e
BASEDIR=$(cd "$(dirname "$0")" && pwd)
DIST_DIR="$BASEDIR/.mvn/wrapper/dists"
MVN_HOME="$DIST_DIR/apache-maven-3.9.9"
MVN_EXE="$MVN_HOME/bin/mvn"
ZIP_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
ZIP_FILE="$DIST_DIR/apache-maven-3.9.9-bin.zip"

if [ ! -x "$MVN_EXE" ]; then
  mkdir -p "$DIST_DIR"
  if [ ! -f "$ZIP_FILE" ]; then
    echo "[mvnw] Downloading Apache Maven 3.9.9 ..."
    curl -sL -o "$ZIP_FILE" "$ZIP_URL"
  fi
  echo "[mvnw] Extracting Maven ..."
  ( cd "$DIST_DIR" && unzip -q -o "$ZIP_FILE" )
fi

exec "$MVN_EXE" "$@"
