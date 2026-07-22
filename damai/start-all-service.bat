@echo off
chcp 65001 >nul

echo ==============================================
echo          一键启动所有微服务
echo ==============================================
echo.

set "BASE_DIR=E:\codexop\damai\damai-server"

:: 启动 5 个服务（每个独立窗口）
echo 正在启动服务...

start "BaseData" cmd /c "cd /d "%BASE_DIR%\damai-base-data-service" && mvn spring-boot:run"
timeout /t 3 /nobreak >nul

start "Pay" cmd /c "cd /d "%BASE_DIR%\damai-pay-service" && mvn spring-boot:run"
timeout /t 3 /nobreak >nul

start "Order" cmd /c "cd /d "%BASE_DIR%\damai-order-service" && mvn spring-boot:run"
timeout /t 3 /nobreak >nul

start "Program" cmd /c "cd /d "%BASE_DIR%\damai-program-service" && mvn spring-boot:run"
timeout /t 3 /nobreak >nul

start "User" cmd /c "cd /d "%BASE_DIR%\damai-user-service" && mvn spring-boot:run"
timeout /t 3 /nobreak >nul

start "Gateway" cmd /c "cd /d "%BASE_DIR%\damai-gateway-service" && mvn spring-boot:run"

echo.
echo 所有服务已启动，请查看各窗口日志
echo 服务端口: 6082(user)、6083(base-data)、6085(gateway)、
echo           6086(program)、8081(order)、8080(pay)
echo.
pause
