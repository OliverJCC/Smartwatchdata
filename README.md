# Smartwatchdata

Proyecto Android con dos aplicaciones complementarias para recolectar datos de movimiento en un reloj Wear OS y transferirlos a un teléfono Android.

## Módulos

- **`app` (reloj):** captura datos del acelerómetro, giroscopio, gravedad y aceleración lineal. Guarda capturas CSV y permite exportarlas como ZIP al teléfono mediante Google Play Services Wearable.
- **`mobile` (teléfono):** recibe los archivos ZIP del reloj y permite acceder a ellos para abrirlos o compartirlos. En versiones compatibles, guarda una copia en `Descargas/SmartwatchData`.

## Requisitos

- Android Studio compatible con Android Gradle Plugin 8.13.0.
- JDK 17 y Android SDK 36.
- Reloj con Wear OS basado en Android API 30 o superior.
- Teléfono Android API 24 o superior.
- Reloj y teléfono vinculados, con Google Play Services disponible.

## Abrir y ejecutar

1. Clona este repositorio y abre la carpeta raíz en Android Studio.
2. Sincroniza Gradle e instala los componentes del SDK que solicite el IDE.
3. Ejecuta el módulo `app` en el reloj y el módulo `mobile` en el teléfono.
4. Concede los permisos solicitados, inicia una captura en el reloj y utiliza la opción de exportación para enviar los datos al teléfono.

También puedes generar los APK de depuración desde PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug
```

## Estructura

```text
app/       Aplicación Wear OS y servicio de captura
mobile/    Aplicación Android y servicio receptor de ZIP
gradle/    Catálogo de versiones y Gradle Wrapper
```

## Archivos locales

La configuración del SDK (`local.properties`), cachés del IDE, resultados de compilación y claves de firma se excluyen del repositorio mediante `.gitignore`.
