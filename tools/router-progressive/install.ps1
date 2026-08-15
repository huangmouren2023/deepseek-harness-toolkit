param(
  [string]$Profile = 'web'
)

$ErrorActionPreference = 'Stop'
$source = Split-Path -Parent $MyInvocation.MyCommand.Path
$dsh = Get-Command dsh,dsh.cmd -ErrorAction SilentlyContinue | Select-Object -First 1

if ($null -eq $dsh) {
  throw '找不到 dsh 命令；请先让 DeepSeek Harness 的 CLI 出现在 PATH 中。'
}

& $dsh.Source plugin --profile $Profile add $source
if ($LASTEXITCODE -ne 0) {
  throw "安装 router-progressive 失败，退出码：$LASTEXITCODE"
}

Write-Host "已把 router-progressive 安装到 profile：$Profile" -ForegroundColor Green
Write-Host '下一步：把 preset-row.yml 中的 router-progressive 行加入目标 agent preset，然后重启 DSH。' -ForegroundColor Cyan

