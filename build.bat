@echo off
setlocal
cd /d "%~dp0"
if not exist build mkdir build
if not exist build\classes mkdir build\classes
del /q build\classes\fr\examen\reservation\*.class 2>nul
javac -encoding UTF-8 -d build\classes src\main\java\fr\examen\reservation\ReservationApp.java
if errorlevel 1 exit /b 1
set JAR_TOOL=jar
where jar >nul 2>nul
if errorlevel 1 (
  for /d %%D in ("%ProgramFiles%\Java\jdk-*") do if exist "%%~D\bin\jar.exe" set "JAR_TOOL=%%~D\bin\jar.exe"
)
"%JAR_TOOL%" --create --file reservation-app.jar --main-class fr.examen.reservation.ReservationApp -C build\classes .
if errorlevel 1 exit /b 1
echo JAR créé : %cd%\reservation-app.jar
