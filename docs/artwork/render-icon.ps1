# Run from PowerShell on Windows. Only crops and resizes the supplied artwork.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$sourceImage = [System.Drawing.Image]::FromFile((Join-Path $PSScriptRoot 'Avatar.png'), $true)
$edge = [Math]::Min($sourceImage.Width, $sourceImage.Height)
$sourceRect = [System.Drawing.RectangleF]::new(($sourceImage.Width - $edge) / 2, ($sourceImage.Height - $edge) / 2, $edge, $edge)

function Draw-Portrait($Graphics, $Rect, [float]$Radius, $Crop) {
    $state = $Graphics.Save()
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    try {
        if ($Radius -gt 0) {
            $diameter = $Radius * 2
            $path.AddArc($Rect.X, $Rect.Y, $diameter, $diameter, 180, 90)
            $path.AddArc($Rect.Right - $diameter, $Rect.Y, $diameter, $diameter, 270, 90)
            $path.AddArc($Rect.Right - $diameter, $Rect.Bottom - $diameter, $diameter, $diameter, 0, 90)
            $path.AddArc($Rect.X, $Rect.Bottom - $diameter, $diameter, $diameter, 90, 90)
            $path.CloseFigure()
            $Graphics.SetClip($path)
        }
        $Graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $Graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $Graphics.DrawImage($sourceImage, $Rect, $Crop, [System.Drawing.GraphicsUnit]::Pixel)
    } finally { $Graphics.Restore($state); $path.Dispose() }
}

function Write-Icon([int]$Size, [string]$Destination, [switch]$Mac) {
    $format = if ($Mac) { [System.Drawing.Imaging.PixelFormat]::Format32bppArgb } else { [System.Drawing.Imaging.PixelFormat]::Format24bppRgb }
    $bitmap = [System.Drawing.Bitmap]::new($Size, $Size, $format)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::FromArgb(8, 16, 31))
        $rect = [System.Drawing.RectangleF]::new(0, 0, $Size, $Size)
        $radius = 0
        if ($Mac) {
            $graphics.Clear([System.Drawing.Color]::Transparent)
            $rect = [System.Drawing.RectangleF]::new(88, 88, 848, 848)
            $radius = 189
        }
        Draw-Portrait $graphics $rect $radius $sourceRect
        $bitmap.Save((Join-Path $repoRoot $Destination), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}

try {
    Write-Icon 1024 'Sources/CaochaoxingIOS/Assets.xcassets/AppIcon.appiconset/AppIcon.png'
    Write-Icon 1024 'Sources/CaochaoxingMac/Resources/AppIcon.png' -Mac
    Write-Icon 432 'android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png'
    $preview = [System.Drawing.Bitmap]::new(384, 192, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [System.Drawing.Graphics]::FromImage($preview)
    try {
        $graphics.Clear([System.Drawing.Color]::FromArgb(239, 237, 231))
        $visibleEdge = $edge * 2 / 3
        $crop = [System.Drawing.RectangleF]::new(($sourceImage.Width - $visibleEdge) / 2, ($sourceImage.Height - $visibleEdge) / 2, $visibleEdge, $visibleEdge)
        Draw-Portrait $graphics ([System.Drawing.RectangleF]::new(16, 16, 160, 160)) 80 $crop
        Draw-Portrait $graphics ([System.Drawing.RectangleF]::new(208, 16, 160, 160)) 35 $crop
        $preview.Save((Join-Path $PSScriptRoot 'AndroidAppIcon-preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $preview.Dispose() }
} finally { $sourceImage.Dispose() }
