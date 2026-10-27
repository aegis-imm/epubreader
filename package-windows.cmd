@echo off
rem Builds a self-contained Windows app (no Java install needed) in target\dist\EPUB Reader.
rem Run "package-windows.cmd installer" to build an .msi instead (requires the WiX Toolset on PATH).
setlocal
cd /d "%~dp0"

rem jpackage marks the previous build read-only, which would make "mvnw clean" fail.
if exist "target\dist" (
    attrib -r "target\dist\*" /s /d >nul
    rmdir /s /q "target\dist" || (echo Close EPUB Reader and try again. & exit /b 1)
)

rem Produces target\app (app jar + PDFBox) and target\javafx (JavaFX modules).
call "%~dp0mvnw.cmd" -B clean package || exit /b 1

set "TYPE=app-image"
set "EXTRA="
if /i "%~1"=="installer" (
    set "TYPE=msi"
    set "EXTRA=--win-menu --win-shortcut --win-dir-chooser --file-associations packaging\epub.properties --file-associations packaging\pdf.properties"
)

jpackage --type %TYPE% --name "EPUB Reader" --app-version 1.0.0 --vendor "EPUB Reader" ^
    --dest target\dist ^
    --input target\app --main-jar epub-reader-1.0.0.jar --main-class com.epubreader.ui.ReaderApp ^
    --module-path target\javafx ^
    --add-modules "javafx.controls,javafx.web,java.desktop,java.logging,java.xml,jdk.charsets,jdk.localedata" ^
    --java-options "--enable-native-access=javafx.graphics,javafx.web" ^
    --jlink-options "--strip-debug --no-header-files --no-man-pages --strip-native-commands --include-locales=en,th" ^
    %EXTRA% || exit /b 1

echo Done: target\dist
