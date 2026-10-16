# A7 (0.8.2-a4): установка Hydra обычным пользователем Windows — без прав администратора.
# Запускается в CI от администратора раннера: заводит обычного пользователя и от его имени ставит
# MSI и EXE (тихо и с окнами), начисто и поверх прошлой версии. Каждая установка — с таймаутом:
# «зависание», которое видел владелец, здесь станет провалом с журналом msiexec и списком окон.
#
#   scripts/installer-nonadmin.ps1 -Msi new.msi -Exe new.exe [-OldMsi old.msi] -Out logs
param(
  [Parameter(Mandatory)] [string]$Msi,
  [Parameter(Mandatory)] [string]$Exe,
  [string]$OldMsi = "",
  [string]$Out = "installer-logs",
  [int]$TimeoutSec = 300
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $Out | Out-Null
$Out = (Resolve-Path $Out).Path
$failed = @()

# На Windows Server обычным пользователям ставить MSI по умолчанию нельзя (DisableMsi = 1, если не задан).
# На Windows 10/11 (у владельца) по умолчанию можно — выравниваем раннер под настольную Windows.
New-Item -Force "HKLM:\SOFTWARE\Policies\Microsoft\Windows\Installer" | Out-Null
Set-ItemProperty "HKLM:\SOFTWARE\Policies\Microsoft\Windows\Installer" DisableMsi 0 -Type DWord

$user = "hydratest"
$pass = "Hy-" + [guid]::NewGuid().ToString("N").Substring(0, 16) + "!7"
$sec = ConvertTo-SecureString $pass -AsPlainText -Force
if (Get-LocalUser $user -ErrorAction SilentlyContinue) { Remove-LocalUser $user }
New-LocalUser $user -Password $sec -PasswordNeverExpires -AccountNeverExpires | Out-Null
Add-LocalGroupMember -SID "S-1-5-32-545" -Member $user -ErrorAction SilentlyContinue
$cred = New-Object Management.Automation.PSCredential("$env:COMPUTERNAME\$user", $sec)
$isAdmin = (Get-LocalGroupMember -SID "S-1-5-32-544" | Where-Object { $_.Name -like "*\$user" })
if ($isAdmin) { throw "${user} — администратор, проверка бессмысленна" }

# Файлы установщиков должны быть доступны пользователю.
$work = "C:\hydra-installer-test"
New-Item -ItemType Directory -Force $work | Out-Null
icacls $work /grant "${user}:(OI)(CI)F" | Out-Null
foreach ($f in @($Msi, $Exe, $OldMsi) | Where-Object { $_ }) { Copy-Item $f $work -Force }
$msiW = Join-Path $work (Split-Path $Msi -Leaf)
$exeW = Join-Path $work (Split-Path $Exe -Leaf)
$oldW = if ($OldMsi) { Join-Path $work (Split-Path $OldMsi -Leaf) } else { "" }

# Текст окон (в т. ч. окна-сообщения об ошибке): заголовок и подписи дочерних элементов.
Add-Type @"
using System; using System.Text; using System.Collections.Generic; using System.Runtime.InteropServices;
public static class Win {
  delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr p, EnumProc f, IntPtr l);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  static string T(IntPtr h) { var s = new StringBuilder(1024); GetWindowText(h, s, 1024); return s.ToString(); }
  public static List<string> Texts(uint pid) {
    var r = new List<string>();
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p);
      if (p == pid) { var line = (IsWindowVisible(h) ? "[видно] " : "[скрыто] ") + T(h);
        EnumChildWindows(h, (c, l2) => { var t = T(c); if (t.Length > 0) line += " | " + t; return true; }, IntPtr.Zero);
        r.Add(line); }
      return true; }, IntPtr.Zero);
    return r;
  }
}
"@

function Snapshot([string]$why) {
  "--- ${why}: процессы и окна" | Out-File -Append "$Out\windows.txt"
  $ps = Get-Process | Where-Object { $_.Name -match 'msiexec|Hydra|consent|java|sing-box|xray' -or $_.MainWindowTitle }
  $ps | Select-Object Id, Name, SessionId, MainWindowTitle | Format-Table -AutoSize | Out-String -Width 300 |
    Out-File -Append "$Out\windows.txt"
  foreach ($x in $ps | Where-Object { $_.Name -match 'msiexec|Hydra' }) {
    foreach ($t in [Win]::Texts([uint32]$x.Id)) { "  окно $($x.Name)#$($x.Id): $t" | Out-File -Append "$Out\windows.txt"; Write-Host "окно $($x.Name): $t" }
  }
}

# Start-Process -Credential передаёт процессу окружение администратора раннера (TEMP, профиль) — у владельца
# окружение своё. Подставляем папки пользователя, иначе обёртка EXE не может распаковать MSI во «временную папку»
# администратора (createTempFile … failed) и висит на окне с ошибкой.
$userEnv = @{ TEMP = "C:\Users\$user\AppData\Local\Temp"; TMP = "C:\Users\$user\AppData\Local\Temp";
  USERPROFILE = "C:\Users\$user"; LOCALAPPDATA = "C:\Users\$user\AppData\Local"; APPDATA = "C:\Users\$user\AppData\Roaming"; USERNAME = $user }

function Run-As([string]$name, [string]$file, [string[]]$argv, [bool]$expectWindow = $false) {
  Write-Host "== $name"
  $saved = @{}; foreach ($k in $userEnv.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $userEnv[$k]) }
  try { Run-AsInner $name $file $argv $expectWindow } finally { foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) } }
}

function Run-AsInner([string]$name, [string]$file, [string[]]$argv, [bool]$expectWindow) {
  $p = Start-Process -FilePath $file -ArgumentList $argv -Credential $cred -LoadUserProfile -WorkingDirectory $work -PassThru
  $t0 = Get-Date
  if ($expectWindow) {
    # С окнами: мастер должен показать окно, а не просить права администратора (consent.exe) и не висеть без окна.
    $seen = $null
    while (((Get-Date) - $t0).TotalSeconds -lt 120 -and -not $seen) {
      Start-Sleep 3
      if (Get-Process consent -ErrorAction SilentlyContinue) { Snapshot "${name}: запрос прав"; $script:failed += "${name}: Windows просит права администратора (UAC)"; break }
      $seen = Get-Process msiexec -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match 'Hydra' }
    }
    Snapshot "${name}: через $([int]((Get-Date) - $t0).TotalSeconds) с"
    if (-not $seen) { $script:failed += "${name}: окно установщика не появилось за 120 с" }
    else { Write-Host "окно: $($seen.MainWindowTitle -join ', ') через $([int]((Get-Date) - $t0).TotalSeconds) с" }
    Get-Process msiexec, consent -ErrorAction SilentlyContinue | Where-Object { $_.Id -ne $null } | ForEach-Object { try { $_.Kill() } catch {} }
    try { $p.Kill() } catch {}
    Start-Sleep 5
    return
  }
  if (-not $p.WaitForExit($TimeoutSec * 1000)) {
    Snapshot "${name}: зависла"
    $script:failed += "${name}: не закончилась за $TimeoutSec с"
    Get-Process msiexec -ErrorAction SilentlyContinue | ForEach-Object { try { $_.Kill() } catch {} }
    try { $p.Kill() } catch {}
    return
  }
  $code = $p.ExitCode
  $sec = [int]((Get-Date) - $t0).TotalSeconds
  Write-Host "код $code за $sec с"
  if ($code -ne 0 -and $code -ne 3010) { $script:failed += "${name}: код $code" }
}

# Окружение пользователя, от имени которого идут установки (TEMP — туда обёртка EXE распаковывает MSI).
$envOut = Join-Path $work "user-env.txt"
$pe = Start-Process cmd.exe -ArgumentList @("/c", "ver") -Credential $cred -LoadUserProfile -WorkingDirectory $work -PassThru -Wait   # создаёт профиль
foreach ($k in $userEnv.Keys) { [Environment]::SetEnvironmentVariable($k, $userEnv[$k]) }
New-Item -ItemType Directory -Force $userEnv.TEMP -ErrorAction SilentlyContinue | Out-Null
$pe = Start-Process cmd.exe -ArgumentList @("/c", "set > `"$envOut`" & whoami /groups >> `"$envOut`" & echo x > `"%TEMP%\hydra-probe.txt`" && echo TEMP-WRITABLE >> `"$envOut`"") -Credential $cred -LoadUserProfile -WorkingDirectory $work -PassThru -Wait
Get-Content $envOut -ErrorAction SilentlyContinue | Where-Object { $_ -match '^(TEMP|TMP|USERPROFILE|LOCALAPPDATA)=|Mandatory Label|BUILTIN|TEMP-WRITABLE' } | ForEach-Object { Write-Host "user: $_" }

$profileDir = "C:\Users\$user"
$installed = "$profileDir\AppData\Local\Hydra\Hydra.exe"
function Check-Installed([string]$name) {
  if (Test-Path $installed) { Write-Host "${name}: $installed на месте" }
  else { $script:failed += "${name}: нет $installed" }
}
function Uninstall([string]$name) {
  Run-As "${name}: удаление" "msiexec.exe" @("/x", "`"$msiW`"", "/qn", "/l*v", "`"$work\$name-uninstall.log`"")
}

# 1. MSI начисто, тихо.
Run-As "msi-fresh" "msiexec.exe" @("/i", "`"$msiW`"", "/qn", "/l*v", "`"$work\msi-fresh.log`"")
Check-Installed "msi-fresh"
Uninstall "msi-fresh"

# 2. Поверх прошлой версии (как у владельца: обновление стоящей Hydra).
if ($oldW) {
  Run-As "old-msi" "msiexec.exe" @("/i", "`"$oldW`"", "/qn", "/l*v", "`"$work\old-msi.log`"")
  Run-As "msi-upgrade" "msiexec.exe" @("/i", "`"$msiW`"", "/qn", "/l*v", "`"$work\msi-upgrade.log`"")
  Check-Installed "msi-upgrade"
  Uninstall "msi-upgrade"
}

# 3. EXE (обёртка jpackage распаковывает MSI во временную папку и передаёт ему аргументы).
Run-As "exe-fresh" $exeW @("/qn", "/l*v", "`"$work\exe-fresh.log`"")
Check-Installed "exe-fresh"
Uninstall "exe-fresh"

# 4. С окнами — так ставит владелец двойным щелчком.
Run-As "msi-ui" "msiexec.exe" @("/i", "`"$msiW`"", "/l*v", "`"$work\msi-ui.log`"") $true
Run-As "exe-ui" $exeW @() $true

Copy-Item "$work\*.log" $Out -ErrorAction SilentlyContinue
Get-WinEvent -LogName Application -MaxEvents 200 -ErrorAction SilentlyContinue | Where-Object { $_.ProviderName -eq 'MsiInstaller' } |
  Select-Object TimeCreated, Id, Message | Format-List | Out-File "$Out\msiinstaller-events.txt"
Remove-LocalUser $user -ErrorAction SilentlyContinue

if ($failed.Count) { Write-Host "ПРОВАЛЫ:"; $failed | ForEach-Object { Write-Host " - $_" }; exit 1 }
Write-Host "Установка без прав администратора: всё прошло"
