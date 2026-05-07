#!/bin/bash
# scripts/setup-callgraph.sh
# 사용법: ./scripts/setup-callgraph.sh <clone-path> [db-output-path]
set -e

CLONE_PATH="${1:?clone path required}"
DB_PATH="${2:-$(pwd)/call_graph.db}"
PLUGIN_JAR="$HOME/.m2/repository/io/github/veronikapj/callgraph-plugin/1.0.0/callgraph-plugin-1.0.0.jar"
SQLITE_JAR="$HOME/.m2/repository/org/xerial/sqlite-jdbc/3.47.1.0/sqlite-jdbc-3.47.1.0.jar"

if [ ! -f "$PLUGIN_JAR" ]; then
  echo "ERROR: Plugin JAR not found. Run: ./gradlew :callgraph-plugin:publishToMavenLocal"
  exit 1
fi

if [ ! -f "$SQLITE_JAR" ]; then
  echo "ERROR: sqlite-jdbc JAR not found at $SQLITE_JAR"
  exit 1
fi

cat > "$CLONE_PATH/init.gradle.kts" << EOF
allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll(
            "-Xplugin=$PLUGIN_JAR",
            "-Xplugin=$SQLITE_JAR",
            "-P", "plugin:io.github.veronikapj.callgraph:outputPath=$DB_PATH"
        )
    }
}
EOF

echo "init.gradle.kts written to $CLONE_PATH/init.gradle.kts"
echo "DB output: $DB_PATH"
