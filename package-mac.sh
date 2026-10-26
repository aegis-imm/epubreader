#!/bin/sh
# Builds a self-contained macOS disk image (no Java install needed) in target/dist.
# Run this on a Mac: JavaFX jars are platform-specific, so a Mac build must be made on macOS.
set -e
cd "$(dirname "$0")"

# Produces target/app (app jar + PDFBox) and target/javafx (JavaFX modules).
./mvnw -B clean package

jpackage --type dmg --name "EPUB Reader" --app-version 1.0.0 --vendor "EPUB Reader" \
    --mac-package-identifier com.epubreader \
    --dest target/dist \
    --input target/app --main-jar epub-reader-1.0.0.jar --main-class com.epubreader.ui.ReaderApp \
    --module-path target/javafx \
    --add-modules "javafx.controls,javafx.web,java.desktop,java.logging,java.xml,jdk.charsets,jdk.localedata" \
    --java-options "--enable-native-access=javafx.graphics,javafx.web" \
    --jlink-options "--strip-debug --no-header-files --no-man-pages --strip-native-commands --include-locales=en,th"

echo "Done: target/dist"
