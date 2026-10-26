$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$mvn=(Get-Command mvn -ErrorAction SilentlyContinue).Source
if (-not $mvn) { $mvn='C:\Users\jaybh\.m2\wrapper\dists\apache-maven-3.9.14\ed7edd442f634ac1c1ef5ba2b61b6d690b5221091f1a8e1123f5fadcc967520d\bin\mvn.cmd' }
if (-not (Test-Path $mvn)) { throw 'Maven 3.9+ is required. Install Maven or use a Maven Wrapper.' }
$mock=Start-Process python -ArgumentList "$root\starter-kit\mock-apis\mock_server.py",'--no-flake' -PassThru -WindowStyle Hidden
try { & $mvn -q -DskipTests spring-boot:run } finally { Stop-Process -Id $mock.Id -Force -ErrorAction SilentlyContinue }
