package io.github.jd1378.otphelper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuRepairManagerTest {
  private val packageName = "io.github.jd1378.otphelper"
  private val listener = "$packageName/$packageName.NotificationListener"

  @Test
  fun android14PlanOnlyRefreshesListenerRegistration() {
    val commands = ShizukuRepairManager.buildRepairCommands(packageName, listener, 34)

    assertEquals(2, commands.size)
    assertFalse(commands.any { it.contains("RECEIVE_SENSITIVE_NOTIFICATIONS") })
    assertEquals("cmd notification disallow_listener '$listener'", commands[0])
    assertEquals("cmd notification allow_listener '$listener'", commands[1])
  }

  @Test
  fun android15PlanAppliesSensitiveAppOpBeforeTrustedListenerRefresh() {
    val commands = ShizukuRepairManager.buildRepairCommands(packageName, listener, 35)

    assertEquals(3, commands.size)
    assertEquals(
        "cmd appops set --user current '$packageName' " +
            "RECEIVE_SENSITIVE_NOTIFICATIONS allow",
        commands[0],
    )
    assertEquals("cmd notification disallow_listener '$listener'", commands[1])
    assertEquals("cmd notification allow_listener '$listener'", commands[2])
  }

  @Test
  fun shellArgumentsAreSafelyQuoted() {
    val unusualPackage = "example.package'quoted"
    val commands = ShizukuRepairManager.buildRepairCommands(unusualPackage, listener, 35)

    assertTrue(commands.all { command -> !command.contains("example.package'quoted") })
    assertTrue(commands.any { it.contains("'example.package'\\''quoted'") })
  }
}
