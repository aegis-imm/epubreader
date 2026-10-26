# Builds a self-contained Windows app (no Java install needed) in target\dist\EPUB Reader.
# Pass -Installer to build an .msi instead (requires the WiX Toolset on PATH).
param([switch]$Installer)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Produces target\app (app jar + PDFBox) and target\javafx (JavaFX modules).
.\mvnw.cmd -B clean package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$type = if ($Installer) { 'msi' } else { 'app-image' }
$extra = if ($Installer) {
    @('--win-menu', '--win-shortcut', '--win-dir-chooser', '--file-associations', 'packaging/epub.properties',
      '--file-associations', 'packaging/pdf.properties')
} else { @() }

jpackage --type $type --name "EPUB Reader" --app-version 1.0.0 --vendor "EPUB Reader" `
    --dest target/dist `
    --input target/app --main-jar epub-reader-1.0.0.jar --main-class com.epubreader.ui.ReaderApp `
    --module-path target/javafx `
    --add-modules "javafx.controls,javafx.web,java.desktop,java.logging,java.xml,jdk.charsets,jdk.localedata" `
    --java-options "--enable-native-access=javafx.graphics,javafx.web" `
    --jlink-options "--strip-debug --no-header-files --no-man-pages --strip-native-commands --include-locales=en,th" `
    @extra
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Done: target\dist"
