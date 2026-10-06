<#
.SYNOPSIS
  Waits for the game window (a java/javaw window titled "Minecraft*") and moves it to the first non-primary monitor,
  keeping its size. Started detached by run.ps1; exits once moved, or after -TimeoutSeconds.
#>
param([int]$TimeoutSeconds = 180)

Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class AetheriumWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@

$target = [System.Windows.Forms.Screen]::AllScreens | Where-Object { -not $_.Primary } | Select-Object -First 1
if (-not $target) { return }
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $window = Get-Process java, javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
    if ($window) {
        $r = New-Object AetheriumWin+RECT
        [AetheriumWin]::GetWindowRect($window.MainWindowHandle, [ref]$r) | Out-Null
        $w = $r.Right - $r.Left
        $h = $r.Bottom - $r.Top
        $area = $target.WorkingArea
        $x = $area.X + [math]::Max(0, [int](($area.Width - $w) / 2))
        $y = $area.Y + [math]::Max(0, [int](($area.Height - $h) / 2))
        # SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
        [AetheriumWin]::SetWindowPos($window.MainWindowHandle, [IntPtr]::Zero, $x, $y, 0, 0, 0x0001 -bor 0x0004 -bor 0x0010) | Out-Null
        return
    }
    Start-Sleep -Milliseconds 500
}
