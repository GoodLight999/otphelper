package io.github.jd1378.otphelper.shizuku;

import android.content.Context;
import android.os.RemoteException;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Short-lived Shizuku UserService used only for the optional repair action.
 *
 * <p>The normal notification listener, accessibility fallback, foreground service and watchdog do
 * not depend on this process. It is deliberately non-daemon and is destroyed immediately after the
 * commands finish.
 */
@Keep
public final class RepairUserService extends IRepairService.Stub {
  private static final long COMMAND_TIMEOUT_SECONDS = 10L;

  public RepairUserService() {}

  /** Constructor used by Shizuku API v13+. */
  @Keep
  public RepairUserService(Context context) {}

  @Override
  public String execute(String[] commands) throws RemoteException {
    if (commands == null || commands.length == 0) {
      throw new RemoteException("No repair commands were supplied");
    }

    List<String> summaries = new ArrayList<>();
    for (String command : commands) {
      if (command == null || command.isBlank()) continue;
      summaries.add(runCommand(command));
    }
    return String.join("\n", summaries);
  }

  private String runCommand(String command) throws RemoteException {
    Process process = null;
    try {
      process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
      boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new RemoteException("Repair command timed out: " + command);
      }

      String output;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        output = reader.lines().limit(30).reduce("", (left, right) -> left + right + "\n").trim();
      }

      int exitCode = process.exitValue();
      if (exitCode != 0) {
        throw new RemoteException(
            "Repair command failed (" + exitCode + "): " + command + "\n" + output);
      }
      return command + " => ok" + (output.isBlank() ? "" : " (" + output + ")");
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

  @Override
  public void destroy() {
    System.exit(0);
  }
}
