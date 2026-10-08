@echo off
echo === Publicando Memora no GitHub ===
echo.
set /p USER="Digite seu usuario do GitHub: "
set /p REPO="Digite o nome do repositorio (ex: memora-android): "

cd /d "%~dp0"

git init
git add .
git commit -m "feat: Memora Android app v1.0"
git branch -M main
git remote add origin https://github.com/%USER%/%REPO%.git
git push -u origin main

echo.
echo === Pronto! Acesse: https://github.com/%USER%/%REPO%/actions ===
echo O APK sera gerado automaticamente em alguns minutos.
pause
