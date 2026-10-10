<#
.SYNOPSIS
    Grants the stockmarket-api service account the realm-management roles that
    /api/v1/admin needs.

.DESCRIPTION
    realm-stockmarket.json already declares these roles, but Keycloak only reads an
    import file when its database is EMPTY. On a stack whose Keycloak database already
    existed when the user-administration slice was added, the mapping was therefore
    never applied, and every call to /api/v1/admin answers:

        502  This application's service account is not allowed to manage users.

    This script applies it to a running stack. It is idempotent -- re-running it on an
    already-granted account changes nothing -- so it is safe to run when in doubt.

    The four roles are the minimum that works. manage-users is what permits realm-role
    MAPPING (Keycloak's RolePermissions.canMapRole returns early on MANAGE_USERS for any
    role that is not one of its own built-in admin roles), so manage-realm is deliberately
    NOT granted. view-users and query-users cover the list and the search; view-realm
    covers GET /roles, which is how the assignable privileges are discovered rather than
    hard-coded.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File docker\keycloak\grant-service-account-roles.ps1

.NOTES
    Needs the stack up (docker compose -f docker.yaml up -d) and the bootstrap admin
    credentials -- admin/admin unless KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD say otherwise.
#>
[CmdletBinding()]
param(
    [string]$Container = 'sma-keycloak',
    [string]$Realm     = 'stockmarket',
    [string]$ClientId  = 'stockmarket-api',
    [string]$AdminUser = $(if ($env:KEYCLOAK_ADMIN) { $env:KEYCLOAK_ADMIN } else { 'admin' }),
    [string]$AdminPass = $(if ($env:KEYCLOAK_ADMIN_PASSWORD) { $env:KEYCLOAK_ADMIN_PASSWORD } else { 'admin' })
)

$ErrorActionPreference = 'Stop'

$Kcadm = '/opt/keycloak/bin/kcadm.sh'
# kcadm caches its login in a config file. Pointed at /tmp so the script never depends on
# /opt/keycloak being writable, which varies between Keycloak image builds.
$Config = '/tmp/kcadm-sma.config'
$ServiceAccount = "service-account-$ClientId"
$Roles = @('manage-users', 'view-users', 'query-users', 'view-realm')

function Invoke-Kcadm {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & docker exec $Container $Kcadm @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "kcadm.sh $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

Write-Host "Keycloak container : $Container"
Write-Host "Realm              : $Realm"
Write-Host "Service account    : $ServiceAccount"
Write-Host ''

# The admin console lives in the MASTER realm, and inside the container Keycloak listens on
# 8080 -- 8180 is only the host-side port mapping.
Write-Host 'Authenticating as the bootstrap admin...'
Invoke-Kcadm @(
    'config', 'credentials',
    '--config', $Config,
    '--server', 'http://localhost:8080',
    '--realm', 'master',
    '--user', $AdminUser,
    '--password', $AdminPass
) | Out-Null

Write-Host "Granting: $($Roles -join ', ')"
$addArgs = @(
    'add-roles',
    '--config', $Config,
    '-r', $Realm,
    '--uusername', $ServiceAccount,
    '--cclientid', 'realm-management'
)
foreach ($role in $Roles) { $addArgs += @('--rolename', $role) }
Invoke-Kcadm $addArgs | Out-Null

Write-Host ''
Write-Host 'Now mapped:'
Invoke-Kcadm @(
    'get-roles',
    '--config', $Config,
    '-r', $Realm,
    '--uusername', $ServiceAccount,
    '--cclientid', 'realm-management',
    '--fields', 'name',
    '--format', 'csv'
)

Write-Host ''
Write-Host 'Done. The backend caches its service-account token, so a token minted before'
Write-Host 'this change still carries the old roles -- it re-mints once on a 403, but if the'
Write-Host 'backend is running the pre-fix build, restart it to clear the cache.'
