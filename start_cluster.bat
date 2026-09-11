@echo off
echo ==================================================================
echo           VisionPulse Platform Launcher (Windows)
echo ==================================================================

echo [1/3] Starting Docker Infrastructure (ZooKeeper, Kafka, Redis, Postgres)...
docker compose up -d
if %errorlevel% neq 0 (
    echo [WARN] Docker compose failed or Docker is not running.
    echo Please make sure Docker Desktop is started.
)

echo [2/3] Opening Real-Time Observability Dashboard...
start "" "%~dp0dashboard\index.html"

echo [3/3] Starting Spring Boot Distributed Coordinator (Node 1 on port 8081)...
cd /d "%~dp0event-coordinator"
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --visionpulse.node-id=1"

pause
