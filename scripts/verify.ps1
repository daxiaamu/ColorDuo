param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    if (-not $env:ANDROID_HOME) {
        $candidate = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
        if (Test-Path $candidate) { $env:ANDROID_HOME = $candidate }
    }
    & .\gradlew.bat :app:assembleDebug :app:compileDebugUnitTestJavaWithJavac :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build/lint failed.' }
    # Launch JUnit directly: avoids Gradle Worker argfile encoding on Chinese Windows paths.
    $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $deps = Join-Path $gradleCache 'caches/modules-2/files-2.1'
    $junit = (Get-ChildItem "$deps/junit/junit/4.13.2" -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
    $hamcrest = (Get-ChildItem "$deps/org.hamcrest/hamcrest-core/1.3" -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
    if (-not $junit -or -not $hamcrest) { throw 'JUnit dependencies missing.' }
    $classpath = "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes;app/build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes;$junit;$hamcrest"
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
    & $javaExe -cp $classpath org.junit.runner.JUnitCore io.github.colorduo.StartupGateTest io.github.colorduo.DepthModelTest io.github.colorduo.EffectModeTest
    if ($LASTEXITCODE -ne 0) { throw 'Curve tests failed.' }
} finally { Pop-Location }
