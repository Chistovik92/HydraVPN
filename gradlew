#!/bin/sh
# Gradle wrapper launcher (standard). Требует gradle/wrapper/gradle-wrapper.jar
DIR=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$DIR/gradle/wrapper/gradle-wrapper.jar"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
