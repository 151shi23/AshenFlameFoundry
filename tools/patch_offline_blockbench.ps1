<#
  MinewaysMobile · 离线版 Blockbench 内核补丁（幂等，可反复执行）
  ==========================================================================
  app/src/main/assets/blockbench/dist/bundle.js 是第三方压缩产物，本工程对它做了几处
  **源码级**改动。升级内置 Blockbench（整目录覆盖 dist/）后这些改动会一并丢失，
  重跑本脚本即可恢复：

  A. 商店列表填充条件去掉 navigator.onLine
     离线版页面 origin 是 appassets.androidplatform.net，WebView 常把 navigator.onLine
     报成 false，于是即便插件索引已取到，市场列表仍被丢弃、入口消失。

  B. 导出优先写入 Android 文件桥（BBAndroid），失败才回退浏览器下载
     网页版内核用 FileSaver：创建一个**从不插入 DOM** 的 <a download>，
     再对它 dispatchEvent("click")。游离节点的事件到不了 document，页面级钩子收不到；
     而内核调用 Blockbench.export() 后立刻把 Project.saved 置为 true，
     于是出现“提示导出成功、磁盘上却没有文件”。补丁让内核自己走文件桥，
     与此前注入的保存助手插件互为双保险。

  用法：
      powershell -ExecutionPolicy Bypass -File neteasemc\tools\patch_offline_blockbench.ps1
      powershell ... -Bundle <其它路径的 bundle.js>        # 校验/修补别的副本
#>
[CmdletBinding()]
param(
    [string]$Bundle,
    [string]$BackupDir
)

# 脚本目录（$PSScriptRoot 在部分调用方式下为空，用 $MyInvocation 兜底）
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $Bundle) { $Bundle = Join-Path $scriptDir '..\app\src\main\assets\blockbench\dist\bundle.js' }
if (-not $BackupDir) { $BackupDir = Join-Path $scriptDir '_backup' }

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Bundle)) { Write-Error "找不到 bundle：$Bundle"; exit 1 }
$path = (Resolve-Path $Bundle).Path
$utf8 = New-Object System.Text.UTF8Encoding($false)
$text = [System.IO.File]::ReadAllText($path)
Write-Host ("目标：{0}  ({1:N2} MB)" -f $path, ((Get-Item $path).Length / 1MB))

# ---------------------------------------------------------------- 补丁 B 的注入实现
# 说明：全部用 var / 普通函数，避免与压缩后的变量名冲突；不含 $ 与反引号，便于内联。
$helper = @'
function mmSaveToAndroid(g,f){try{var b=window.BBAndroid;if(!b||typeof b.beginSave!=="function")return!1;var n=f||"model";var send=function(bl){try{var id=b.beginSave(n,bl.type||"application/octet-stream");if(typeof id!=="string"||id.indexOf("ERR:")===0)return;bl.arrayBuffer().then(function(ab){var u=new Uint8Array(ab),CH=245760;for(var off=0;off<u.length;off+=CH){var part=u.subarray(off,Math.min(off+CH,u.length)),s="";for(var i=0;i<part.length;i+=8190){var q=part.subarray(i,Math.min(i+8190,part.length)),bin="";for(var j=0;j<q.length;j++)bin+=String.fromCharCode(q[j]);s+=btoa(bin)}if(String(b.appendSave(id,s)).indexOf("ERR:")===0){b.abortSave(id);return}}b.finishSave(id)},function(){})}catch(e){}};if(g&&typeof g==="object"&&typeof g.arrayBuffer==="function"){send(g);return!0}if(g instanceof ArrayBuffer){send(new Blob([g],{type:"octet/stream"}));return!0}if(typeof g==="string"){if(/^blob:/i.test(g)){fetch(g).then(function(x){return x.blob()}).then(send,function(){});return!0}if(/^data:[^,]*,/i.test(g)){var c=g.indexOf(","),meta=g.slice(0,c),body=g.slice(c+1),mt=(meta.match(/^data:([^;]*)/)||[])[1]||"application/octet-stream";if(/;base64/i.test(meta)){var bin2=atob(body),arr2=new Uint8Array(bin2.length);for(var i2=0;i2<bin2.length;i2++)arr2[i2]=bin2.charCodeAt(i2);send(new Blob([arr2],{type:mt}))}else{send(new Blob([decodeURIComponent(body)],{type:mt}))}return!0}send(new Blob([g],{type:"text/plain;charset=utf-8"}));return!0}if(g&&g.buffer instanceof ArrayBuffer){send(new Blob([g],{type:"octet/stream"}));return!0}if(g!=null){send(new Blob([g],{type:"octet/stream"}));return!0}}catch(e){}return!1}
try{window.MMSaveNative=mmSaveToAndroid;}catch(e){}
'@

$hookOpen = '_.pickDirectory=o;function r(g,f){'
$hookOpenNew = '_.pickDirectory=o;' + "`n" + $helper.Trim() + "`n" + 'function r(g,f){'
$anchorHelper = 'function a(s){try{s.dispatchEvent(new MouseEvent("click"))}'
$anchorHelperNew = 'function a(s){try{if(window.MMSaveNative&&s&&s.download&&/^(blob|data):/i.test(s.href||"")&&window.MMSaveNative(s.href,s.download))return;s.dispatchEvent(new MouseEvent("click"))}'

# B2~B4 用正则锚点：FileSaver 的压缩变量名每个版本都可能变（5.2.1 是 Ow，5.2.2 是 Lw），
# 只认 "(0,<变量>.default)(参数…)" 这个调用形状，升级后无需再手改脚本。
$edits = @(
    @{ Name = 'A  商店列表不再依赖 navigator.onLine';
       From = 'St.json instanceof Object&&navigator.onLine'; To = 'St.json instanceof Object';
       Forbid = 'St.json instanceof Object&&navigator.onLine' },
    @{ Name = 'B1 注入导出桥实现 + 暴露给下载层';      From = $hookOpen;     To = $hookOpenNew },
    @{ Name = 'B2 图片 / data:URL 分支走文件桥';
       Pattern = '\(0,[\w$]+\.default\)\(g\.content,v,\{\}\)';
       Replacement = 'mmSaveToAndroid(g.content,v)||$&'; CheckPattern = 'mmSaveToAndroid\(g\.content,v\)\|\|' },
    @{ Name = 'B3 zip / buffer / binary 分支走文件桥';
       Pattern = '\(0,[\w$]+\.default\)\((z|E),v\)';
       Replacement = 'mmSaveToAndroid($1,v)||$&'; CheckPattern = 'mmSaveToAndroid\((z|E),v\)\|\|' },
    @{ Name = 'B4 文本分支（json / bbmodel 等）';
       Pattern = '\(0,[\w$]+\.default\)\(y,v,\{autoBOM:!0\}\)';
       Replacement = 'mmSaveToAndroid(y,v)||$&'; CheckPattern = 'mmSaveToAndroid\(y,v\)\|\|' },
    @{ Name = 'B5 游离锚点下载兜底走文件桥';           From = $anchorHelper; To = $anchorHelperNew }
)

# 判断某条补丁是否已生效：Forbid 看「锚点已彻底消失」，正则补丁看 CheckPattern，其余看替换结果
function Test-Applied([string]$s, $e) {
    if ($e.ContainsKey('Forbid')) { return -not $s.Contains($e.Forbid) }
    if ($e.ContainsKey('CheckPattern')) { return ([regex]$e.CheckPattern).IsMatch($s) }
    return $s.Contains($e.To)
}

# ---------------------------------------------------------------- 应用
$pending = @()
foreach ($e in $edits) {
    if (Test-Applied $text $e) { Write-Host ("  [已生效] " + $e.Name) -ForegroundColor DarkGray; continue }
    $pending += $e
}

if ($pending.Count -eq 0) {
    Write-Host '内核已是最新补丁状态，无需改动。' -ForegroundColor Green
} else {
    # 首次改动前留一份原始副本（放在 tools/_backup 下，不在 assets 内，不会被打进 APK）
    if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null }
    $backup = Join-Path $BackupDir 'bundle.js.orig'
    if (-not (Test-Path $backup)) {
        Copy-Item $path $backup -Force
        Write-Host ("  原始副本已备份到 " + $backup) -ForegroundColor DarkGray
    }

    foreach ($e in $pending) {
        if ($e.ContainsKey('Pattern')) {
            $rx = [regex]$e.Pattern
            $count = $rx.Matches($text).Count
            if ($count -eq 0) {
                Write-Host ("  [跳过] " + $e.Name + "：未找到锚点（内核版本可能已变，请人工确认）") -ForegroundColor Yellow
                continue
            }
            if ($count -gt 1) {
                Write-Host ("  [注意] " + $e.Name + "：锚点出现 " + $count + " 次，将全部替换") -ForegroundColor Yellow
            }
            $text = $rx.Replace($text, $e.Replacement)
            Write-Host ("  [写入] " + $e.Name + "  (x" + $count + ")") -ForegroundColor Cyan
            continue
        }
        $count = ([regex]::Matches($text, [regex]::Escape($e.From))).Count
        if ($count -eq 0) {
            Write-Host ("  [跳过] " + $e.Name + "：未找到锚点（内核版本可能已变，请人工确认）") -ForegroundColor Yellow
            continue
        }
        if ($count -gt 1) {
            Write-Host ("  [注意] " + $e.Name + "：锚点出现 " + $count + " 次，将全部替换") -ForegroundColor Yellow
        }
        $text = $text.Replace($e.From, $e.To)
        Write-Host ("  [写入] " + $e.Name + "  (x" + $count + ")") -ForegroundColor Cyan
    }

    [System.IO.File]::WriteAllText($path, $text, $utf8)
    Write-Host ("已写回：" + $path)
}

# ---------------------------------------------------------------- 校验
$final = [System.IO.File]::ReadAllText($path)
$ok = $true
foreach ($e in $edits) {
    if (-not (Test-Applied $final $e)) { Write-Host ("  [缺失] " + $e.Name) -ForegroundColor Red; $ok = $false }
}
if ($ok) { Write-Host '补丁校验：全部到位。' -ForegroundColor Green }

if (Get-Command node -ErrorAction SilentlyContinue) {
    node --check $path
    if ($LASTEXITCODE -eq 0) { Write-Host '语法校验：node --check 通过。' -ForegroundColor Green }
    else { Write-Host '语法校验：失败！请用 tools/_backup/bundle.js.orig 还原。' -ForegroundColor Red; exit 2 }
} else {
    Write-Host '未找到 node，跳过语法校验（建议自行安装 node 或人工确认）。' -ForegroundColor Yellow
}
