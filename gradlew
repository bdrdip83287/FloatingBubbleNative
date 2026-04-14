#!/bin/sh

# Gradle wrapper script

# Find Java
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD="java"
fi

# Set JVM options
DEFAULT_JVM_OPTS='"-Xmx2048m" "-Xms512m"'
JAVA_OPTS="$JAVA_OPTS $DEFAULT_JVM_OPTS"

# Set classpath
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Execute Gradle
exec "$JAVA_CMD" $JAVA_OPTS -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
