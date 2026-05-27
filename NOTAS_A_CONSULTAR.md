# NOTAS A CONSULTAR / NOTES FOR REFERENCE

Este documento contiene instrucciones de configuración para el controlador y aclaraciones sobre el comportamiento del pedal con la aplicación.
This document contains configuration instructions for the controller and clarifications regarding the pedal's behavior with the application.

---

## 1. CONFIGURACIÓN DEL CONTROLADOR (ESP32) / CONTROLLER CONFIGURATION (ESP32)

### Castellano (Spanish)
Para poder conectar la aplicación de Android al controlador ESP32 mediante Bluetooth, es necesario realizar los siguientes pasos de configuración inicial a través de la interfaz web por Wi-Fi de la placa:

1. **Conectarse a la red Wi-Fi del controlador** y acceder a su portal web de configuración (usualmente a través de la IP `192.168.4.1`).
2. Dirigirse a la pestaña de configuración de **Bluetooth** (o Bluetooth Config).
3. Asegurarse de activar y configurar los siguientes campos:
   - **Bluetooth Mode:** Configurarlo en **"Peripheral"** (Modo Periférico) para que la placa se anuncie y permita a la aplicación Android conectarse a ella como cliente (Central).
   - **Midi over BT (Midi CC on BT / BLE Midi):** Asegurarse de tener habilitada esta opción para permitir el tráfico de mensajes de control MIDI a través del canal Bluetooth.
   - **Custom BT Device / Peripheral Name:** Puedes activar el nombre personalizado. Se recomienda configurar el nombre por defecto como **`TnxBT`** para que la app se conecte automáticamente al iniciar.
4. Guardar los cambios y reiniciar la placa.

---

### English
To connect the Android app to the ESP32 controller via Bluetooth, you must perform the following initial configuration steps via the board's Wi-Fi web interface:

1. **Connect to the controller's Wi-Fi network** and access its web configuration portal (usually at `192.168.4.1`).
2. Navigate to the **Bluetooth Settings** tab.
3. Configure the following fields:
   - **Bluetooth Mode:** Set it to **"Peripheral"** so the controller advertises itself and allows the Android app to connect as a client (Central).
   - **Midi over BT (Midi CC on BT / BLE Midi):** Ensure this option is enabled to allow MIDI control messages over the Bluetooth link.
   - **Custom BT Device / Peripheral Name:** You can set a custom name. We recommend setting the default name to **`TnxBT`** so the app auto-connects on startup.
4. Save the changes and reboot the board.

---

## 2. GUARDADO DE CAMBIOS EN LOS PRESETS / SAVING PRESET CHANGES

### Castellano (Spanish)
Los cambios en los parámetros del amplificador o los efectos que se realizan desde la aplicación se aplican en tiempo real al sonido del pedal, pero **no se guardan automáticamente en la memoria interna (Flash) del pedal**. Si cambias de preset o reinicias el pedal, se perderán.

Para guardarlos permanentemente en el pedal:

* **TONEX Pedal (Grande):**
  - Mantén pulsado el potenciómetro físico **PRESET** del propio pedal hasta que aparezca la confirmación en la pantalla para guardar los cambios en el slot actual.

* **TONEX ONE (Pequeño):**
  - Dado que el pedal no permite recibir comandos de guardado vía MIDI CC, debes forzar al pedal a guardar su buffer de edición actual:
    1. Haz un **pequeño cambio físico** moviendo levemente cualquiera de los micro-potenciómetros (por ejemplo, el Gain o el Bass) para que el pedal registre que ha habido una acción de edición por hardware.
    2. Mantén pulsado el botón físico **ALT** del pedal durante unos 2 segundos.
    3. Los micro-potenciómetros parpadearán, indicando que todo el estado actual (incluyendo todos los cambios remotos que habías enviado desde la app) se ha guardado de forma permanente.

---

### English
Parameters altered via the app (amplifier settings or effects) are updated in real-time on the pedal's active sound buffer, but **they are not written automatically to the pedal's internal flash memory**. If you switch presets or reboot the pedal, these changes will be lost.

To write them permanently to the pedal:

* **TONEX Pedal (Large):**
  - Press and hold the physical **PRESET** encoder on the pedal until the screen prompts/confirms that the preset is saved.

* **TONEX ONE (Small):**
  - Since the pedal does not support remote "save" triggers via MIDI CC, you must force the pedal to dump its active edit buffer manually:
    1. Make a **tiny physical adjustment** to any micro-knob on the pedal (such as Gain or Bass) to tell the pedal a hardware edit has occurred.
    2. Press and hold the physical **ALT** button on the pedal for about 2 seconds.
    3. The micro-knob LEDs will flash, indicating that the entire active sound state (including all the remote tweaks you sent from the app) has been written permanently to the selected preset slot.
