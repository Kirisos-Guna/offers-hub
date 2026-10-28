# Publishes the listener to the Hugging Face Space (a git repo of its own).
#   powershell -File deploy\push_hf_space.ps1 [-Space <user>/<space>]
# Needs a one-time `hf auth login` (write token, stored for git). Secrets live in the Space
# settings, never in these files.
param([string]$Space = "Kirisos-Guna/offers-hub-listener")
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $env:TEMP "offershub-space"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory $build | Out-Null

# Listener code only: no local database, media, sessions or .env.
$files = "config.py", "db.py", "firebase_publisher.py", "image_store.py", "listener.py",
         "offer_parser.py", "product_images.py", "telegram_source.py", "requirements.txt"
foreach ($f in $files) { Copy-Item (Join-Path $root "backend\$f") $build }
Copy-Item (Join-Path $PSScriptRoot "hf_space\Dockerfile"), (Join-Path $PSScriptRoot "hf_space\README.md") $build

Push-Location $build
try {
    git init -q -b main
    git add -A
    git -c user.name="offershub-deploy" -c user.email="deploy@users.noreply.huggingface.co" commit -q -m "Deploy listener"
    git push -f "https://huggingface.co/spaces/$Space" main
} finally {
    Pop-Location
}
Write-Host "Pushed to https://huggingface.co/spaces/$Space - the Space rebuilds and restarts by itself."
