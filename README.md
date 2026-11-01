# SSMgen4

Android Auto gauges for a 2006 Subaru, read live from the ECU over Subaru Select Monitor (SSM)
on CAN through a Bluetooth OBD adapter (developed against an OBDLink LX, STN1155). Everything is
read-only: the app sends SSM init and read-address requests, never writes.

## On the car screen

Three pages, turned by swiping, tapping near either edge, or the first button of the action strip:

- **Trip**: fuel economy now and on average, litres used, distance, time and average speed.
  Fuel is estimated from airflow; the trip survives reconnects until reset from the car.
- **Gauges**: boost, rpm, speed and coolant dials, and a six-speed shift gate showing the current
  gear (worked out from engine against road speed with the TY856WBDAA gearing, calibrating itself
  to the tyres; neutral and clutch come from the ECU's switches).
- **Stuff**: check-engine light and trouble codes, IAM, fuel trims, battery, coolant and intake
  temperatures, misfire counts, cam advance, and lights for A/C, fan, headlights, clutch and brake.

Each page polls only what it shows, plus what the trip and gear need, so the gauges keep their
update rate. The app draws on the surface Android Auto gives navigation apps (Car App Library
`NavigationTemplate` + `ACCESS_SURFACE`), through a `VirtualDisplay` and `Presentation`.

## On the phone

The launcher screen gets the Bluetooth permission, shows the adapter and live readings, and runs
the **parameter sweep**: every parameter, switch and trouble-code byte the RomRaider logger
definitions list for the SSM protocol, probed and then sampled through guided steps (brake,
clutch, lights, A/C, throttle blips…). Reports land in
`Android/data/com.ssmgen4.app/files/sweep/`. It can also be started over adb:

    adb shell am start -n com.ssmgen4.app/.MainActivity --ez sweep true

## Code

`app/src/main/kotlin/com/ssmgen4/app/`

| Package | What |
|---|---|
| `car` | Car App Library session, screen, surface callback, pages, gestures, trip and gear storage |
| `gauge` | Views drawn on the car screen: dials, shift gate, trip and stuff pages |
| `data` | Metric sources (SSM and simulated), trip computer, gear estimator, trouble codes |
| `ssm` | SSM protocol, ISO-TP framing, the standard parameters and switches used |
| `elm` | Bluetooth SPP port and the STN/ELM327 CAN link |
| `defs` | Parser for the RomRaider logger definitions (`assets/definitions/`) |
| `sweep` | The parameter sweep and its reports |

## Build, test, install

    gradlew :app:assembleDebug :app:testDebugUnitTest

Compile SDK 37, target SDK 36, min SDK 23. Unit tests run on the JVM and include the real
logger definitions.

Android Auto only shows Car App Library apps in a real car when they come from Google Play;
sideloading works on the Desktop Head Unit only. To get a build into the car, upload the APK
from `app/build/outputs/apk/debug/` to Play Console **Internal App Sharing** and open the link
on the phone. Build it with Gradle as above: Android Studio's Run adds `testOnly`, which Play
rejects.

Car-side logging uses the tag `SsmGen4Car`; the SSM link logs as `SsmSource` and `SppPort`.
