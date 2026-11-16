<#
.SYNOPSIS
    Lord Gainsborough API - Production & Staging Endpoint Smoke Test
.EXAMPLE
    .\smoke-test.ps1 -BaseUrl "https://your-railway-app.up.railway.app"
#>
param(
    [string]$BaseUrl = "http://localhost:8080"
)

$BaseUrl = $BaseUrl.TrimEnd('/')
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Running Lord Gainsborough Endpoint Smoke Tests" -ForegroundColor Cyan
Write-Host " Target: $BaseUrl" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$Failed = 0

function Test-Endpoint {
    param(
        [string]$Method,
        [string]$Path,
        [int]$ExpectedStatus,
        [string]$Description
    )

    $url = "$BaseUrl$Path"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $statusCode = 0

    try {
        $response = Invoke-WebRequest -Uri $url -Method $Method -Headers @{ "Accept" = "application/json"; "User-Agent" = "LGB-SmokeTest/1.0" } -TimeoutSec 15 -SkipHttpErrorCheck -ErrorAction SilentlyContinue
        $statusCode = [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        } else {
            $statusCode = 0
        }
    }
    $sw.Stop()
    $timeTotal = "{0:N3}" -f ($sw.ElapsedMilliseconds / 1000)

    if ($statusCode -eq $ExpectedStatus) {
        Write-Host (" [PASS] {0,-6} {1,-28} -> HTTP {2} (expected {3}) in {4}s - {5}" -f $Method, $Path, $statusCode, $ExpectedStatus, $timeTotal, $Description) -ForegroundColor Green
    } else {
        Write-Host (" [FAIL] {0,-6} {1,-28} -> HTTP {2} (expected {3}) in {4}s - {5}" -f $Method, $Path, $statusCode, $ExpectedStatus, $timeTotal, $Description) -ForegroundColor Red
        $script:Failed++
    }
}

Test-Endpoint -Method "GET" -Path "/api/welcome" -ExpectedStatus 200 -Description "System health/welcome probe"
Test-Endpoint -Method "GET" -Path "/api/menu" -ExpectedStatus 200 -Description "Menu catalog retrieval"
Test-Endpoint -Method "GET" -Path "/api/categories" -ExpectedStatus 200 -Description "Menu categories retrieval"
Test-Endpoint -Method "GET" -Path "/api/settings" -ExpectedStatus 200 -Description "Pickup store settings & tax rate"
Test-Endpoint -Method "GET" -Path "/api/reviews" -ExpectedStatus 200 -Description "Customer reviews retrieval"

Test-Endpoint -Method "GET" -Path "/api/cart" -ExpectedStatus 401 -Description "Cart requires Bearer auth"
Test-Endpoint -Method "GET" -Path "/api/auth/profile" -ExpectedStatus 401 -Description "Profile requires Bearer auth"
Test-Endpoint -Method "GET" -Path "/api/orders" -ExpectedStatus 401 -Description "Order history requires Bearer auth"
Test-Endpoint -Method "GET" -Path "/api/kds/orders" -ExpectedStatus 401 -Description "Kitchen display requires Staff/Admin auth"

Test-Endpoint -Method "GET" -Path "/api/nonexistent-endpoint-smoke" -ExpectedStatus 404 -Description "Non-existent endpoint returns 404"

Write-Host "==========================================================" -ForegroundColor Cyan
if ($Failed -eq 0) {
    Write-Host " ALL SMOKE TESTS PASSED!" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host " SMOKE TESTS FAILED: $Failed check(s) did not match expected status." -ForegroundColor Red
    Write-Host "==========================================================" -ForegroundColor Cyan
    exit 1
}
