# 清理不再使用的 import（仅处理 RoundedCornerShape，保守策略）。
# 判定：文件有该 import，但除 import 行外无任何使用 -> 删除该 import 行。

param([string]$Dir)

$removed = 0
$files = @()

Get-ChildItem $Dir -Filter '*.kt' -Recurse | ForEach-Object {
    $file = $_
    $lines = [System.IO.File]::ReadAllLines($file.FullName)

    $impIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -eq 'import androidx.compose.foundation.shape.RoundedCornerShape') { $impIdx = $i; break }
    }
    if ($impIdx -lt 0) { return }

    # 除 import 行外是否还有使用
    $used = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($i -eq $impIdx) { continue }
        if ($lines[$i] -match '\bRoundedCornerShape\b') { $used = $true; break }
    }
    if ($used) { return }

    $new = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($i -eq $impIdx) { continue }
        $new += $lines[$i]
    }
    [System.IO.File]::WriteAllLines($file.FullName, $new, (New-Object System.Text.UTF8Encoding $false))
    Write-Host ("  - " + $file.Name)
    $script:removed++
    $script:files += $file.Name
}

Write-Host ""
Write-Host "清理 import 文件数: $removed"