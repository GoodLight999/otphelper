package io.github.jd1378.otphelper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuRepairManagerTest {
  private val packageName = "io.github.jd1378.otphelper"
  private val listener = "$packageName/$packageName.NotificationListener"

  @Test
  fun android14PlanDoesNotUseAndroid15SensitiveNotificationOp() {
    val commands = ShizukuRepairManager.buildRepairCommands(packageName, listener, 34)

    assertEquals(4, commands.size)
    assertFalse(commands.any { it.contains("RECEIVE_SENSITIVE_NOTIFICATIONS") })
    assertTrue(commands.any { it == "cmd notification allow_listener '$listener'" })
    assertTrue(commands.any { it == "cmd deviceidle whitelist +'$packageName'" })
    assertTrue(
        commands
            .filter { it.startsWith("cmd appops") }
            .all { it.contains("--user current '$packageName'") })
  }

  @Test
  fun android15PlanIncludesSensitiveNotificationOp() {
    val commands = ShizukuRepairManager.buildRepairCommands(packageName, listener, 35)

    assertEquals(5, commands.size)
    assertTrue(
        commands.any {
          it ==
              "cmd appops set --user current '$packageName' " +
                  "RECEIVE_SENSITIVE_NOTIFICATIONS allow"
        })
  }

  @Test
  fun shellArgumentsAreSafelyQuoted() {
    val unusualPackage = "example.package'quoted"
    val commands = ShizukuRepairManager.buildRepairCommands(unusualPackage, listener, 35)

    assertTrue(commands.all { command -> !command.contains("example.package'quoted") })
    assertTrue(commands.any { it.contains("'example.package'\\''quoted'") })
  }

  @Test
  fun probeIsPostedByShellWithUniqueTagAndOtpBody() {
    val command =
        ShizukuRepairManager.buildProbeCommand(
            NotificationIngestionSelfTest.Probe(token = "123456", tag = "otphelper_probe_1"))

    assertEquals(
        "cmd notification post -t 'OTP Helper external read test' " +
            "'otphelper_probe_1' 'One-time verification code: 123456'",
        command,
    )
  }
}
