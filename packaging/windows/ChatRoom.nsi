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
!ifdef EXPORT_UNINSTALLER
  !ifndef RELEASE_BUILD
    !error "EXPORT_UNINSTALLER requires RELEASE_BUILD"
  !endif
  !ifdef IMPORT_SIGNED_UNINSTALLER
    !error "EXPORT_UNINSTALLER and IMPORT_SIGNED_UNINSTALLER are mutually exclusive"
  !endif
!endif
!ifdef IMPORT_SIGNED_UNINSTALLER
  !ifndef RELEASE_BUILD
    !error "IMPORT_SIGNED_UNINSTALLER requires RELEASE_BUILD"
  !endif
!endif

!include "MUI2.nsh"
!include "WordFunc.nsh"
!insertmacro VersionCompare

!define PRODUCT_NAME "Chat Room"
!define PRODUCT_EXE "ChatClient.exe"
!define PRODUCT_UPDATE_LAUNCHER "ChatRoomUpdateLauncher.exe"
!define PRODUCT_UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\ChatRoom"
!define PRODUCT_INSTALL_ID "chat-room-windows-client-v1"
!define PRODUCT_INSTALL_MARKER ".chat-room-install.ini"
!define PRODUCT_RUNNING_MUTEX "Local\ChatRoom.WindowsClient.Running.v1"

Var StageDir
Var BackupDir
Var ExistingInstall
Var OwnStage

Name "${PRODUCT_NAME}"
!ifdef EXPORT_UNINSTALLER
  OutFile "${OUTPUT_DIR}\ChatRoom-${VERSION}-uninstaller-export-helper.exe"
!else ifdef RELEASE_BUILD
  OutFile "${OUTPUT_DIR}\ChatRoom-${VERSION}-Setup.exe"
!else
  OutFile "${OUTPUT_DIR}\ChatRoom-${VERSION}-unsigned-verification-Setup.exe"
!endif
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
!ifndef IMPORT_SIGNED_UNINSTALLER
  !insertmacro MUI_UNPAGE_CONFIRM
  !insertmacro MUI_UNPAGE_INSTFILES
!endif
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

!ifdef EXPORT_UNINSTALLER
  !uninstfinalize 'python "../../tools/export_windows_uninstaller.py" "%1" "${OUTPUT_DIR}/ChatRoom-${VERSION}-Uninstall.exe"'
!endif

Function .onInit
  System::Call 'kernel32::OpenMutexW(i 0x00100000, i 0, w "${PRODUCT_RUNNING_MUTEX}") p.r0'
  IntCmp $0 0 client_not_running
  System::Call 'kernel32::CloseHandle(p r0)'
  IfSilent running_client_abort 0
  MessageBox MB_ICONEXCLAMATION|MB_OK "Chat Room is running. Close it before installing or upgrading."
  running_client_abort:
  SetErrorLevel 4
  Abort
  client_not_running:
FunctionEnd

LangString MainSectionName ${LANG_SIMPCHINESE} "聊天软件客户端（必需）"
LangString MainSectionName ${LANG_ENGLISH} "Chat client (required)"

Function .onInstFailed
  StrCmp $OwnStage "1" 0 cleanup_done
  RMDir /r "$StageDir"
  cleanup_done:
FunctionEnd

Section "$(MainSectionName)" MainSection
  SectionIn RO
  SetShellVarContext current
  StrCpy $StageDir "$INSTDIR.__chatroom_stage"
  StrCpy $BackupDir "$INSTDIR.__chatroom_backup"
  StrCpy $ExistingInstall "0"
  StrCpy $OwnStage "0"

  IfFileExists "$StageDir\*.*" unsafe_install
  IfFileExists "$BackupDir\*.*" unsafe_install
  IfFileExists "$INSTDIR\*.*" 0 target_ready
  IfFileExists "$INSTDIR\${PRODUCT_INSTALL_MARKER}" 0 unsafe_install
  ReadINIStr $0 "$INSTDIR\${PRODUCT_INSTALL_MARKER}" "Installation" "ProductId"
  StrCmp $0 "${PRODUCT_INSTALL_ID}" 0 unsafe_install
  ReadINIStr $1 "$INSTDIR\${PRODUCT_INSTALL_MARKER}" "Installation" "Version"
  StrCmp $1 "" unsafe_install
  ${VersionCompare} "${VERSION}" "$1" $2
  StrCmp $2 "2" downgrade_install
  IfFileExists "$INSTDIR\${PRODUCT_EXE}" 0 unsafe_install
  StrCpy $ExistingInstall "1"

  target_ready:
  CreateDirectory "$StageDir"
  SetOutPath "$StageDir"
  WriteINIStr "$StageDir\${PRODUCT_INSTALL_MARKER}" "Installation" "ProductId" "${PRODUCT_INSTALL_ID}"
  WriteINIStr "$StageDir\${PRODUCT_INSTALL_MARKER}" "Installation" "Version" "${VERSION}"
  WriteINIStr "$StageDir\${PRODUCT_INSTALL_MARKER}" "Installation" "SourceRevision" "${SOURCE_REVISION}"
  StrCpy $OwnStage "1"
  File /r "${PAYLOAD_DIR}\*"
  WriteINIStr "$StageDir\${PRODUCT_INSTALL_MARKER}" "Installation" "ProductId" "${PRODUCT_INSTALL_ID}"
  WriteINIStr "$StageDir\${PRODUCT_INSTALL_MARKER}" "Installation" "Version" "${VERSION}"
  WriteINIStr "$StageDir\${PRODUCT_INSTALL_MARKER}" "Installation" "SourceRevision" "${SOURCE_REVISION}"
  !ifdef IMPORT_SIGNED_UNINSTALLER
    File /oname=Uninstall.exe "${OUTPUT_DIR}\ChatRoom-${VERSION}-Uninstall.exe"
  !else
    WriteUninstaller "$StageDir\Uninstall.exe"
  !endif
  SetOutPath "$TEMP"
  IfFileExists "$StageDir\${PRODUCT_EXE}" 0 stage_invalid
  IfFileExists "$StageDir\${PRODUCT_UPDATE_LAUNCHER}" 0 stage_invalid
  IfFileExists "$StageDir\sqldrivers\qsqlite.dll" 0 stage_invalid

  StrCmp $ExistingInstall "1" upgrade_swap fresh_swap

  upgrade_swap:
    ClearErrors
    Rename "$INSTDIR" "$BackupDir"
    IfErrors swap_failed
    ClearErrors
    Rename "$StageDir" "$INSTDIR"
    IfErrors restore_previous
    StrCpy $OwnStage "0"
    RMDir /r "$BackupDir"
    IfFileExists "$BackupDir\*.*" cleanup_failed 0
    Goto swap_complete

  restore_previous:
    ClearErrors
    Rename "$BackupDir" "$INSTDIR"
    IfErrors rollback_failed
    SetErrorLevel 2
    Abort "The new Chat Room version could not be activated; the previous version was restored."

  fresh_swap:
    RMDir "$INSTDIR"
    ClearErrors
    Rename "$StageDir" "$INSTDIR"
    IfErrors swap_failed
    StrCpy $OwnStage "0"
    Goto swap_complete

  stage_invalid:
    RMDir /r "$StageDir"
    StrCpy $OwnStage "0"
    SetErrorLevel 2
    Abort "The staged Chat Room payload is incomplete."

  cleanup_failed:
    SetErrorLevel 2
    Abort "The previous Chat Room program directory could not be removed."

  rollback_failed:
    SetErrorLevel 3
    Abort "Chat Room upgrade rollback failed; manual repair is required."

  swap_failed:
    RMDir /r "$StageDir"
    StrCpy $OwnStage "0"
    SetErrorLevel 2
    Abort "The Chat Room program directory could not be activated."

  unsafe_install:
    SetErrorLevel 2
    Abort "The target or temporary directory is not owned by Chat Room; refusing installation."

  downgrade_install:
    SetErrorLevel 2
    Abort "Installing an older Chat Room version over a newer version is not allowed."

  swap_complete:
  SetOutPath "$INSTDIR"

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
  WriteRegStr HKCU "${PRODUCT_UNINSTALL_KEY}" "InstallId" "${PRODUCT_INSTALL_ID}"
  WriteRegDWORD HKCU "${PRODUCT_UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${PRODUCT_UNINSTALL_KEY}" "NoRepair" 1
SectionEnd

!ifndef IMPORT_SIGNED_UNINSTALLER
Section "Uninstall"
  SetShellVarContext current
  IfFileExists "$INSTDIR\${PRODUCT_EXE}" 0 unsafe_uninstall
  IfFileExists "$INSTDIR\${PRODUCT_INSTALL_MARKER}" 0 unsafe_uninstall
  ReadINIStr $0 "$INSTDIR\${PRODUCT_INSTALL_MARKER}" "Installation" "ProductId"
  StrCmp $0 "${PRODUCT_INSTALL_ID}" 0 unsafe_uninstall
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
!endif
