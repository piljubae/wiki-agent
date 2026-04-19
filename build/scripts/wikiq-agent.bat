@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  wikiq-agent startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and WIKIQ_AGENT_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx2g"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\wikiq-agent-1.0.0.jar;%APP_HOME%\lib\koog-agents-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-a2a-server-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-a2a-client-jvm-0.8.0.jar;%APP_HOME%\lib\a2a-transport-server-jsonrpc-http-jvm-0.8.0.jar;%APP_HOME%\lib\a2a-transport-client-jsonrpc-http-jvm-0.8.0.jar;%APP_HOME%\lib\agents-mcp-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-llms-all-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-event-handler-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-longterm-memory-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-memory-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-opentelemetry-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-snapshot-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-tokenizer-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-trace-jvm-0.8.0.jar;%APP_HOME%\lib\agents-ext-jvm-0.8.0.jar;%APP_HOME%\lib\agents-core-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-processor-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-cached-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-model-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-bedrock-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-anthropic-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-google-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-dashscope-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-deepseek-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-mistralai-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-openai-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-openrouter-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-openai-client-base-jvm-0.8.0.jar;%APP_HOME%\lib\http-client-ktor-jvm-0.8.0.jar;%APP_HOME%\lib\embeddings-llm-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-ollama-client-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-executor-clients-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-structure-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-cache-files-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-cache-redis-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-cache-model-jvm-0.8.0.jar;%APP_HOME%\lib\agents-tools-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-markdown-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-tokenizer-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-xml-jvm-0.8.0.jar;%APP_HOME%\lib\agents-features-a2a-core-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-model-jvm-0.8.0.jar;%APP_HOME%\lib\utils-jvm-0.8.0.jar;%APP_HOME%\lib\agents-mcp-metadata-jvm-0.8.0.jar;%APP_HOME%\lib\prompt-llm-jvm-0.8.0.jar;%APP_HOME%\lib\agents-utils-jvm-0.8.0.jar;%APP_HOME%\lib\rag-vector-jvm-0.8.0.jar;%APP_HOME%\lib\embeddings-base-jvm-0.8.0.jar;%APP_HOME%\lib\http-client-core-jvm-0.8.0.jar;%APP_HOME%\lib\rag-base-jvm-0.8.0.jar;%APP_HOME%\lib\serialization-jackson-0.8.0.jar;%APP_HOME%\lib\serialization-core-jvm-0.8.0.jar;%APP_HOME%\lib\a2a-server-jvm-0.8.0.jar;%APP_HOME%\lib\a2a-client-jvm-0.8.0.jar;%APP_HOME%\lib\a2a-transport-core-jsonrpc-jvm-0.8.0.jar;%APP_HOME%\lib\a2a-core-jvm-0.8.0.jar;%APP_HOME%\lib\kaml-jvm-0.67.0.jar;%APP_HOME%\lib\ktor-client-apache5-jvm-3.2.2.jar;%APP_HOME%\lib\ktor-serialization-kotlinx-json-jvm-3.2.2.jar;%APP_HOME%\lib\ktor-serialization-kotlinx-jvm-3.2.2.jar;%APP_HOME%\lib\kotlinx-serialization-json-io-jvm-1.10.0.jar;%APP_HOME%\lib\ktor-client-content-negotiation-jvm-3.2.2.jar;%APP_HOME%\lib\ktor-client-logging-jvm-3.2.2.jar;%APP_HOME%\lib\kotlin-sdk-client-jvm-0.8.1.jar;%APP_HOME%\lib\ktor-client-cio-jvm-3.2.2.jar;%APP_HOME%\lib\ktor-client-core-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-network-tls-jvm-3.2.2.jar;%APP_HOME%\lib\ktor-server-cio-jvm-3.2.2.jar;%APP_HOME%\lib\ktor-server-content-negotiation-jvm-3.2.2.jar;%APP_HOME%\lib\ktor-server-sse-jvm-3.2.2.jar;%APP_HOME%\lib\ktor-server-cors-jvm-3.2.2.jar;%APP_HOME%\lib\kotlin-sdk-core-jvm-0.8.1.jar;%APP_HOME%\lib\ktor-server-websockets-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-server-core-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-http-cio-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-websocket-serialization-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-serialization-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-websockets-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-http-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-events-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-sse-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-network-jvm-3.2.3.jar;%APP_HOME%\lib\ktor-utils-jvm-3.2.3.jar;%APP_HOME%\lib\kotlinx-serialization-core-jvm-1.10.0.jar;%APP_HOME%\lib\kotlinx-schema-generator-json-jvm-0.4.4.jar;%APP_HOME%\lib\kotlinx-schema-generator-core-jvm-0.4.4.jar;%APP_HOME%\lib\kotlinx-schema-json-jvm-0.4.4.jar;%APP_HOME%\lib\kotlinx-serialization-json-jvm-1.10.0.jar;%APP_HOME%\lib\kotlinx-coroutines-slf4j-1.10.2.jar;%APP_HOME%\lib\kotlinx-coroutines-reactive-1.10.2.jar;%APP_HOME%\lib\kotlinx-coroutines-jdk9-1.10.2.jar;%APP_HOME%\lib\bedrockruntime-jvm-1.6.17.jar;%APP_HOME%\lib\aws-config-jvm-1.6.17.jar;%APP_HOME%\lib\aws-http-jvm-1.6.17.jar;%APP_HOME%\lib\aws-endpoint-jvm-1.6.17.jar;%APP_HOME%\lib\aws-json-protocols-jvm-1.6.2.jar;%APP_HOME%\lib\aws-xml-protocols-jvm-1.6.2.jar;%APP_HOME%\lib\aws-protocol-core-jvm-1.6.2.jar;%APP_HOME%\lib\aws-event-stream-jvm-1.6.2.jar;%APP_HOME%\lib\aws-signing-default-jvm-1.6.2.jar;%APP_HOME%\lib\http-auth-aws-jvm-1.6.2.jar;%APP_HOME%\lib\aws-signing-common-jvm-1.6.2.jar;%APP_HOME%\lib\http-client-engine-default-jvm-1.6.2.jar;%APP_HOME%\lib\http-client-engine-okhttp-jvm-1.6.2.jar;%APP_HOME%\lib\http-client-jvm-1.6.2.jar;%APP_HOME%\lib\aws-core-jvm-1.6.17.jar;%APP_HOME%\lib\smithy-client-jvm-1.6.2.jar;%APP_HOME%\lib\aws-credentials-jvm-1.6.2.jar;%APP_HOME%\lib\http-auth-jvm-1.6.2.jar;%APP_HOME%\lib\http-auth-api-jvm-1.6.2.jar;%APP_HOME%\lib\http-jvm-1.6.2.jar;%APP_HOME%\lib\identity-api-jvm-1.6.2.jar;%APP_HOME%\lib\telemetry-defaults-jvm-1.6.2.jar;%APP_HOME%\lib\logging-slf4j2-jvm-1.6.2.jar;%APP_HOME%\lib\telemetry-api-jvm-1.6.2.jar;%APP_HOME%\lib\serde-json-jvm-1.6.2.jar;%APP_HOME%\lib\serde-xml-jvm-1.6.2.jar;%APP_HOME%\lib\serde-form-url-jvm-1.6.2.jar;%APP_HOME%\lib\serde-jvm-1.6.2.jar;%APP_HOME%\lib\runtime-core-jvm-1.6.2.jar;%APP_HOME%\lib\okhttp-coroutines-5.3.2.jar;%APP_HOME%\lib\ktor-io-jvm-3.2.3.jar;%APP_HOME%\lib\kotlinx-coroutines-core-jvm-1.10.2.jar;%APP_HOME%\lib\jackson-databind-2.15.2.jar;%APP_HOME%\lib\jackson-annotations-2.15.2.jar;%APP_HOME%\lib\jackson-core-2.15.2.jar;%APP_HOME%\lib\jackson-module-kotlin-2.21.2.jar;%APP_HOME%\lib\kotlin-reflect-2.3.10.jar;%APP_HOME%\lib\snakeyaml-engine-kmp-jvm-3.0.3.jar;%APP_HOME%\lib\urlencoder-lib-jvm-1.6.0.jar;%APP_HOME%\lib\kotlinx-schema-annotations-jvm-0.4.4.jar;%APP_HOME%\lib\kotlinx-io-core-jvm-0.8.2.jar;%APP_HOME%\lib\kotlinx-collections-immutable-jvm-0.4.0.jar;%APP_HOME%\lib\kotlin-logging-jvm-8.0.01.jar;%APP_HOME%\lib\bolt-socket-mode-1.46.0.jar;%APP_HOME%\lib\bolt-1.46.0.jar;%APP_HOME%\lib\slack-app-backend-1.46.0.jar;%APP_HOME%\lib\slack-api-client-1.46.0.jar;%APP_HOME%\lib\opentelemetry-exporter-otlp-1.51.0.jar;%APP_HOME%\lib\opentelemetry-exporter-sender-okhttp-1.51.0.jar;%APP_HOME%\lib\okhttp-jvm-5.3.2.jar;%APP_HOME%\lib\okio-jvm-3.16.4.jar;%APP_HOME%\lib\kotlinx-datetime-jvm-0.7.1.jar;%APP_HOME%\lib\kotlinx-io-bytestring-jvm-0.8.2.jar;%APP_HOME%\lib\kotlin-stdlib-2.3.10.jar;%APP_HOME%\lib\nv-websocket-client-2.14.jar;%APP_HOME%\lib\logback-classic-1.5.13.jar;%APP_HOME%\lib\annotations-26.0.2-1.jar;%APP_HOME%\lib\slack-api-model-1.46.0.jar;%APP_HOME%\lib\commons-text-1.14.0.jar;%APP_HOME%\lib\logback-core-1.5.13.jar;%APP_HOME%\lib\httpclient5-5.5.jar;%APP_HOME%\lib\lettuce-core-7.2.1.RELEASE.jar;%APP_HOME%\lib\redis-authx-core-0.1.1-beta2.jar;%APP_HOME%\lib\slf4j-api-2.0.17.jar;%APP_HOME%\lib\gson-2.12.1.jar;%APP_HOME%\lib\commons-lang3-3.18.0.jar;%APP_HOME%\lib\opentelemetry-exporter-logging-1.51.0.jar;%APP_HOME%\lib\opentelemetry-exporter-otlp-common-1.51.0.jar;%APP_HOME%\lib\opentelemetry-exporter-common-1.51.0.jar;%APP_HOME%\lib\opentelemetry-sdk-extension-autoconfigure-spi-1.51.0.jar;%APP_HOME%\lib\opentelemetry-sdk-1.51.0.jar;%APP_HOME%\lib\config-1.4.3.jar;%APP_HOME%\lib\jansi-2.4.2.jar;%APP_HOME%\lib\error_prone_annotations-2.36.0.jar;%APP_HOME%\lib\httpcore5-h2-5.3.4.jar;%APP_HOME%\lib\httpcore5-5.3.4.jar;%APP_HOME%\lib\opentelemetry-sdk-trace-1.51.0.jar;%APP_HOME%\lib\opentelemetry-sdk-metrics-1.51.0.jar;%APP_HOME%\lib\opentelemetry-sdk-logs-1.51.0.jar;%APP_HOME%\lib\opentelemetry-sdk-common-1.51.0.jar;%APP_HOME%\lib\opentelemetry-api-1.51.0.jar;%APP_HOME%\lib\opentelemetry-context-1.51.0.jar;%APP_HOME%\lib\netty-resolver-dns-4.2.5.Final.jar;%APP_HOME%\lib\netty-handler-4.2.5.Final.jar;%APP_HOME%\lib\netty-transport-native-unix-common-4.2.5.Final.jar;%APP_HOME%\lib\netty-codec-dns-4.2.5.Final.jar;%APP_HOME%\lib\netty-codec-base-4.2.5.Final.jar;%APP_HOME%\lib\netty-transport-4.2.5.Final.jar;%APP_HOME%\lib\netty-resolver-4.2.5.Final.jar;%APP_HOME%\lib\netty-buffer-4.2.5.Final.jar;%APP_HOME%\lib\netty-common-4.2.5.Final.jar;%APP_HOME%\lib\reactor-core-3.6.6.jar;%APP_HOME%\lib\reactive-streams-1.0.4.jar


@rem Execute wikiq-agent
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %WIKIQ_AGENT_OPTS%  -classpath "%CLASSPATH%" io.github.veronikapj.wikiq.MainKt %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable WIKIQ_AGENT_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%WIKIQ_AGENT_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
