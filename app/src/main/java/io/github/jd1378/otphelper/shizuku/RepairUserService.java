package io.github.jd1378.otphelper.shizuku;

import android.content.Context;
import android.os.RemoteException;
import android.os.SystemClock;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Short-lived Shizuku UserService used only for the optional repair and external notification-body
 * verification action.
 *
 * <p>The normal notification listener, foreground service and watchdog do not depend on this
 * process. It is deliberately non-daemon and is destroyed immediately after the commands finish.
 */
@Keep
public final class RepairUserService extends IRepairService.Stub {
  private static final long COMMAND_TIMEOUT_MILLIS = 10_000L;
  private static final int MAX_OUTPUT_LINES = 30;

  public RepairUserService() {}

  /** Constructor used by Shizuku API v13+. */
  @Keep
  public RepairUserService(Context context) {}

  @Override
  public String execute(String[] commands) throws RemoteException {
    if (commands == null || commands.length == 0) {
      throw new RemoteException("No repair commands were supplied");
    }

    StringBuilder summaries = new StringBuilder();
    for (String command : commands) {
      if (command == null || command.trim().isEmpty()) continue;
      if (summaries.length() > 0) summaries.append('\n');
      summaries.append(runCommand(command));
    }
    return summaries.toString();
  }

  private String runCommand(String command) throws RemoteException {
    Process process = null;
    try {
      process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
      int exitCode = waitForProcess(process, command);
      String output = readLimitedOutput(process);
      if (exitCode != 0) {
        throw new RemoteException(
            "Repair command failed (" + exitCode + "): " + command + "\n" + output);
      }
      return command + " => ok" + (output.trim().isEmpty() ? "" : " (" + output + ")");
    } catch (IOException e) {
      RemoteException remoteException = new RemoteException("Unable to run repair command: " + command);
      remoteException.initCause(e);
      throw remoteException;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      RemoteException remoteException = new RemoteException("Repair command interrupted: " + command);
      remoteException.initCause(e);
      throw remoteException;
    } finally {
      if (process != null) process.destroy();
    }
  }

  private int waitForProcess(Process process, String command)
      throws InterruptedException, RemoteException {
    long deadline = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MILLIS;
    while (true) {
      try {
        return process.exitValue();
      } catch (IllegalThreadStateException stillRunning) {
        if (SystemClock.elapsedRealtime() >= deadline) {
          process.destroy();
          throw new RemoteException("Repair command timed out: " + command);
        }
        Thread.sleep(50L);
      }
    }
  }

  private String readLimitedOutput(Process process) throws IOException {
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      int count = 0;
      while (count < MAX_OUTPUT_LINES && (line = reader.readLine()) != null) {
        if (output.length() > 0) output.append('\n');
        output.append(line);
        count++;
      }
    }
    return output.toString();
  }

  @Override
  public void destroy() {
    System.exit(0);
  }
}
