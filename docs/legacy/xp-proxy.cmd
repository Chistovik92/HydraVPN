@echo off
rem Windows XP SP3 (и новее): прописать HTTP-прокси Hydra из локальной сети в настройках Internet Explorer / WinInet.
rem Использование:  xp-proxy.cmd on 192.168.1.10:2080     - включить
rem                 xp-proxy.cmd off                       - выключить
rem Логин и пароль прокси браузер спросит при первом открытии страницы.
rem Работает только для программ, использующих системный прокси (IE, Chrome 49, Opera 36, Firefox ESR 52 с "Использовать системные").
set K=HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings
if /i "%1"=="on" (
  if "%2"=="" ( echo Укажите адрес:порт, например 192.168.1.10:2080 & exit /b 1 )
  reg add "%K%" /v ProxyServer /t REG_SZ /d "%2" /f >nul
  reg add "%K%" /v ProxyEnable /t REG_DWORD /d 1 /f >nul
  echo Прокси %2 включён. Перезапустите браузер.
) else if /i "%1"=="off" (
  reg add "%K%" /v ProxyEnable /t REG_DWORD /d 0 /f >nul
  echo Прокси выключен.
) else (
  echo Использование: xp-proxy.cmd on адрес:порт ^| off
)
