#!/bin/bash
# Build Windows MSI installer for HydraVPN Desktop
# Requires: WiX Toolset v3.11+ (https://wixtoolset.org/)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

echo "=== Building Windows MSI Installer ==="

# Check for WiX
if ! command -v candle &> /dev/null; then
    echo "ERROR: WiX Toolset not found. Install from https://wixtoolset.org/"
    exit 1
fi

# Build desktop JAR
echo "Building desktop JAR..."
./gradlew :desktop:jar --no-daemon

# Create staging directory
STAGING_DIR="desktop/build/msi-staging"
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"

# Copy JAR and dependencies
cp desktop/build/libs/*.jar "$STAGING_DIR/"

# Copy wintun.dll if available
if [ -f "desktop/libs/wintun.dll" ]; then
    cp desktop/libs/wintun.dll "$STAGING_DIR/"
fi

# Create WiX source file
cat > "$STAGING_DIR/hydravpn.wxs" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="*" Name="HydraVPN" Language="1033" Version="1.0.0.0" Manufacturer="HydraVPN" UpgradeCode="PUT-GUID-HERE">
    <Package InstallerVersion="200" Compressed="yes" InstallScope="perMachine" />
    <MajorUpgrade DowngradeErrorMessage="A newer version is already installed." />
    <MediaTemplate />
    <Feature Id="ProductFeature" Title="HydraVPN" Level="1">
      <ComponentGroupRef Id="ProductComponents" />
    </Feature>
  </Product>
  <Fragment>
    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="ProgramFilesFolder">
        <Directory Id="INSTALLFOLDER" Name="HydraVPN" />
      </Directory>
    </Directory>
  </Fragment>
  <Fragment>
    <ComponentGroup Id="ProductComponents" Directory="INSTALLFOLDER">
      <Component Id="MainJar" Guid="PUT-GUID-HERE">
        <File Id="HydraVPN.jar" Source="HydraVPN.jar" KeyPath="yes" />
      </Component>
      <Component Id="WinTunDll" Guid="PUT-GUID-HERE">
        <File Id="wintun.dll" Source="wintun.dll" KeyPath="yes" />
      </Component>
    </ComponentGroup>
  </Fragment>
</Wix>
EOF

# Compile and link
candle -out "$STAGING_DIR/hydravpn.wixobj" "$STAGING_DIR/hydravpn.wxs"
light -out "desktop/build/distributions/HydraVPN-1.0.0.msi" "$STAGING_DIR/hydravpn.wixobj"

echo "MSI created: desktop/build/distributions/HydraVPN-1.0.0.msi"