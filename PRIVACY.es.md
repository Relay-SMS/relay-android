# Política de privacidad — SMS Relay

_Última actualización: 13 de noviembre de 2026_ · 🇬🇧 [English](PRIVACY.md) · 🇫🇷 [Français](PRIVACY.fr.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇮🇹 [Italiano](PRIVACY.it.md)

> **Traducción.** Esta traducción al español se facilita a título informativo. Solo las versiones
> [inglesa](PRIVACY.md) y [francesa](PRIVACY.fr.md) de esta política hacen fe; en caso de
> discrepancia, prevalecen las versiones inglesa y francesa.

SMS Relay (`com.filestech.sms`) forma parte de la suite **Files Tech**, editada por **Patrice
Haltaya**. Véanse también las [condiciones de uso](TERMS.es.md).

## Lo que recopilamos

**Nada.** SMS Relay no recopila, no transmite ni agrega ningún dato personal — con dos
excepciones, ambas iniciadas por usted: cuando busca un GIF, las palabras que escribe se envían a
Klipy para que devuelva GIF coincidentes, y cuando un mensaje contiene un enlace, Relay descarga
esa página una vez para mostrar una tarjeta de vista previa (véase [Red](#red) más abajo). Relay
no conserva nada de esos intercambios más allá del GIF que usted elige enviar y de la vista
previa que le muestra. Vincular un ordenador no cambia nada de esto: el teléfono y el ordenador
se comunican directamente entre sí en su red Wi-Fi local — ningún servidor Relay, ninguna nube,
nada sale de la red.

Ningún SDK de analítica, ningún notificador de fallos, ningún endpoint de telemetría, ningún
identificador publicitario, ninguna biblioteca de fingerprinting. El binario no contiene ningún
código de rastreo de terceros.

## Lo que permanece en su dispositivo

- Sus SMS y MMS, en una base de datos Room cifrada (SQLCipher) protegida por una clave envuelta por
  el AndroidKeyStore.
- Los metadatos de las conversaciones (borradores, fijación, archivado, caja fuerte, preferencias
  por conversación).
- Los ajustes, en Android DataStore Preferences.
- Un hash con sal PBKDF2-HMAC-SHA512 de su código PIN (el PIN nunca se almacena en claro).
- Los archivos adjuntos MMS que pueda haber, en `<files>/mms_attachments/`.
- Los PDF de conversación que se hayan generado localmente, en `<files>/exports/`.
- Si los configura, sus contactos de emergencia y los ajustes del modo emergencia y de Safety call.
- Si vincula un dispositivo, su ficha: identificador del dispositivo, tipo (ordenador, teléfono
  o tableta), nombre visible, clave pública y fecha de vinculación. La clave privada
  correspondiente nunca sale del dispositivo vinculado, y la ficha se elimina cuando usted quita
  el dispositivo. El teléfono conserva además su propio identificador de hub y el certificado TLS
  y la clave privada que el servidor local presenta a los dispositivos vinculados (la clave
  privada reside en el AndroidKeyStore).

Como toda aplicación de SMS predeterminada, SMS Relay también escribe sus mensajes en el almacén de
mensajes del sistema de Android, que los conserva con independencia de la aplicación.

La copia de seguridad del sistema de Android está **desactivada**: estos datos no se envían ni a
Google Drive ni en una transferencia de dispositivo sin su consentimiento explícito.

## Red

SMS Relay tiene el permiso `INTERNET` para tres usos. (1) Búsqueda de GIF: cuando abre la pestaña
de GIF y escribe una búsqueda, las palabras que escribe se envían a Klipy (`api.klipy.com`), que
devuelve GIF coincidentes; tocar uno lo descarga de Klipy, y las miniaturas de la cuadrícula
también provienen de Klipy. Klipy ve sus palabras de búsqueda y su dirección IP, como cualquier
servicio al que usted se conecta. (2) Vistas previas de enlaces: cuando un mensaje contiene una
URL, Relay descarga esa página una vez, en el dispositivo, y lee su título e imagen Open Graph
solo para mostrarlos — el sitio enlazado ve la solicitud y su dirección IP, como en cualquier
visita; la imagen de vista previa nunca se envía como archivo adjunto. (3) Dispositivos
vinculados: el teléfono ejecuta un servidor TLS en la red local (puerto 47831, anunciado por
mDNS como `_relay._tcp.`) para que un ordenador que usted haya vinculado pueda encontrarlo y
conectarse. El servidor solo acepta dispositivos cuya clave pública usted guardó escaneando su
código de vinculación, y solo después de que demuestren poseer la clave privada correspondiente;
el tráfico nunca sale de su red Wi-Fi y no interviene ningún servidor Relay ni ninguna cuenta en
la nube. El servidor puede desactivarse en Ajustes → Dispositivos vinculados; la aplicación
funciona entonces plenamente por sí sola. Este es el único uso de red de la aplicación: ninguna
comprobación de actualizaciones, ninguna configuración remota, ningún ping analítico, ningún
informe de fallos. Si el permiso está bloqueado, la aplicación sigue funcionando — la búsqueda de
GIF simplemente no devuelve nada, los enlaces no muestran vista previa y ningún ordenador puede
vincularse. (El permiso se retiró en la 1.28.13, cuando la aplicación no realizaba ninguna
solicitud de red; volvió para la búsqueda de GIF, luego para las vistas previas de enlaces y
ahora para los dispositivos vinculados.)

Los MMS se encaminan a través del servicio MMS de Android, que se comunica con el MMSC de su
operador cuando usted envía o recibe uno. Los MMS nunca necesitaron el permiso `INTERNET` y
siguen sin usarlo.

Los mensajes que usted envía pasan por la red de su operador, como con cualquier aplicación de SMS.
El desarrollador nunca los recibe.

## Funciones de emergencia (inactivas mientras usted no las active)

- **Modo emergencia.** Cuando mantiene pulsado el botón de emergencia tres segundos, SMS Relay envía
  un SMS a los contactos de emergencia que **usted** ha elegido. Si ha concedido el permiso de
  ubicación, precisa o aproximada, y ha dejado «incluir mi ubicación» (activado de forma
  predeterminada en este modo), la aplicación pide a Android **una** posición en ese momento:
  reutiliza una posición de menos de cinco minutos de antigüedad si Android dispone de ella; si no,
  espera una nueva lectura durante ocho segundos como máximo y, en su defecto, reutiliza la última
  posición conocida si tiene menos de treinta minutos de antigüedad. Ningún seguimiento en segundo
  plano ni continuo. La posición se añade al SMS en forma de enlace `https://maps.google.com/?q=…`;
  cuando solo es aproximada, Android la desplaza unos dos kilómetros y el SMS lo indica con
  «(+/-N km)» después del enlace. Si un contacto abre ese enlace, es su navegador el que se comunica
  con Google Maps; la aplicación, por su parte, no envía nada a Google. Si la ubicación se deniega o
  no está disponible, el SMS lo indica y se envía sin coordenadas.
- **Safety call.** Si no utiliza la aplicación antes del plazo que ha fijado, SMS Relay envía un SMS
  de comprobación a los mismos contactos. No contiene ninguna posición.
- **Llamada de emergencia.** Los botones de llamada de la pantalla de emergencia proponen los
  números de emergencia del país en el que su teléfono está registrado en la red (el 112 figura
  siempre). Cuando toca uno, la aplicación solicita el permiso de llamada (`CALL_PHONE`) y, si lo
  concede, realiza la llamada ella misma; si lo deniega, abre el marcador telefónico ya rellenado,
  que no requiere ningún permiso. El acceso directo en la pantalla de bloqueo abre siempre el
  marcador telefónico.
- Ninguna de estas funciones se ejecuta en la sesión señuelo abierta por el código de pánico.
- Estos mensajes son SMS ordinarios: su operador puede cobrarlos según su plan.

## Permisos

Consulte [PERMISSIONS.md](PERMISSIONS.md) para ver la justificación de cada permiso.

## Sus derechos

Como ningún dato personal sale de su dispositivo, no hay nada que consultar, rectificar o suprimir en
ningún sistema remoto. Para borrar sus datos en SMS Relay: desinstale la aplicación (Android elimina
sus datos automáticamente) o utilice **Ajustes → Eliminar todos mis datos**, que borra la base de
datos cifrada, los alias del Keystore, los archivos adjuntos en caché y los PDF exportados. Los
mensajes conservados por el almacén de mensajes del sistema de Android se gestionan desde Android o
desde cualquier aplicación de SMS.

## Contacto

Para cualquier pregunta: **contact@files-tech.com**.

El editor no recibe ningún dato de la aplicación. Solo le llega un correo que usted decida
escribirle: solo sirve para responderle, se conserva el tiempo del intercambio y después se borra.
Puede solicitar el acceso a él o su supresión en la misma dirección, y reclamar ante la CNIL, la
autoridad francesa de protección de datos (www.cnil.fr), si considera que no se respetan sus
derechos.
