# Creates disposable local containers; never uses the existing project DB/Redis or persistent volumes.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$tag = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$mysqlName = 'launch-verify-mysql-' + $tag
$redisName = 'launch-verify-redis-' + $tag
$mysqlCreated = $false
$redisCreated = $false
$testExit = 1
$savedEnv = @{}
$envNames = @('MYSQL_ROOT_PASSWORD','LAUNCH_MYSQL_VERIFY','LAUNCH_MYSQL_PASSWORD','DEEPSEEK_API_KEY','DASHSCOPE_API_KEY','MYSQL_PASSWORD','JWT_SECRET','APP_DATA_KEY')
foreach ($name in $envNames) { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:MYSQL_ROOT_PASSWORD = [Guid]::NewGuid().ToString('N')
    $env:LAUNCH_MYSQL_PASSWORD = $env:MYSQL_ROOT_PASSWORD
    $env:MYSQL_PASSWORD = $env:MYSQL_ROOT_PASSWORD
    $env:LAUNCH_MYSQL_VERIFY = 'true'
    $env:DEEPSEEK_API_KEY = ''
    $env:DASHSCOPE_API_KEY = ''
    $env:JWT_SECRET = 'launch-local-test-jwt-secret-at-least-32-bytes-0000'
    $env:APP_DATA_KEY = 'MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI='
    docker run -d --name $mysqlName --label "launch-verification=$tag" -p 127.0.0.1:13317:3306 -e MYSQL_ROOT_PASSWORD mysql:8.0 *> target/launch-mysql-container.log
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated MySQL container; check port 13317.' }
    $mysqlCreated = $true
    docker run -d --name $redisName --label "launch-verification=$tag" -p 127.0.0.1:16387:6379 redis:7-alpine *> target/launch-redis-container.log
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated Redis container; check port 16387.' }
    $redisCreated = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        docker exec $mysqlName sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping --silent' *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (!$ready) { throw 'Isolated MySQL did not become ready.' }
    .\mvnw.cmd '-Dtest=LaunchMysqlVerificationTest' test *> target/launch-mysql-verification.log
    $testExit = $LASTEXITCODE
    Get-Content target/launch-mysql-verification.log -Tail 18
} finally {
    # Only remove containers this invocation successfully created, with the matching ownership label.
    foreach ($item in @(@($mysqlName,$mysqlCreated),@($redisName,$redisCreated))) {
        if ($item[1]) {
            $ownerTag = docker inspect --format '{{ index .Config.Labels "launch-verification" }}' $item[0] 2>$null
            if ($ownerTag -eq $tag) { docker rm -f -v $item[0] *> $null }
        }
    }
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], 'Process') }
}
exit $testExit
