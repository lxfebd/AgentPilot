# 圆角归并脚本（PowerShell）
# 按 ui-shape-migration-map.md 的映射表批量替换硬编码圆角。
# 只替换单参数 RoundedCornerShape(<数字>dp) 形式；不动四角不对称、CircleShape、百分比。

param([string]$Dir)

$map = @{
    '6'  = 'AAShapes.tiny'
    '8'  = 'AAShapes.tiny'
    '10' = 'AAShapes.extraSmall'
    '12' = 'AAShapes.extraSmall'
    '13' = 'AAShapes.small'
    '14' = 'AAShapes.small'
    '15' = 'AAShapes.small'
    '16' = 'AAShapes.small'
    '17' = 'AAShapes.medium'
    '18' = 'AAShapes.medium'
    '20' = 'AAShapes.large'
    '22' = 'AAShapes.large'
    '24' = 'AAShapes.extraLarge'
    '26' = 'AAShapes.extraLarge'
}

$totalChanged = 0
$changedFiles = @()

Get-ChildItem $Dir -Filter '*.kt' -Recurse | ForEach-Object {
    $file = $_
    $text = Get-Content $file.FullName -Raw -Encoding UTF8
    if ($text -notmatch 'RoundedCornerShape\(\d+(\.\d+)?\.dp\)') { return }

    $original = $text
    foreach ($k in $map.Keys) {
        # 单参数形式：RoundedCornerShape(12.dp)
        $text = [regex]::Replace($text, "RoundedCornerShape\($([regex]::Escape($k))\.dp\)", ($map[$k] + 'Shape'))
    }

    if ($text -ne $original) {
        [System.IO.File]::WriteAllText($file.FullName, $text, (New-Object System.Text.UTF8Encoding $false))
        $n = ([regex]::Matches($original, 'RoundedCornerShape\(\d+(\.\d+)?\.dp\)')).Count
        $m = ([regex]::Matches($text, 'RoundedCornerShape\(\d+(\.\d+)?\.dp\)')).Count
        Write-Host ("  {0,-46} {1,3} -> {2,3}" -f $file.Name, $n, $m)
        $changedFiles += $file.FullName
        $totalChanged += ($n - $m)
    }
}

Write-Host ""
Write-Host "改动文件: $($changedFiles.Count)  替换总数: $totalChanged"