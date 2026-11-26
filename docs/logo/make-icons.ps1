# Linky 로고(docs/logo/linky.svg)를 PNG와 Windows 아이콘(.ico)으로 만든다.
# SVG를 바꾸면 아래 그리기 값도 같이 바꾸고 다시 실행한다:
#   powershell -ExecutionPolicy Bypass -File docs\logo\make-icons.ps1
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$ink = [System.Drawing.ColorTranslator]::FromHtml('#26215C')
$teal = [System.Drawing.ColorTranslator]::FromHtml('#5DCAA5')
$white = [System.Drawing.Color]::White

function New-LinkyBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)
    $k = $size / 160.0
    $g.ScaleTransform($k, $k)

    # 바탕: 둥근 사각형 (rx 36)
    $r = 36.0; $d = $r * 2
    $bg = New-Object System.Drawing.Drawing2D.GraphicsPath
    $bg.AddArc(0, 0, $d, $d, 180, 90)
    $bg.AddArc(160 - $d, 0, $d, $d, 270, 90)
    $bg.AddArc(160 - $d, 160 - $d, $d, $d, 0, 90)
    $bg.AddArc(0, 160 - $d, $d, $d, 90, 90)
    $bg.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush $ink), $bg)

    # L 글자
    $pen = New-Object System.Drawing.Pen $teal, 18
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'; $pen.LineJoin = 'Round'
    $pts = [System.Drawing.PointF[]]@(
        (New-Object System.Drawing.PointF 54, 38),
        (New-Object System.Drawing.PointF 54, 108),
        (New-Object System.Drawing.PointF 104, 108))
    $g.DrawLines($pen, $pts)

    # 바로가기 화살표 (↙): 바깥에서 L 안쪽으로
    $wp = New-Object System.Drawing.Pen $white, 10
    $wp.StartCap = 'Round'; $wp.EndCap = 'Round'; $wp.LineJoin = 'Round'
    $g.DrawLine($wp, 112, 48, 80, 80)
    $head = [System.Drawing.PointF[]]@(
        (New-Object System.Drawing.PointF 80, 62),
        (New-Object System.Drawing.PointF 80, 80),
        (New-Object System.Drawing.PointF 98, 80))
    $g.DrawLines($wp, $head)

    $g.Dispose()
    return $bmp
}

function Get-PngBytes($bmp) {
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    # 쉼표: 배열이 바이트 하나하나로 풀려서 넘어가지 않게 통째로 돌려준다
    return ,$ms.ToArray()
}

# 미리보기·README용 큰 PNG
$big = New-LinkyBitmap 512
$big.Save((Join-Path $PSScriptRoot 'linky-512.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$big.Dispose()

# .ico: 여러 크기의 PNG를 한 파일에 담는다
$sizes = 16, 24, 32, 48, 64, 128, 256
$images = New-Object 'System.Collections.Generic.List[byte[]]'
foreach ($s in $sizes) {
    $b = New-LinkyBitmap $s
    $images.Add([byte[]](Get-PngBytes $b))
    $b.Dispose()
}
$ico = Join-Path $root 'desktop\icons\linky.ico'
New-Item -ItemType Directory (Split-Path $ico) -Force | Out-Null
$fs = [System.IO.File]::Create($ico)
$w = New-Object System.IO.BinaryWriter $fs
$w.Write([UInt16]0); $w.Write([UInt16]1); $w.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]; $len = $images[$i].Length
    $w.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $w.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([UInt16]1); $w.Write([UInt16]32)
    $w.Write([UInt32]$len); $w.Write([UInt32]$offset)
    $offset += $len
}
foreach ($img in $images) { $w.Write($img) }
$w.Close()
"만듦: docs\logo\linky-512.png, desktop\icons\linky.ico ($($sizes -join ', ') px)"
