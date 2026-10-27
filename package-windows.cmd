@echo off
rem Builds a self-contained Windows app (no Java install needed) in target\dist\EPUB Reader.
rem Run "package-windows.cmd installer" to build an .msi instead. That needs the WiX Toolset (v4 or later):
rem wix.exe on PATH, in the folder named by WIX_DIR, or a WiX SDK package extracted to <drive>:\wix-<version>.
setlocal
cd /d "%~dp0"
set "PROJECT_DRIVE=%~d0"

set "TYPE=app-image"
set "EXTRA="
if /i not "%~1"=="installer" goto :build
call :setup_wix
if errorlevel 1 exit /b 1
set "TYPE=msi"
set "EXTRA=--win-menu --win-shortcut --win-dir-chooser --file-associations packaging\epub.properties --file-associations packaging\pdf.properties"

:build

rem jpackage marks the previous build read-only, which would make "mvnw clean" fail.
if exist "target\dist" (
    attrib -r "target\dist\*" /s /d >nul
    rmdir /s /q "target\dist" || (echo Close EPUB Reader and try again. & exit /b 1)
)

rem Produces target\app (app jar + PDFBox) and target\javafx (JavaFX modules).
call "%~dp0mvnw.cmd" -B clean package || exit /b 1

jpackage --type %TYPE% --name "EPUB Reader" --app-version 1.0.0 --vendor "EPUB Reader" ^
    --icon packaging\app.ico ^
    --dest target\dist ^
    --input target\app --main-jar epub-reader-1.0.0.jar --main-class com.epubreader.ui.ReaderApp ^
    --module-path target\javafx ^
    --add-modules "javafx.controls,javafx.web,java.desktop,java.logging,java.xml,jdk.charsets,jdk.localedata" ^
    --java-options "--enable-native-access=javafx.graphics,javafx.web" ^
    --jlink-options "--strip-debug --no-header-files --no-man-pages --strip-native-commands --include-locales=en,th" ^
    %EXTRA% || exit /b 1

echo Done: target\dist
exit /b 0

rem ---------------------------------------------------------------------------
rem Puts wix.exe on PATH for jpackage and installs the WiX extensions it uses.
:setup_wix
where wix.exe >nul 2>&1 && goto :wix_found
if defined WIX_DIR if exist "%WIX_DIR%\wix.exe" set "PATH=%WIX_DIR%;%PATH%" & goto :wix_found
for /d %%D in ("%PROJECT_DRIVE%\wix-*") do (
    if exist "%%D\tools\net472\x64\wix.exe" set "WIX_FOUND=%%D\tools\net472\x64"
)
if defined WIX_FOUND set "PATH=%WIX_FOUND%;%PATH%" & goto :wix_found
echo WiX Toolset not found. Put wix.exe on PATH, or set WIX_DIR to the folder that contains it.
exit /b 1

:wix_found
for /f "delims=+" %%V in ('wix.exe --version') do set "WIX_VERSION=%%V"
echo Using WiX %WIX_VERSION%
wix.exe extension list -g > "%TEMP%\epubreader-wix.txt" 2>&1
findstr /c:"WIX7015" "%TEMP%\epubreader-wix.txt" >nul && goto :wix_eula
call :wix_extension WixToolset.Util.wixext || exit /b 1
call :wix_extension WixToolset.UI.wixext || exit /b 1
exit /b 0

:wix_eula
echo WiX 7 needs its licence accepted first. Read OSMFEULA.txt in the WiX package, then run:
echo     wix.exe eula accept wix7
exit /b 1

:wix_extension
findstr /i /c:"%~1 %WIX_VERSION%" "%TEMP%\epubreader-wix.txt" >nul && exit /b 0
echo Installing WiX extension %~1 %WIX_VERSION%...
wix.exe extension add -g %~1/%WIX_VERSION%
exit /b
