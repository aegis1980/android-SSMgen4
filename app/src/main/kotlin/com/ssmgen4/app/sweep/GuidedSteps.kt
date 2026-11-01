package com.ssmgen4.app.sweep

/**
 * What the driver is asked to do while the ECU is sampled, one step at a time, so each
 * switch and sensor can be tied to a known action. Car parked: neutral, handbrake on.
 * Step 0 is the idle baseline everything else is compared against.
 *
 * Switch steps are 8 s because the first run showed a 1-2 s reaction lag at each change.
 * There is no cruise-control step: the user's car has none.
 */
object GuidedSteps {

    class Step(val label: String, val prompt: String, val seconds: Int)

    val STEPS = listOf(
        Step("Idle baseline", "Hands and feet off.\nLet it idle.", 8),
        Step("Brake", "Press and HOLD the BRAKE pedal.", 8),
        Step("Clutch", "Release the brake.\nPress and HOLD the CLUTCH.", 8),
        Step("Headlights", "Release the clutch.\nHEADLIGHTS ON.", 8),
        Step("Rear demister", "Headlights OFF.\nREAR DEMISTER ON.", 8),
        Step("Heater fan", "Demister OFF.\nHeater FAN ON, any speed.", 8),
        Step("A/C", "Leave the fan on.\nA/C ON.", 10),
        Step("Wipers", "A/C OFF, fan OFF.\nWIPERS ON.", 8),
        Step("Steering lock", "Wipers OFF.\nTurn the wheel to FULL LOCK and hold.", 8),
        Step("Throttle blips", "Centre the wheel.\nBLIP the throttle to about 3000 rpm,\n3 or 4 times.", 16),
        Step("Steady 2500 rpm", "Hold a STEADY 2500 rpm.", 8),
        Step("Idle again", "Off the throttle.\nLet it idle.", 6),
    )

    val totalSeconds: Int get() = STEPS.sumOf { it.seconds }
}
