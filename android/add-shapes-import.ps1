# 为使用了 AAShapes 但未导入的 Kotlin 文件补 import。
# 插入位置：第一个 com.agentpilot.app.ui.designsystem.* import 之前（保持字母序）。

param([string]$Dir)

$imp = 'import com.agentpilot.app.ui.designsystem.AAShapes'
$fixed = 0

Get-ChildItem $Dir -Filter '*.kt' -Recurse | ForEach-Object {
    $file = $_
    $text = Get-Content $file.FullName -Raw -Encoding UTF8

    $usesAA = ([regex]::Matches($text, 'AAShapes\.')).Count
    if ($usesAA -eq 0) { return }
    if ($text -match [regex]::Escape($imp)) { return }

    # 找第一个 designsystem import 的行首位置
    $m = [regex]::Match($text, '(?m)^import com\.agentpilot\.app\.ui\.designsystem\.')
    if ($m.Success) {
        $text = $text.Insert($m.Index, $imp + "`n")
    } else {
        # 没有 designsystem import：插在最后一个 import 之后
        $all = [regex]::Matches($text, '(?m)^import .*$')
        if ($all.Count -eq 0) { return }
        $last = $all[$all.Count - 1]
        $ins = $last.Index + $last.Length + 1
        $text = $text.Insert($ins, "`n$imp")
    }

    [System.IO.File]::WriteAllText($file.FullName, $text, (New-Object System.Text.UTF8Encoding $false))
    Write-Host ("  + " + $file.Name)
    $script:fixed++
}

Write-Host ""
Write-Host "已补 import 文件数: $fixed"