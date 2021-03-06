@echo off
rem Licensed to the Apache Software Foundation (ASF) under one
rem or more contributor license agreements.  See the NOTICE file
rem distributed with this work for additional information
rem regarding copyright ownership.  The ASF licenses this file
rem to you under the Apache License, Version 2.0 (the
rem "License"); you may not use this file except in compliance
rem with the License.  You may obtain a copy of the License at
rem
rem   http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing,
rem software distributed under the License is distributed on an
rem "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
rem KIND, either express or implied.  See the License for the
rem specific language governing permissions and limitations
rem under the License.

setlocal

if NOT "%ARTEMIS_INSTANCE%"=="" goto CHECK_ARTEMIS_INSTANCE
PUSHD .
CD %~dp0..
set ARTEMIS_INSTANCE=%CD%
POPD

:CHECK_ARTEMIS_INSTANCE
if exist "%ARTEMIS_INSTANCE%\bin\activemq.cmd" goto CHECK_JAVA

:NO_HOME
echo ARTEMIS_INSTANCE environment variable is set incorrectly. Please set ARTEMIS_INSTANCE.
goto END

:CHECK_JAVA
set _JAVACMD=%JAVACMD%

if "%JAVA_HOME%" == "" goto NO_JAVA_HOME
if not exist "%JAVA_HOME%\bin\java.exe" goto NO_JAVA_HOME
if "%_JAVACMD%" == "" set _JAVACMD=%JAVA_HOME%\bin\java.exe
goto RUN_JAVA

:NO_JAVA_HOME
if "%_JAVACMD%" == "" set _JAVACMD=java.exe
echo.
echo Warning: JAVA_HOME environment variable is not set.
echo.

:RUN_JAVA

rem "Set Defaults."
set JAVA_ARGS=-XX:+UseParallelGC -XX:+AggressiveOpts -XX:+UseFastAccessorMethods -Xms512M -Xmx1024M
set ARTEMIS_LOGGING_CONF=file:%ARTEMIS_INSTANCE%\etc\logging.properties
set ARTEMIS_DATA_DIR=%ARTEMIS_INSTANCE%\data
set ARTEMIS_LOG_MANAGER=org.jboss.logmanager.LogManager

rem "Load Profile Config"
call "%ARTEMIS_INSTANCE%\etc\artemis.profile.cmd" %*

rem "Create full JVM Args"
set JVM_ARGS=%JAVA_ARGS%
if not "%ARTEMIS_CLUSTER_PROPS%"=="" set JVM_ARGS=%JVM_ARGS% %ARTEMIS_CLUSTER_PROPS%
set JVM_ARGS=%JVM_ARGS% -classpath "%ARTEMIS_HOME%\lib\artemis-boot.jar"
set JVM_ARGS=%JVM_ARGS% -Dartemis.home="%ARTEMIS_HOME%"
set JVM_ARGS=%JVM_ARGS% -Dartemis.instance="%ARTEMIS_INSTANCE%"
set JVM_ARGS=%JVM_ARGS% -Ddata.dir="%ARTEMIS_DATA_DIR%"
set JVM_ARGS=%JVM_ARGS% -Djava.util.logging.manager="%ARTEMIS_LOG_MANAGER%"
set JVM_ARGS=%JVM_ARGS% -Dlogging.configuration="%ARTEMIS_LOGGING_CONF%"
if not "%DEBUG_ARGS%"=="" set JVM_ARGS=%JVM_ARGS% %DEBUG_ARGS%

"%_JAVACMD%" %JVM_ARGS% org.apache.activemq.artemis.boot.Artemis %*

:END
endlocal
GOTO :EOF

:EOF
