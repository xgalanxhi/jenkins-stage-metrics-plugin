# Quick deployment script for Jenkins plugin development
# Usage: .\deploy-to-jenkins.ps1 [jenkins-path]

param(
    [string]$JenkinsPath = "C:\Program Files\Jenkins",
    [switch]$HotReload = $false
)

Write-Host "Building plugin..." -ForegroundColor Yellow
mvn clean package

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    exit 1
}

$hpiFile = "target\demo.hpi"
$jenkinsPluginsDir = Join-Path $JenkinsPath "plugins"
$targetFile = Join-Path $jenkinsPluginsDir "demo.hpi"

if (Test-Path $hpiFile) {
    Write-Host "Deploying to Jenkins..." -ForegroundColor Yellow
    
    if ($HotReload) {
        Write-Host "Attempting hot reload..." -ForegroundColor Cyan
        Copy-Item $hpiFile $targetFile -Force
        Write-Host "Plugin deployed! Check Jenkins for hot reload success." -ForegroundColor Green
        Write-Host "If hot reload failed, restart Jenkins service manually." -ForegroundColor Yellow
    } else {
        Write-Host "Stopping Jenkins service..." -ForegroundColor Cyan
        net stop jenkins
        
        Write-Host "Copying plugin file..." -ForegroundColor Cyan
        Copy-Item $hpiFile $targetFile -Force
        
        Write-Host "Starting Jenkins service..." -ForegroundColor Cyan
        net start jenkins
        
        Write-Host "Plugin deployed and Jenkins restarted!" -ForegroundColor Green
    }
} else {
    Write-Host "Build artifact not found: $hpiFile" -ForegroundColor Red
    exit 1
}

Write-Host "`nDeployment complete! Your plugin should be available in Jenkins." -ForegroundColor Green
