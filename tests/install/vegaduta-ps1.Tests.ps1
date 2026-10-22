# Pester 5 tests for scripts/vegaduta.ps1, the Windows launcher.
#
#   pwsh -NoProfile -File tests/install/run-vegaduta-ps1-tests.ps1                 # PowerShell 7
#   powershell -NoProfile -File tests\install\run-vegaduta-ps1-tests.ps1           # Windows PowerShell 5.1
#
# The launcher is the only code of VegaDuta that never ran in CI before, and it ships to every Windows
# user (Setup runs it). These tests run it as a child process of the SAME PowerShell that runs the tests,
# so the `windows-launcher` job of .github/workflows/ci.yml proves Windows PowerShell 5.1 and PowerShell 7
# separately. Docker is a fake on PATH (tests/install never starts a container) that writes every call to
# a log, and every launcher run gets its own copy of the install folder, HOME and kept-secrets file, so
# nothing here touches a real install.
#
# This file is parsed by Windows PowerShell 5.1 too: no ternary, no `??`, no `&&` / `||`, no `?.`.

BeforeDiscovery {
  $OnWindows = [Environment]::OSVersion.Platform -eq 'Win32NT'
}

BeforeAll {
  $script:OnWindows = [Environment]::OSVersion.Platform -eq 'Win32NT'
  $script:Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
  $script:Launcher = Join-Path (Join-Path $script:Root 'scripts') 'vegaduta.ps1'
  $script:PsExe = (Get-Process -Id $PID).Path
  $script:Sandboxes = New-Object System.Collections.ArrayList

  function Format-Arg([string]$a) {
    if ($a -eq '' -or $a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
  }

  # A throw-away install folder: the launcher, the compose files, the model catalog, the Keycloak
  # reconcile script, a fake docker on PATH and a HOME of its own. The release is "stamped" 0.1.0.
  function New-Sandbox {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ('vgd-ps1-' + [guid]::NewGuid().ToString('N').Substring(0, 10))
    $sb = @{ Root = $root }
    $sb.Repo = Join-Path $root 'repo'
    $sb.Bin = Join-Path $root 'bin'
    $sb.Home = Join-Path $root 'home'
    $sb.Compose = Join-Path (Join-Path $sb.Repo 'deploy') 'compose'
    $sb.Script = Join-Path (Join-Path $sb.Repo 'scripts') 'vegaduta.ps1'
    $sb.EnvFile = Join-Path $sb.Compose '.env'
    $sb.State = Join-Path $sb.Compose '.vegaduta-state'
    $sb.Keep = Join-Path (Join-Path $root 'keep') 'secrets.env'
    $sb.Log = Join-Path $root 'docker.log'
    foreach ($d in @($sb.Repo, $sb.Bin, $sb.Home, $sb.Compose, (Split-Path $sb.Script), (Join-Path (Join-Path $sb.Repo 'deploy') 'models'), (Join-Path (Join-Path $sb.Repo 'deploy') 'keycloak'), (Join-Path $root 'localappdata'))) {
      New-Item -ItemType Directory -Force -Path $d | Out-Null
    }
    Copy-Item -LiteralPath $script:Launcher -Destination $sb.Script
    foreach ($f in @(Get-ChildItem -LiteralPath (Join-Path (Join-Path $script:Root 'deploy') 'compose') -File -Force)) {
      Copy-Item -LiteralPath $f.FullName -Destination $sb.Compose
    }
    # deploy/models/catalog.txt is a symlink to core's catalog (a text file holding the path on a Windows checkout
    # without symlinks): copy the real file.
    $catalog = [System.IO.Path]::Combine($script:Root, 'core', 'providers', 'src', 'main', 'resources', 'local-models', 'catalog.txt')
    Copy-Item -LiteralPath $catalog -Destination (Join-Path (Join-Path (Join-Path $sb.Repo 'deploy') 'models') 'catalog.txt')
    Copy-Item -LiteralPath (Join-Path (Join-Path (Join-Path $script:Root 'deploy') 'keycloak') 'reconcile-realm.kcadm.sh') -Destination (Join-Path (Join-Path $sb.Repo 'deploy') 'keycloak')
    $example = Join-Path $sb.Compose '.env.example'
    $text = [System.IO.File]::ReadAllText($example) -replace '(?m)^VEGADUTA_VERSION=.*$', 'VEGADUTA_VERSION=0.1.0'
    [System.IO.File]::WriteAllText($example, $text)
    New-FakeDocker $sb
    [void]$script:Sandboxes.Add($root)
    return $sb
  }

  # docker on PATH: a shim that runs fake-docker.ps1 with the PowerShell running these tests. The fake
  # logs each call and behaves per FAKE_DOCKER_* variables; `volume inspect` and a stopped daemon write to
  # STDERR and exit 1, like the real docker (that is what trips Windows PowerShell 5.1).
  function New-FakeDocker($sb) {
    $fake = @'
$a = @($args)
if ($env:FAKE_DOCKER_LOG) { Add-Content -LiteralPath $env:FAKE_DOCKER_LOG -Value ('docker ' + ($a -join ' ')) }
function Fail([string]$m, [int]$code) { [Console]::Error.WriteLine($m); exit $code }
$verbs = 'version', 'config', 'ps', 'exec', 'pull', 'up', 'logs', 'down', 'build', 'cp', 'stop', 'restart', 'run'
switch ($a[0]) {
  'version' {
    if ($env:FAKE_DOCKER_DOWN -eq '1') { Fail 'Cannot connect to the Docker daemon. Is the docker daemon running?' 1 }
    '27.0.0'; exit 0
  }
  'volume' {
    $n = $a[2]
    if ($env:FAKE_DOCKER_OLD_DATA -eq '1' -and $n -eq 'vegaduta-personal_postgres-data') { $n; exit 0 }
    Fail "Error response from daemon: get ${n}: no such volume" 1
  }
  'info' { '17179869184'; exit 0 }
  'ps' { if ($env:FAKE_DOCKER_PS) { $env:FAKE_DOCKER_PS }; exit 0 }
  'port' { '3000/tcp -> 0.0.0.0:3000'; exit 0 }
  'stop' { exit 0 }
  'compose' {
    $verb = ''
    foreach ($x in $a) { if ($verbs -contains $x) { $verb = $x; break } }
    switch ($verb) {
      'version' { '2.29.0'; exit 0 }
      'exec' {
        if ($a -contains 'ollama' -and $a -contains 'pull') {
          $code = 0; if ($env:FAKE_DOCKER_PULL_EXIT) { $code = [int]$env:FAKE_DOCKER_PULL_EXIT }
          if ($code -ne 0) { [Console]::Error.WriteLine('pull model manifest: file does not exist') }
          exit $code
        }
        exit 0
      }
      default { exit 0 }
    }
  }
}
exit 0
'@
    [System.IO.File]::WriteAllText((Join-Path $sb.Bin 'fake-docker.ps1'), $fake)
    if ($script:OnWindows) {
      $cmd = "@echo off`r`n`"%FAKE_DOCKER_PS_EXE%`" -NoProfile -ExecutionPolicy Bypass -File `"%~dp0fake-docker.ps1`" %*`r`nexit /b %ERRORLEVEL%`r`n"
      [System.IO.File]::WriteAllText((Join-Path $sb.Bin 'docker.cmd'), $cmd)
    } else {
      $sh = "#!/bin/sh`nexec `"`$FAKE_DOCKER_PS_EXE`" -NoProfile -File `"`$(dirname `"`$0`")/fake-docker.ps1`" `"`$@`"`n"
      $p = Join-Path $sb.Bin 'docker'
      [System.IO.File]::WriteAllText($p, $sh)
      & chmod +x $p
    }
  }

  function New-FakeNvidiaSmi($sb) {
    if ($script:OnWindows) {
      [System.IO.File]::WriteAllText((Join-Path $sb.Bin 'nvidia-smi.cmd'), "@echo off`r`necho NVIDIA GeForce RTX 4090, 24564`r`n")
    } else {
      $p = Join-Path $sb.Bin 'nvidia-smi'
      [System.IO.File]::WriteAllText($p, "#!/bin/sh`necho 'NVIDIA GeForce RTX 4090, 24564'`n")
      & chmod +x $p
    }
  }

  # Runs the sandbox's launcher as a child of this PowerShell. Returns ExitCode and Text (stdout + stderr).
  function Invoke-Launcher($sb, [string[]]$Arguments, [hashtable]$Env = @{}) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:PsExe
    $argList = @('-NoProfile')
    if ($script:OnWindows) { $argList += @('-ExecutionPolicy', 'Bypass') }
    $argList += @('-File', $sb.Script)
    $argList += $Arguments
    $psi.Arguments = (@($argList | ForEach-Object { Format-Arg $_ })) -join ' '
    $psi.WorkingDirectory = $sb.Repo
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardInput = $true
    $vars = @{
      PATH = $sb.Bin + [System.IO.Path]::PathSeparator + $env:PATH
      HOME = $sb.Home; USERPROFILE = $sb.Home; LOCALAPPDATA = (Join-Path $sb.Root 'localappdata')
      VEGADUTA_SECRETS_KEEP = $sb.Keep; VEGADUTA_ASSUME_NO = '1'
      FAKE_DOCKER_LOG = $sb.Log; FAKE_DOCKER_PS_EXE = $script:PsExe
    }
    foreach ($k in $Env.Keys) { $vars[$k] = $Env[$k] }
    foreach ($k in @('HOST_RAM_GB', 'HOST_ACCELERATOR', 'VEGADUTA_SEARCH', 'VEGADUTA_REPO', 'VEGADUTA_ENV_FILE', 'VEGADUTA_WAIT_TIMEOUT', 'COMPOSE_PROFILES')) {
      if (-not $vars.ContainsKey($k)) { [void]$psi.EnvironmentVariables.Remove($k) }
    }
    foreach ($k in $vars.Keys) { $psi.EnvironmentVariables[$k] = [string]$vars[$k] }
    $p = [System.Diagnostics.Process]::Start($psi)
    $p.StandardInput.Close()
    $so = $p.StandardOutput.ReadToEndAsync()
    $se = $p.StandardError.ReadToEndAsync()
    if (-not $p.WaitForExit(240000)) { try { $p.Kill() } catch { } }
    $p.WaitForExit()
    return [pscustomobject]@{ ExitCode = $p.ExitCode; Text = ($so.Result + "`n" + $se.Result) }
  }

  function Read-DotEnv([string]$Path) {
    $h = @{}
    foreach ($l in [System.IO.File]::ReadAllLines($Path)) {
      $i = $l.IndexOf('=')
      if ($i -gt 0 -and -not $l.StartsWith('#')) { $h[$l.Substring(0, $i)] = $l.Substring($i + 1) }
    }
    return $h
  }
  function Get-DockerLog($sb) {
    if (Test-Path -LiteralPath $sb.Log) { return @([System.IO.File]::ReadAllLines($sb.Log)) }
    return @()
  }
  function Set-EnvLine($sb, [string]$Key, [string]$Value) {
    $lines = @([System.IO.File]::ReadAllLines($sb.EnvFile) | ForEach-Object { if ($_.StartsWith("$Key=")) { "$Key=$Value" } else { $_ } })
    [System.IO.File]::WriteAllText($sb.EnvFile, (($lines -join "`n") + "`n"))
  }

  # The text of a function of the launcher, to define it in a test (the script itself cannot be dot-sourced:
  # it runs a command on load).
  function Get-LauncherFunctionText([string[]]$Name) {
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($script:Launcher, [ref]$null, [ref]$null)
    foreach ($n in $Name) {
      $nn = $n
      $f = $ast.Find({ param($x) ($x -is [System.Management.Automation.Language.FunctionDefinitionAst]) -and $x.Name -eq $nn }, $true)
      if (-not $f) { throw "function $n not found in the launcher" }
      $f.Extent.Text
    }
  }

  function Start-FakeOllama([int]$Status, [string]$Body) {
    $l = New-Object System.Net.HttpListener
    $l.Prefixes.Add('http://127.0.0.1:11434/')
    $l.Start()
    $state = [hashtable]::Synchronized(@{ Requests = (New-Object System.Collections.ArrayList) })
    $ps = [powershell]::Create()
    [void]$ps.AddScript({
        param($l, $state, $status, $body)
        while ($l.IsListening) {
          try { $ctx = $l.GetContext() } catch { break }
          $sr = New-Object System.IO.StreamReader($ctx.Request.InputStream)
          [void]$state.Requests.Add(($ctx.Request.HttpMethod + ' ' + $ctx.Request.RawUrl + ' ' + $sr.ReadToEnd()))
          $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
          $ctx.Response.StatusCode = $status
          $ctx.Response.ContentType = 'application/json'
          $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
          $ctx.Response.Close()
        }
      }).AddArgument($l).AddArgument($state).AddArgument($Status).AddArgument($Body)
    $h = $ps.BeginInvoke()
    return [pscustomobject]@{ Listener = $l; PS = $ps; State = $state; Handle = $h }
  }
  function Stop-FakeOllama($f) {
    try { $f.Listener.Stop(); $f.Listener.Close() } catch { }
    try { $f.PS.Stop() } catch { }
    try { $f.PS.Dispose() } catch { }
  }
}

AfterAll {
  foreach ($d in $script:Sandboxes) { Remove-Item -LiteralPath $d -Recurse -Force -ErrorAction SilentlyContinue }
}

Describe 'the script as text' {
  BeforeAll {
    $script:Tokens = $null; $script:ParseErrors = $null
    $script:Ast = [System.Management.Automation.Language.Parser]::ParseFile($script:Launcher, [ref]$script:Tokens, [ref]$script:ParseErrors)
  }

  It 'parses without errors under this PowerShell' {
    @($script:ParseErrors | ForEach-Object { "$($_.Extent.StartLineNumber): $($_.Message)" }) | Should -BeNullOrEmpty
  }

  It 'uses no syntax that Windows PowerShell 5.1 cannot parse (ternary, ??, ?., && and ||)' {
    $bad = @()
    $bad += @($script:Ast.FindAll({ param($n) $n.GetType().Name -in 'TernaryExpressionAst', 'PipelineChainAst' }, $true) | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.GetType().Name)" })
    $bad += @($script:Tokens | Where-Object { "$($_.Kind)" -in 'AndAnd', 'OrOr', 'QuestionQuestion', 'QuestionQuestionEquals', 'QuestionDot', 'QuestionLBracket' } | ForEach-Object { "line $($_.Extent.StartLineNumber): $($_.Kind)" })
    $bad += @($script:Ast.FindAll({ param($n) $n.PSObject.Properties['NullConditional'] -and $n.NullConditional }, $true) | ForEach-Object { "line $($_.Extent.StartLineNumber): null-conditional access" })
    $bad += @($script:Ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.ScriptBlockAst] -and $n.PSObject.Properties['CleanBlock'] -and $n.CleanBlock }, $true) | ForEach-Object { "line $($_.Extent.StartLineNumber): clean block" })
    $bad | Should -BeNullOrEmpty
  }

  It 'uses no cmdlet parameter that exists only in PowerShell 7' {
    $only7 = 'AsByteStream', 'AsArray', 'SkipHttpErrorCheck', 'SkipCertificateCheck', 'Parallel', 'ThrottleLimit', 'AdditionalChildPath', 'AsHashtable', 'NoProxy', 'Authentication'
    $bad = @($script:Ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.CommandParameterAst] -and $n.ParameterName -in $only7 }, $true) | ForEach-Object { "line $($_.Extent.StartLineNumber): -$($_.ParameterName)" })
    $bad += @($script:Ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Join-Path' -and @($n.CommandElements | Where-Object { $_ -is [System.Management.Automation.Language.CommandParameterAst] }).Count -eq 0 -and $n.CommandElements.Count -gt 3 }, $true) | ForEach-Object { "line $($_.Extent.StartLineNumber): Join-Path with more than two positional arguments" })
    $bad | Should -BeNullOrEmpty
  }

  It 'is ASCII (or has a byte order mark), because 5.1 reads a BOM-less file as the ANSI code page' {
    $bytes = [System.IO.File]::ReadAllBytes($script:Launcher)
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    $nonAscii = @($bytes | Where-Object { $_ -gt 127 }).Count
    ($nonAscii -eq 0 -or $hasBom) | Should -BeTrue
  }

  It 'has CRLF line endings throughout' {
    $text = [System.IO.File]::ReadAllText($script:Launcher)
    ([regex]::Matches($text, "(?<!`r)`n")).Count | Should -Be 0
  }

  It 'redirects native stderr only inside Invoke-Native (Get-DcOutput merges it on purpose)' {
    $bad = @()
    $nodes = $script:Ast.FindAll({ param($n) ($n -is [System.Management.Automation.Language.FileRedirectionAst] -or $n -is [System.Management.Automation.Language.MergingRedirectionAst]) -and "$($n.FromStream)" -in 'Error', 'All' }, $true)
    foreach ($n in $nodes) {
      $p = $n.Parent
      while ($p -and -not ($p -is [System.Management.Automation.Language.FunctionDefinitionAst])) { $p = $p.Parent }
      $owner = ''
      if ($p) { $owner = $p.Name }
      if ($owner -notin 'Invoke-Native', 'Get-DcOutput') { $bad += "line $($n.Extent.StartLineNumber): $($n.Extent.Text) outside Invoke-Native" }
    }
    $bad | Should -BeNullOrEmpty
  }
}

Describe 'the generated secrets' {
  It 'are the same table as scripts/vegaduta (the Setup app and WSL share one .env)' {
    $ps = [System.IO.File]::ReadAllText($script:Launcher) -replace "`r", ''
    $block = [regex]::Match($ps, '(?ms)^\$SecretSpecs = .*?^\}').Value
    $psPairs = @([regex]::Matches($block, "([A-Z0-9_]+)='([a-z0-9]+)'") | ForEach-Object { $_.Groups[1].Value + ':' + $_.Groups[2].Value } | Sort-Object)
    $sh = [System.IO.File]::ReadAllText((Join-Path (Join-Path $script:Root 'scripts') 'vegaduta')) -replace "`r", ''
    $shBlock = [regex]::Match($sh, '(?ms)^SECRET_SPECS="(.*?)^"').Groups[1].Value
    $shPairs = @([regex]::Matches($shBlock, '(?m)^([A-Z0-9_]+:[a-z0-9]+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object)
    $psPairs.Count | Should -BeGreaterThan 30
    ($psPairs -join "`n") | Should -Be ($shPairs -join "`n")
  }

  It 'are all generated, each in its own format, by a real run of `secrets`' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('secrets')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $e = Read-DotEnv $sb.EnvFile
    $ps = [System.IO.File]::ReadAllText($script:Launcher) -replace "`r", ''
    $block = [regex]::Match($ps, '(?ms)^\$SecretSpecs = .*?^\}').Value
    $format = @{ hex16 = '^[0-9a-f]{32}$'; hex32 = '^[0-9a-f]{64}$'; b64 = '^[A-Za-z0-9+/]{43}=$'; fernet = '^[A-Za-z0-9_-]{43}=$'; lfpk = '^pk-lf-[0-9a-f]{32}$'; lfsk = '^sk-lf-[0-9a-f]{32}$' }
    $bad = @()
    foreach ($m in [regex]::Matches($block, "([A-Z0-9_]+)='([a-z0-9]+)'")) {
      $name = $m.Groups[1].Value; $kind = $m.Groups[2].Value
      if (-not $format.ContainsKey($kind)) { $bad += "$name has the unknown kind $kind"; continue }
      if (-not $e.ContainsKey($name) -or $e[$name] -notmatch $format[$kind]) { $bad += "$name ($kind) is '$($e[$name])'" }
    }
    $bad | Should -BeNullOrEmpty
    [Convert]::FromBase64String($e['APP_ENCRYPTION_KEY']).Length | Should -Be 32
    Test-Path -LiteralPath $sb.Keep | Should -BeTrue
  }
}

Describe 'native commands under $ErrorActionPreference = Stop' {
  BeforeAll {
    foreach ($t in (Get-LauncherFunctionText 'Invoke-Native')) { Invoke-Expression $t }
  }

  It 'Invoke-Native returns stdout and the exit code, and does not throw on stderr' {
    $ErrorActionPreference = 'Stop'
    $out = @(Invoke-Native $script:PsExe @('-NoProfile', '-Command', "[Console]::Out.WriteLine('hi'); [Console]::Error.WriteLine('boom'); exit 3"))
    $script:NativeExit | Should -Be 3
    $out | Should -Contain 'hi'
    "$out" | Should -Not -Match 'boom'
  }

  It 'Invoke-Native reports a program that does not exist as a failure, without throwing' {
    $ErrorActionPreference = 'Stop'
    $out = @(Invoke-Native 'vegaduta-no-such-program' @('x'))
    $script:NativeExit | Should -Not -Be 0
    $out.Count | Should -Be 0
  }

  It 'and leaves $ErrorActionPreference as it found it' {
    $ErrorActionPreference = 'Stop'
    [void](Invoke-Native $script:PsExe @('-NoProfile', '-Command', 'exit 0'))
    $ErrorActionPreference | Should -Be 'Stop'
  }

  # install-ps1-eap-stop-stderr-07: on Windows PowerShell 5.1 the first stderr line of a redirected native
  # command became a terminating NativeCommandError under 'Stop' (docker volume inspect on a machine with no
  # earlier install, docker version with the daemon down, ...).
  It '`secrets` on a machine with no earlier install finishes although `docker volume inspect` writes to stderr' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('secrets')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $r.Text | Should -Not -Match 'no such volume'
    $r.Text | Should -Not -Match 'NativeCommandError'
    Test-Path -LiteralPath $sb.EnvFile | Should -BeTrue
  }

  It '`doctor` says the Docker daemon is not running instead of failing with a PowerShell error' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('doctor', '-Lite') @{ FAKE_DOCKER_DOWN = '1' }
    $r.ExitCode | Should -Not -Be 0
    $r.Text | Should -Match 'Docker daemon not running'
    $r.Text | Should -Not -Match 'NativeCommandError'
  }
}

Describe 'an earlier install left its data in Docker' {
  It '`secrets` gives the new install its own project and stops no container' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('secrets') @{ FAKE_DOCKER_OLD_DATA = '1'; FAKE_DOCKER_PS = 'abc123' }
    $r.ExitCode | Should -Be 0 -Because $r.Text
    (Read-DotEnv $sb.EnvFile)['VEGADUTA_PERSONAL_PROJECT'] | Should -Be 'vegaduta-personal-2'
    @(Get-DockerLog $sb | Where-Object { $_ -match '^docker (stop|rm|kill|restart|down)\b' }) | Should -BeNullOrEmpty
  }
}

Describe 'personal -DryRun' {
  It 'prints the plan and starts nothing' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $r.Text | Should -Match 'plan: tier=personal'
    $r.Text | Should -Match 'plan: docker compose .*personal\.yml.* up -d'
    $r.Text | Should -Not -Match 'docker-compose\.search\.yml'
    @(Get-DockerLog $sb | Where-Object { $_ -match '^docker (stop|rm|kill|restart|down|pull)\b' -or $_ -match '^docker compose .* (up|pull|down|exec|build)\b' }) | Should -BeNullOrEmpty
    Test-Path -LiteralPath $sb.State | Should -BeTrue
    $state = [System.IO.File]::ReadAllText($sb.State)
    $state | Should -Match 'tier=personal'
    $state | Should -Match 'cloud=1'
  }

  It 'plans the free local model that fits the machine when nothing else is asked' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('personal', '-DryRun')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $r.Text | Should -Match 'local_ai=container'
    $r.Text | Should -Match 'local_model=\S+'
    $r.Text | Should -Match 'docker-compose\.local-ai\.yml'
  }

  # install-ps1-no-search-11
  It 'layers web search with -Search, remembers it, and drops it with -NoSearch' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud', '-Search')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $r.Text | Should -Match 'search=1'
    $r.Text | Should -Match 'docker-compose\.search\.yml'
    $r.Text | Should -Match 'docker-compose\.search\.lite\.yml'
    $r.Text | Should -Match '--profile search'
    [System.IO.File]::ReadAllText($sb.State) | Should -Match '(?m)^search=1\r?$'
    $again = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud')
    $again.Text | Should -Match 'search=1' -Because 'a plain re-run keeps the remembered choice'
    $off = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud', '-NoSearch')
    $off.Text | Should -Match 'search=0'
    $off.Text | Should -Not -Match 'docker-compose\.search\.yml'
    [System.IO.File]::ReadAllText($sb.State) | Should -Match '(?m)^search=0\r?$'
  }

  It 'takes web search from VEGADUTA_SEARCH=1 like the bash launcher' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud') @{ VEGADUTA_SEARCH = '1' }
    $r.Text | Should -Match 'search=1'
    $r.Text | Should -Match 'docker-compose\.search\.yml'
  }

  It 'does not turn web search on by itself' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud')
    $r.Text | Should -Match 'search=0'
    $r.Text | Should -Not -Match 'docker-compose\.search\.yml'
  }

  It 'records the tools and browser add-ons in the state file under the keys the Setup app reads' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud', '-Profile', 'tools,browser')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $state = [System.IO.File]::ReadAllText($sb.State)
    $state | Should -Match '(?m)^tools=1\r?$'
    $state | Should -Match '(?m)^browser=1\r?$'
    $state | Should -Match '(?m)^profiles=.*tools'
  }

  # install-ps1-host-ram-04
  It 'writes HOST_RAM_GB and HOST_ACCELERATOR to .env like the bash launcher (an exported value wins)' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud') @{ HOST_RAM_GB = '32'; HOST_ACCELERATOR = 'apple' }
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $e = Read-DotEnv $sb.EnvFile
    $e['HOST_RAM_GB'] | Should -Be '32'
    $e['HOST_ACCELERATOR'] | Should -Be 'apple'
  }

  It 'measures the computer, not the Docker VM, when nothing is exported' -Skip:(-not $OnWindows) {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $e = Read-DotEnv $sb.EnvFile
    [int]$e['HOST_RAM_GB'] | Should -BeGreaterThan 0
    [int]$e['HOST_RAM_GB'] | Should -Not -Be 16 -Because 'the fake Docker reports 16 GB; the value must come from the computer'
  }

  It 'reports an NVIDIA GPU as nvidia:NAME:GB' {
    $sb = New-Sandbox
    New-FakeNvidiaSmi $sb
    $r = Invoke-Launcher $sb @('personal', '-DryRun', '-Cloud') @{ HOST_RAM_GB = '32' }
    $r.ExitCode | Should -Be 0 -Because $r.Text
    (Read-DotEnv $sb.EnvFile)['HOST_ACCELERATOR'] | Should -Be 'nvidia:NVIDIA GeForce RTX 4090:24'
  }
}

Describe 'upgrade' {
  # install-ps1-upgrade-no-tree-03: `upgrade -Version X` only re-pinned the images.
  It 'does not re-pin or restart anything when the release of the target cannot be downloaded' {
    $sb = New-Sandbox
    (Invoke-Launcher $sb @('secrets')).ExitCode | Should -Be 0
    (Read-DotEnv $sb.EnvFile)['VEGADUTA_VERSION'] | Should -Be '0.1.0'
    Remove-Item -LiteralPath $sb.Log -ErrorAction SilentlyContinue
    $r = Invoke-Launcher $sb @('upgrade', '-Version', '9.9.9', '-NoBackup') @{ VEGADUTA_REPO = 'example.invalid/none' }
    $r.ExitCode | Should -Not -Be 0 -Because $r.Text
    $r.Text | Should -Match 'download failed'
    (Read-DotEnv $sb.EnvFile)['VEGADUTA_VERSION'] | Should -Be '0.1.0'
    @(Get-DockerLog $sb | Where-Object { $_ -match ' (pull|up|build)\b' }) | Should -BeNullOrEmpty
  }

  It 'refuses a target that is not a release version' {
    $sb = New-Sandbox
    (Invoke-Launcher $sb @('secrets')).ExitCode | Should -Be 0
    $r = Invoke-Launcher $sb @('upgrade', '-Version', '1.0/../x', '-NoBackup')
    $r.ExitCode | Should -Not -Be 0
    (Read-DotEnv $sb.EnvFile)['VEGADUTA_VERSION'] | Should -Be '0.1.0'
  }

  It 'in a git checkout warns that the compose files are of another release, then re-pins and restarts' {
    $sb = New-Sandbox
    New-Item -ItemType Directory -Force -Path (Join-Path $sb.Repo '.git') | Out-Null
    (Invoke-Launcher $sb @('secrets')).ExitCode | Should -Be 0
    Remove-Item -LiteralPath $sb.Log -ErrorAction SilentlyContinue
    $r = Invoke-Launcher $sb @('upgrade', '-Version', '9.9.9', '-NoBackup')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    $r.Text | Should -Match 'git checkout'
    (Read-DotEnv $sb.EnvFile)['VEGADUTA_VERSION'] | Should -Be '9.9.9'
    @(Get-DockerLog $sb | Where-Object { $_ -match 'pull --ignore-buildable' }).Count | Should -Be 1
    @(Get-DockerLog $sb | Where-Object { $_ -match ' up -d --remove-orphans --wait' }).Count | Should -Be 1
  }

  Context 'Install-Release (the download, with the network stubbed)' {
    BeforeAll {
      foreach ($t in (Get-LauncherFunctionText 'Say', 'Ok', 'Warn', 'Install-Release')) { Invoke-Expression $t }
      $script:Warnings = 0
      function New-ReleaseFixture([string]$Version, [string]$Marker) {
        $fx = Join-Path ([System.IO.Path]::GetTempPath()) ('vgd-rel-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
        $top = Join-Path $fx "vegaduta-$Version"
        New-Item -ItemType Directory -Force -Path (Join-Path (Join-Path $top 'scripts') '.') | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path (Join-Path $top 'deploy') 'compose') | Out-Null
        [System.IO.File]::WriteAllText((Join-Path (Join-Path $top 'scripts') 'vegaduta.ps1'), $Marker)
        [System.IO.File]::WriteAllText((Join-Path (Join-Path (Join-Path $top 'deploy') 'compose') 'personal.yml'), "services: {} # $Marker`n")
        Push-Location $fx
        try { & tar -czf "vegaduta-$Version.tar.gz" "vegaduta-$Version" } finally { Pop-Location }
        [void]$script:Sandboxes.Add($fx)
        $hash = (Get-FileHash -LiteralPath (Join-Path $fx "vegaduta-$Version.tar.gz") -Algorithm SHA256).Hash.ToLower()
        [System.IO.File]::WriteAllText((Join-Path $fx 'SHA256SUMS'), "$hash  vegaduta-$Version.tar.gz`n")
        return $fx
      }
      function New-InstallFolder {
        $d = Join-Path ([System.IO.Path]::GetTempPath()) ('vgd-inst-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
        New-Item -ItemType Directory -Force -Path (Join-Path $d 'scripts') | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path (Join-Path $d 'deploy') 'compose') | Out-Null
        [System.IO.File]::WriteAllText((Join-Path (Join-Path $d 'scripts') 'vegaduta.ps1'), 'OLD')
        [System.IO.File]::WriteAllText((Join-Path (Join-Path (Join-Path $d 'deploy') 'compose') '.env'), "POSTGRES_PASSWORD=keep-me`n")
        [void]$script:Sandboxes.Add($d)
        return $d
      }
    }

    It 'verifies SHA256SUMS, unpacks over the install folder (one leading folder stripped) and keeps .env' {
      $fixture = New-ReleaseFixture '9.9.9' 'NEW'
      $RepoRoot = New-InstallFolder
      function Invoke-WebRequest { param([switch]$UseBasicParsing, [string]$Uri, [string]$OutFile)
        $src = Join-Path $fixture (Split-Path -Leaf $Uri)
        if (-not (Test-Path -LiteralPath $src)) { throw "404 $Uri" }
        Copy-Item -LiteralPath $src -Destination $OutFile
      }
      Install-Release '9.9.9'
      [System.IO.File]::ReadAllText((Join-Path (Join-Path $RepoRoot 'scripts') 'vegaduta.ps1')) | Should -Be 'NEW'
      [System.IO.File]::ReadAllText((Join-Path (Join-Path (Join-Path $RepoRoot 'deploy') 'compose') 'personal.yml')) | Should -Match 'NEW'
      [System.IO.File]::ReadAllText((Join-Path (Join-Path (Join-Path $RepoRoot 'deploy') 'compose') '.env')) | Should -Match 'keep-me'
    }

    It 'refuses an archive whose checksum does not match, and unpacks nothing' {
      $fixture = New-ReleaseFixture '9.9.9' 'NEW'
      $bytes = [System.IO.File]::ReadAllBytes((Join-Path $fixture 'vegaduta-9.9.9.tar.gz'))
      $bytes[$bytes.Length - 1] = $bytes[$bytes.Length - 1] -bxor 0xFF
      [System.IO.File]::WriteAllBytes((Join-Path $fixture 'vegaduta-9.9.9.tar.gz'), $bytes)
      $RepoRoot = New-InstallFolder
      function Invoke-WebRequest { param([switch]$UseBasicParsing, [string]$Uri, [string]$OutFile)
        $src = Join-Path $fixture (Split-Path -Leaf $Uri)
        if (-not (Test-Path -LiteralPath $src)) { throw "404 $Uri" }
        Copy-Item -LiteralPath $src -Destination $OutFile
      }
      { Install-Release '9.9.9' } | Should -Throw '*checksum mismatch*'
      [System.IO.File]::ReadAllText((Join-Path (Join-Path $RepoRoot 'scripts') 'vegaduta.ps1')) | Should -Be 'OLD'
    }

    It 'refuses a release whose SHA256SUMS does not list the archive' {
      $fixture = New-ReleaseFixture '9.9.9' 'NEW'
      [System.IO.File]::WriteAllText((Join-Path $fixture 'SHA256SUMS'), ('0' * 64) + "  something-else.tar.gz`n")
      $RepoRoot = New-InstallFolder
      function Invoke-WebRequest { param([switch]$UseBasicParsing, [string]$Uri, [string]$OutFile)
        $src = Join-Path $fixture (Split-Path -Leaf $Uri)
        if (-not (Test-Path -LiteralPath $src)) { throw "404 $Uri" }
        Copy-Item -LiteralPath $src -Destination $OutFile
      }
      { Install-Release '9.9.9' } | Should -Throw '*no entry*'
      [System.IO.File]::ReadAllText((Join-Path (Join-Path $RepoRoot 'scripts') 'vegaduta.ps1')) | Should -Be 'OLD'
    }

    It 'reports a missing release as a download failure' {
      $fixture = New-ReleaseFixture '1.0.0' 'NEW'
      $RepoRoot = New-InstallFolder
      function Invoke-WebRequest { param([switch]$UseBasicParsing, [string]$Uri, [string]$OutFile)
        $src = Join-Path $fixture (Split-Path -Leaf $Uri)
        if (-not (Test-Path -LiteralPath $src)) { throw "404 $Uri" }
        Copy-Item -LiteralPath $src -Destination $OutFile
      }
      { Install-Release '9.9.9' } | Should -Throw '*download failed*'
    }
  }
}

Describe 'commands' {
  It 'prints the help and exits 0 for `help` and for no command' {
    $sb = New-Sandbox
    (Invoke-Launcher $sb @('help')).ExitCode | Should -Be 0
    (Invoke-Launcher $sb @()).ExitCode | Should -Be 0
  }

  It 'exits 2 for a command it does not have, so a caller (Setup) sees the failure' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('frobnicate')
    $r.ExitCode | Should -Be 2
    $r.Text | Should -Match 'unknown command'
  }
}

Describe 'models pull' {
  # install-setup-win-models-pull-01: Setup runs `vegaduta.ps1 models pull TAG`; the launcher printed its help and exited 0.
  It 'pulls through the Ollama container when the install uses it' {
    $sb = New-Sandbox
    [System.IO.File]::WriteAllText($sb.State, "tier=personal`nprofiles=`nbuild=0`nlocal_ai=container`nlocal_model=qwen3.5:4b`ncloud=0`n")
    $r = Invoke-Launcher $sb @('models', 'pull', 'qwen3.5:4b')
    $r.ExitCode | Should -Be 0 -Because $r.Text
    @(Get-DockerLog $sb | Where-Object { $_ -match 'compose .*exec -T ollama ollama pull qwen3\.5:4b$' }).Count | Should -Be 1
    $r.Text | Should -Match 'qwen3\.5:4b'
  }

  It 'exits non-zero when the pull fails' {
    $sb = New-Sandbox
    [System.IO.File]::WriteAllText($sb.State, "tier=personal`nprofiles=`nbuild=0`nlocal_ai=container`nlocal_model=qwen3.5:4b`ncloud=0`n")
    $r = Invoke-Launcher $sb @('models', 'pull', 'no-such-model:1b') @{ FAKE_DOCKER_PULL_EXIT = '1' }
    $r.ExitCode | Should -Not -Be 0
    $r.Text | Should -Match 'could not download'
  }

  It 'exits non-zero when there is no local Ollama to pull into' {
    $sb = New-Sandbox
    $r = Invoke-Launcher $sb @('models', 'pull', 'qwen3.5:4b')
    $r.ExitCode | Should -Not -Be 0
    $r.Text | Should -Match 'no local Ollama'
    @(Get-DockerLog $sb | Where-Object { $_ -match 'ollama pull' }) | Should -BeNullOrEmpty
  }

  It 'needs a tag, takes only a tag, and knows only `pull`' {
    $sb = New-Sandbox
    [System.IO.File]::WriteAllText($sb.State, "tier=personal`nprofiles=`nbuild=0`nlocal_ai=container`nlocal_model=qwen3.5:4b`ncloud=0`n")
    (Invoke-Launcher $sb @('models', 'pull')).ExitCode | Should -Not -Be 0
    (Invoke-Launcher $sb @('models', 'pull', 'x; rm -rf')).ExitCode | Should -Not -Be 0
    (Invoke-Launcher $sb @('models', 'frobnicate')).ExitCode | Should -Not -Be 0
    (Invoke-Launcher $sb @('models')).ExitCode | Should -Not -Be 0
    @(Get-DockerLog $sb | Where-Object { $_ -match 'ollama' }) | Should -BeNullOrEmpty
  }

  Context 'into the Ollama on this computer' {
    It 'posts to /api/pull and exits 0 on success' {
      $sb = New-Sandbox
      [System.IO.File]::WriteAllText($sb.State, "tier=personal`nprofiles=`nbuild=0`nlocal_ai=host`nlocal_model=qwen3.5:4b`ncloud=0`n")
      $srv = $null
      try { $srv = Start-FakeOllama 200 '{"status":"success"}' } catch { }
      if (-not $srv) { Set-ItResult -Skipped -Because 'port 11434 is not free'; return }
      try {
        $r = Invoke-Launcher $sb @('models', 'pull', 'qwen3.5:4b')
        $r.ExitCode | Should -Be 0 -Because $r.Text
        $reqs = @($srv.State.Requests)
        $reqs.Count | Should -Be 1
        $reqs[0] | Should -Match '^POST /api/pull '
        $reqs[0] | Should -Match '"model"\s*:\s*"qwen3\.5:4b"'
      } finally { Stop-FakeOllama $srv }
    }

    It 'exits non-zero when Ollama refuses the model' {
      $sb = New-Sandbox
      [System.IO.File]::WriteAllText($sb.State, "tier=personal`nprofiles=`nbuild=0`nlocal_ai=host`nlocal_model=qwen3.5:4b`ncloud=0`n")
      $srv = $null
      try { $srv = Start-FakeOllama 500 '{"error":"pull model manifest: file does not exist"}' } catch { }
      if (-not $srv) { Set-ItResult -Skipped -Because 'port 11434 is not free'; return }
      try {
        $r = Invoke-Launcher $sb @('models', 'pull', 'no-such-model:1b')
        $r.ExitCode | Should -Not -Be 0
        $r.Text | Should -Match 'could not download'
      } finally { Stop-FakeOllama $srv }
    }
  }
}
