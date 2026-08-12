Unicode true

!ifndef VERSION
  !error "VERSION define is required"
!endif
!ifndef SOURCE_REVISION
  !error "SOURCE_REVISION define is required"
!endif
!ifndef PAYLOAD_DIR
  !error "PAYLOAD_DIR define is required"
!endif
!ifndef OUTPUT_DIR
  !error "OUTPUT_DIR define is required"
!endif
!ifndef ICON_FILE
  !error "ICON_FILE define is required"
!endif

!include "MUI2.nsh"

!define PRODUCT_NAME "Chat Room"
!define PRODUCT_EXE "ChatClient.exe"
!define PRODUCT_UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\ChatRoom"

Name "${PRODUCT_NAME}"
OutFile "${OUTPUT_DIR}\ChatRoom-${VERSION}-unsigned-verification-Setup.exe"
InstallDir "$LOCALAPPDATA\Programs\ChatRoom"
InstallDirRegKey HKCU "${PRODUCT_UNINSTALL_KEY}" "InstallLocation"
RequestExecutionLevel user
AllowRootDirInstall false
SetCompressor /SOLID lzma
CRCCheck force
ManifestDPIAware true
ManifestSupportedOS Win10
VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1033 "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey /LANG=1033 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1033 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1033 "FileDescription" "Chat Room Windows Installer"
VIAddVersionKey /LANG=1033 "LegalCopyright" "Chat Room project contributors"

!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\${PRODUCT_EXE}"
!define MUI_FINISHPAGE_RUN_NOTCHECKED

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

LangString MainSectionName ${LANG_SIMPCHINESE} "聊天软件客户端（必需）"
LangString MainSectionName ${LANG_ENGLISH} "Chat client (required)"

Section "$(MainSectionName)" MainSection
  SectionIn RO
  SetShellVarContext current
  SetOutPath "$INSTDIR"
  File /r "${PAYLOAD_DIR}\*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\Chat Room"
  CreateShortcut "$SMPROGRAMS\Chat Room\Chat Room.lnk" "$INSTDIR\${PRODUCT_EXE}"
  CreateShortcut "$SMPROGRAMS\Chat Room\Uninstall Chat Room.lnk" "$INSTDIR\Uninstall.exe"

  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "DisplayName" "${PRODUCT_NAME}"
  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "Publisher" "Chat Room project contributors"
  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\${PRODUCT_EXE}"
  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "UninstallString" "$\"$INSTDIR\Uninstall.exe$\""
  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "QuietUninstallString" "$\"$INSTDIR\Uninstall.exe$\" /S"
  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "SourceRevision" "${SOURCE_REVISION}"
  WriteRegDWORD HKCU "${PRODUCT_UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${PRODUCT_UNINSTALL_KEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  SetShellVarContext current
  IfFileExists "$INSTDIR\${PRODUCT_EXE}" 0 unsafe_uninstall
  Delete "$SMPROGRAMS\Chat Room\Chat Room.lnk"
  Delete "$SMPROGRAMS\Chat Room\Uninstall Chat Room.lnk"
  RMDir "$SMPROGRAMS\Chat Room"
  DeleteRegKey HKCU "${PRODUCT_UNINSTALL_KEY}"
  RMDir /r "$INSTDIR"
  Goto uninstall_done

  unsafe_uninstall:
    MessageBox MB_ICONSTOP "Chat Room executable is missing; refusing to remove this directory."
    SetErrorLevel 2
    Abort

  uninstall_done:
SectionEnd
