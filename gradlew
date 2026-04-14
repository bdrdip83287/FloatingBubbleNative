#!/bin/sh

# Gradle wrapper script for Unix

# Find Java
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD="java"
fi

# Set classpath
CLASSPATH="gradle/wrapper/gradle-wrapper.jar"

# Execute Gradle
exec "$JAVA_CMD" -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
