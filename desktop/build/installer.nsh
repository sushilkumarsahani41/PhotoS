; --- PhotoS installer additions ---

; Close a running PhotoS before installing or uninstalling, so its files are not locked.
!macro customInit
  nsExec::Exec 'taskkill /F /T /IM "PhotoS.exe"'
  Sleep 500
!macroend
!macro customUnInit
  nsExec::Exec 'taskkill /F /T /IM "PhotoS.exe"'
  Sleep 500
!macroend

; An earlier PhotoS whose uninstaller is missing or failed (for example after its folder was deleted by hand)
; must not stop a new install: carry on and overwrite the files.
!macro customUnInstallCheck
  ClearErrors
!macroend
!macro customUnInstallCheckCurrentUser
  ClearErrors
!macroend

; Start menu entry so PhotoS can be removed without opening Windows Settings.
!macro customInstall
  CreateDirectory "$SMPROGRAMS\PhotoS"
  CreateShortCut "$SMPROGRAMS\PhotoS\Uninstall PhotoS.lnk" "$INSTDIR\Uninstall PhotoS.exe"
!macroend
!macro customUnInstall
  Delete "$SMPROGRAMS\PhotoS\Uninstall PhotoS.lnk"
  RMDir "$SMPROGRAMS\PhotoS"
!macroend
