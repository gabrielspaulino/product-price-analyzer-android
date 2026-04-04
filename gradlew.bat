@rem Gradle wrapper for Windows
@if "%DEBUG%"=="" @echo off
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set CLASSPATH=%DIRNAME%\gradle\wrapper\gradle-wrapper.jar
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
