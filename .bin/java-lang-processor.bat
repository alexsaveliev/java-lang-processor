@echo off
"%JAVA_HOME%/bin/java.exe" -classpath "%~dp0/java-lang-processor.jar" com.sourcegraph.langp.Main %*
